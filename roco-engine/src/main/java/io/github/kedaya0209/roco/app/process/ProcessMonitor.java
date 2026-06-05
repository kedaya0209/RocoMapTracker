package io.github.kedaya0209.roco.app.process;

import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 Win32 FFM API 查询子进程 CPU 和内存占用。
 * <p>
 * 用法：注册进程 PID 后周期调用 {@link #sample()}，返回各注册进程的瞬时读数。
 */
@Slf4j
public class ProcessMonitor {

    // ---- FFM 基础设施 ----
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
    private static final SymbolLookup PSAPI = SymbolLookup.libraryLookup("psapi", Arena.global());

    private static final MethodHandle OPEN_PROCESS;
    private static final MethodHandle CLOSE_HANDLE;
    private static final MethodHandle GET_PROCESS_TIMES;
    private static final MethodHandle GET_PROCESS_MEMORY_INFO;

    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
    private static final int PROCESS_VM_READ = 0x0010;

    static {
        try {
            OPEN_PROCESS = LINKER.downcallHandle(
                    KERNEL32.find("OpenProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT,      // dwDesiredAccess
                            ValueLayout.JAVA_INT,      // bInheritHandle
                            ValueLayout.JAVA_INT));     // dwProcessId

            CLOSE_HANDLE = LINKER.downcallHandle(
                    KERNEL32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG));

            GET_PROCESS_TIMES = LINKER.downcallHandle(
                    KERNEL32.find("GetProcessTimes").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,      // hProcess
                            ValueLayout.ADDRESS,         // lpCreationTime
                            ValueLayout.ADDRESS,         // lpExitTime
                            ValueLayout.ADDRESS,         // lpKernelTime
                            ValueLayout.ADDRESS));       // lpUserTime

            GET_PROCESS_MEMORY_INFO = LINKER.downcallHandle(
                    PSAPI.find("GetProcessMemoryInfo").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,      // hProcess
                            ValueLayout.ADDRESS,         // pcb
                            ValueLayout.JAVA_INT));      // cb
        } catch (Exception e) {
            throw new RuntimeException("Failed to init ProcessMonitor FFM handles", e);
        }
    }

    // PROCESS_MEMORY_COUNTERS_EX layout (x64):
    //   cb(4) + PageFaultCount(4) + PeakWorkingSetSize(8) + WorkingSetSize(8) +
    //   Quota*PoolUsage(8*4) + PagefileUsage(8) + PeakPagefileUsage(8) + PrivateUsage(8) = 80 bytes
    /** PrivateUsage 偏移量 — 与任务管理器"内存"列一致 */
    private static final long PMC_PRIVATE_OFFSET = 72;

    /** 逻辑 CPU 核心数，用于将 CPU% 归一化到系统总容量（与任务管理器一致） */
    private static final int CORE_COUNT = Runtime.getRuntime().availableProcessors();

    /** 一个注册项 */
    private static class ProcEntry {
        final int pid;
        long hProcess;       // 缓存的句柄，0 = 无效
        long prevKernelTime;
        long prevUserTime;
        long prevWallNanos;

        ProcEntry(int pid) {
            this.pid = pid;
        }

        /** 获取或打开句柄 */
        long handle() {
            if (hProcess == 0) {
                hProcess = openProcess(pid);
            }
            return hProcess;
        }

        void invalidateHandle() {
            if (hProcess != 0) {
                closeHandle(hProcess);
                hProcess = 0;
            }
        }
    }

    private final ConcurrentHashMap<String, ProcEntry> entries = new ConcurrentHashMap<>();

    /**
     * 注册一个进程到监控中。
     *
     * @param pluginId 插件标识
     * @param pid      进程 ID
     */
    public void register(String pluginId, int pid) {
        entries.put(pluginId, new ProcEntry(pid));
    }

    /**
     * 更新已注册进程的 PID。
     */
    public void updatePid(String pluginId, int pid) {
        ProcEntry entry = entries.get(pluginId);
        if (entry != null) {
            entry.invalidateHandle();
            // hProcess 会在下次 handle() 调用时重新打开
        }
    }

    /**
     * 移除一个不再需要的进程。
     */
    public void unregister(String pluginId) {
        ProcEntry entry = entries.remove(pluginId);
        if (entry != null) {
            entry.invalidateHandle();
        }
    }

    /**
     * 采样所有已注册进程，返回当前内存占用和 CPU 使用率。
     * CPU 使用率需第二次采样才有有效值（首次返回 0）。
     *
     * @return pluginId → Reading(内存 KB, CPU 百分比)
     */
    public Map<String, Reading> sample() {
        long now = System.nanoTime();
        Map<String, Reading> result = new HashMap<>();
        for (Map.Entry<String, ProcEntry> e : entries.entrySet()) {
            result.put(e.getKey(), sampleOne(e.getValue(), now));
        }
        return result;
    }

    private Reading sampleOne(ProcEntry entry, long now) {
        long hProc = entry.handle();
        if (hProc == 0) return new Reading(0, 0);

        try (Arena arena = Arena.ofConfined()) {
            // ---- 内存（PrivateUsage，与任务管理器一致）----
            MemorySegment pmc = arena.allocate(80);
            int memOk = (int) GET_PROCESS_MEMORY_INFO.invoke(hProc, pmc, 80);
            long workingSetBytes = 0;
            if (memOk != 0) {
                workingSetBytes = pmc.get(ValueLayout.JAVA_LONG, PMC_PRIVATE_OFFSET);
            }

            // ---- CPU 时间 ----
            MemorySegment creationTime = arena.allocate(8);
            MemorySegment exitTime = arena.allocate(8);
            MemorySegment kernelTime = arena.allocate(8);
            MemorySegment userTime = arena.allocate(8);
            int timesOk = (int) GET_PROCESS_TIMES.invoke(
                    hProc, creationTime, exitTime, kernelTime, userTime);

            double cpuPercent = 0;
            if (timesOk != 0 && entry.prevKernelTime != 0) {
                long kt = kernelTime.get(ValueLayout.JAVA_LONG, 0);
                long ut = userTime.get(ValueLayout.JAVA_LONG, 0);
                long prevTotal = entry.prevKernelTime + entry.prevUserTime;
                long total = kt + ut;
                long dtCpu = total - prevTotal;
                long dtWall = now - entry.prevWallNanos;
                if (dtWall > 0) {
                    // dtCpu 单位 = 100ns, dtWall 单位 = ns
                    // CPU% = dtCpu * 100 / dtWall * 100 / coreCount = dtCpu * 10_000 / dtWall / coreCount
                    cpuPercent = Math.min(100.0, dtCpu * 10_000.0 / dtWall / CORE_COUNT);
                }
                entry.prevKernelTime = kt;
                entry.prevUserTime = ut;
            } else if (timesOk != 0) {
                entry.prevKernelTime = kernelTime.get(ValueLayout.JAVA_LONG, 0);
                entry.prevUserTime = userTime.get(ValueLayout.JAVA_LONG, 0);
            }
            entry.prevWallNanos = now;

            long kb = workingSetBytes / 1024;
            return new Reading(kb, cpuPercent);
        } catch (Throwable ex) {
            log.warn("ProcessMonitor sample failed for pid={}", entry.pid, ex);
            entry.invalidateHandle();
            return new Reading(0, 0);
        }
    }

    // ---- private helpers ----

    private static long openProcess(int pid) {
        try {
            return (long) OPEN_PROCESS.invoke(PROCESS_QUERY_LIMITED_INFORMATION | PROCESS_VM_READ, 0, pid);
        } catch (Throwable e) {
            log.warn("OpenProcess failed pid={}", pid, e);
            return 0;
        }
    }

    private static void closeHandle(long h) {
        try {
            CLOSE_HANDLE.invoke(h);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 进程快照读数。
     *
     * @param memoryKB   工作集内存 (KB)
     * @param cpuPercent CPU 使用率百分比
     */
    public record Reading(long memoryKB, double cpuPercent) {}
}
