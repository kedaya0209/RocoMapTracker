package com.luoke.app.platform;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

/**
 * Windows JobObject 管理器 — 使用 FFM 将 Java 进程及 C++ 子进程纳入同一 JobObject.
 *
 * <p>双重策略:
 * <ol>
 *   <li>init() 中将 Java 自身加入 JobObject (GetCurrentProcess() 伪句柄=-1),
 *       之后 CreateProcess 创建的子进程理论上自动归属</li>
 *   <li>attachProcess() 中仍逐进程 OpenProcess + AssignProcessToJobObject,
 *       作为确定性兜底，并打印诊断日志确认归属状态</li>
 * </ol>
 *
 * <p>当 Java 进程退出/崩溃时，JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE 确保
 * JobObject 内所有子进程被 OS 自动终止，防止孤儿进程残留。
 */
@ThreadSafe
@Slf4j
public class JobObjectManager {

    private static final Linker LINKER = Linker.nativeLinker();

    // ---- kernel32 函数句柄 ----
    private static final MethodHandle CREATE_JOB_OBJECT_W;
    private static final MethodHandle SET_INFORMATION_JOB_OBJECT;
    private static final MethodHandle ASSIGN_PROCESS_TO_JOB_OBJECT;
    private static final MethodHandle IS_PROCESS_IN_JOB;
    private static final MethodHandle OPEN_PROCESS;
    private static final MethodHandle CLOSE_HANDLE;
    private static final MethodHandle GET_LAST_ERROR;

    // ---- 常量 ----
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int JOB_OBJECT_LIMIT_BREAKAWAY_OK = 0x0800;
    private static final int JOB_INFO_CLASS_EXTENDED_LIMIT = 9;
    /**
     * PROCESS_SET_QUOTA | PROCESS_TERMINATE | PROCESS_CREATE_PROCESS | PROCESS_QUERY_LIMITED_INFORMATION
     */
    private static final int PROCESS_ACCESS = 0x0100 | 0x0001 | 0x0080 | 0x1000;

    // JOBOBJECT_EXTENDED_LIMIT_INFORMATION (x64, 自然对齐): 144 bytes
    private static final int EXTENDED_LIMIT_INFO_SIZE = 144;
    private static final long LIMIT_FLAGS_OFFSET = 16;

    // ---- 状态 ----
    private static volatile long jobHandle;
    private static volatile boolean initialized;
    private static volatile boolean selfAssigned;

    static {
        try {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

            // HANDLE CreateJobObjectW(LPSECURITY_ATTRIBUTES, LPCWSTR)
            CREATE_JOB_OBJECT_W = LINKER.downcallHandle(
                    kernel32.find("CreateJobObjectW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

            // BOOL SetInformationJobObject(HANDLE, JOBOBJECTINFOCLASS, LPVOID, DWORD)
            SET_INFORMATION_JOB_OBJECT = LINKER.downcallHandle(
                    kernel32.find("SetInformationJobObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            // BOOL AssignProcessToJobObject(HANDLE, HANDLE)
            ASSIGN_PROCESS_TO_JOB_OBJECT = LINKER.downcallHandle(
                    kernel32.find("AssignProcessToJobObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

            // BOOL IsProcessInJob(HANDLE, HANDLE, PBOOL)
            IS_PROCESS_IN_JOB = LINKER.downcallHandle(
                    kernel32.find("IsProcessInJob").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS));

            // HANDLE OpenProcess(DWORD, BOOL, DWORD)
            OPEN_PROCESS = LINKER.downcallHandle(
                    kernel32.find("OpenProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            // BOOL CloseHandle(HANDLE)
            CLOSE_HANDLE = LINKER.downcallHandle(
                    kernel32.find("CloseHandle").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            // DWORD GetLastError()
            GET_LAST_ERROR = LINKER.downcallHandle(
                    kernel32.find("GetLastError").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
        } catch (Exception e) {
            throw new RuntimeException("Failed to init kernel32 FFM handles for JobObject", e);
        }
    }

    public static long getJobHandle() {
        return jobHandle;
    }

    /**
     * 创建并配置全局 JobObject — 整个进程生命周期只调用一次.
     * 注意: 不在此处将 Java 自身加入 JobObject，因为那会导致后续
     * PROC_THREAD_ATTRIBUTE_JOB_LIST 在 CreateProcessW 时报 err=87。
     * 自我归组推迟到首个子进程创建完成后由 attachSelf() 执行。
     */
    public static synchronized void init() {
        if (initialized) return;
        try {
            // 1. 创建匿名 JobObject
            long hJob = (long) CREATE_JOB_OBJECT_W.invoke(
                    MemorySegment.NULL, MemorySegment.NULL);
            if (hJob == 0) {
                log.warn("CreateJobObjectW 失败 err={}", getLastError());
                return;
            }

            // 2. 配置 KILL_ON_JOB_CLOSE + BREAKAWAY_OK（允许更新脚本脱离 JobObject）
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment info = arena.allocate(EXTENDED_LIMIT_INFO_SIZE);
                info.set(ValueLayout.JAVA_INT, LIMIT_FLAGS_OFFSET,
                        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_BREAKAWAY_OK);

                int result = (int) SET_INFORMATION_JOB_OBJECT.invoke(
                        hJob, JOB_INFO_CLASS_EXTENDED_LIMIT, info, EXTENDED_LIMIT_INFO_SIZE);
                if (result == 0) {
                    log.warn("SetInformationJobObject 失败 err={}", getLastError());
                }
            }

            jobHandle = hJob;
            initialized = true;
            log.info("JobObject 已创建 (尚未归组 Java 自身) handle=0x{}", Long.toHexString(hJob));
        } catch (Throwable e) {
            log.error("JobObject 初始化失败", e);
        }
    }

    /**
     * 将 Java 自身加入全局 JobObject — 在首个子进程通过 JOB_LIST 创建成功后调用.
     * 幂等: 多次调用安全。
     */
    public static void attachSelf() {
        if (selfAssigned) return;
        long hJob = jobHandle;
        if (hJob == 0) return;

        synchronized (JobObjectManager.class) {
            if (selfAssigned) return;
            // GetCurrentProcess() 伪句柄 = -1
            int result;
            try {
                result = (int) ASSIGN_PROCESS_TO_JOB_OBJECT.invoke(hJob, -1L);
            } catch (Throwable e) {
                log.warn("attachSelf 调用异常: {}", e.toString());
                return;
            }
            if (result != 0) {
                log.info("Java 进程已加入 JobObject handle=0x{}", Long.toHexString(hJob));
            } else {
                int err = getLastError();
                log.warn("Java 自身加入 JobObject 失败 err={} (可能已在调试器 Job 中)", err);
            }
            selfAssigned = true;
        }
    }

    /**
     * 将子进程 (按 PID) 加入全局 JobObject — 供 NativeProcess 使用.
     */
    public static void attachPid(long pid) {
        long hJob = jobHandle;
        if (hJob == 0) return;

        long hProcess = openProcess(pid);
        if (hProcess == 0) {
            log.warn("OpenProcess 失败 pid={} err={}", pid, getLastError());
            return;
        }
        try {
            int result = (int) ASSIGN_PROCESS_TO_JOB_OBJECT.invoke(hJob, hProcess);
            if (result != 0) {
                log.info("子进程 pid={} 已加入 JobObject", pid);
            } else {
                int err = getLastError();
                if (err != 5) { // 忽略 ERROR_ACCESS_DENIED (可能已在 Job 中)
                    log.warn("AssignProcessToJobObject 失败 pid={} err={}", pid, err);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            closeHandle(hProcess);
        }
    }

    /**
     * 将子进程加入全局 JobObject — 双重保险.
     * 无论 self-assign 成功与否都调用，通过 IsProcessInJob 验证结果.
     */
    public static void attachProcess(Process process) {
        if (process == null) return;
        long hJob = jobHandle;
        if (hJob == 0) return;

        try {
            long pid = process.pid();
            long hProcess = openProcess(pid);
            if (hProcess == 0) {
                log.warn("OpenProcess 失败 pid={} err={}", pid, getLastError());
                return;
            }

            try {
                // 先检查是否已在 Job 中
                int inJobResult = isInJob(hProcess, hJob);

                if (inJobResult == 1) {
                    log.info("子进程 pid={} 已在 JobObject 中 (自动归属)", pid);
                    return;
                }

                // inJobResult == 0: 确认不在 → 强制附加
                // inJobResult == -1: 查询失败 (权限不足等) → 直接尝试附加
                int result = (int) ASSIGN_PROCESS_TO_JOB_OBJECT.invoke(hJob, hProcess);
                if (result != 0) {
                    log.info("子进程 pid={} 已加入 JobObject (附加成功)", pid);
                } else {
                    int err = getLastError();
                    if (err == 5) { // ERROR_ACCESS_DENIED
                        if (isInAnyJob(hProcess) == 1) {
                            log.info("子进程 pid={} 已在其他 Job 中", pid);
                        } else {
                            log.warn("AssignProcessToJobObject 被拒绝 pid={} err=5 (可能已在 Job 中)", pid);
                        }
                    } else {
                        log.warn("AssignProcessToJobObject 失败 pid={} err={}", pid, err);
                    }
                }
            } finally {
                closeHandle(hProcess);
            }
        } catch (Throwable e) {
            log.error("attachProcess 异常 pid={}", process.pid(), e);
        }
    }

    // ---- private helpers ----

    private static long openProcess(long pid) {
        try {
            return (long) OPEN_PROCESS.invoke(PROCESS_ACCESS, 0, (int) pid);
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * @return 1=进程在 OUR Job 中, 0=不在, -1=查询失败
     */
    private static int isInJob(long hProcess, long hJob) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment resultBuf = arena.allocate(4);
            int ok = (int) IS_PROCESS_IN_JOB.invoke(hProcess, hJob, resultBuf);
            if (ok != 0) {
                return resultBuf.get(ValueLayout.JAVA_INT, 0) != 0 ? 1 : 0;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * @return 1=进程在任意 Job 中, 0=不在, -1=查询失败
     */
    private static int isInAnyJob(long hProcess) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment resultBuf = arena.allocate(4);
            int ok = (int) IS_PROCESS_IN_JOB.invoke(hProcess, 0L, resultBuf);
            if (ok != 0) {
                return resultBuf.get(ValueLayout.JAVA_INT, 0) != 0 ? 1 : 0;
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static void closeHandle(long handle) {
        try {
            CLOSE_HANDLE.invoke(handle);
        } catch (Throwable ignored) {
        }
    }

    private static int getLastError() {
        try {
            return (int) GET_LAST_ERROR.invoke();
        } catch (Throwable e) {
            return -1;
        }
    }
}
