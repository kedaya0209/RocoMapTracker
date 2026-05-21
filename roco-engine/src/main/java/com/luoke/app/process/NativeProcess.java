package com.luoke.app.process;

import net.jcip.annotations.NotThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 通过 FFM 调用 CreateProcessW + PROC_THREAD_ATTRIBUTE_JOB_LIST 创建子进程，
 * 使子进程在任务管理器"进程"页签下显示为父进程的子项。
 *
 * <p>替代 {@link ProcessBuilder#start()}，同时完成 JobObject 归属。
 */
@NotThreadSafe
@Slf4j
public class NativeProcess {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

    // ---- kernel32 函数句柄 ----
    private static final MethodHandle CREATE_PROCESS_W;
    private static final MethodHandle CREATE_PIPE;
    private static final MethodHandle WAIT_FOR_SINGLE_OBJECT;
    private static final MethodHandle TERMINATE_PROCESS;
    private static final MethodHandle GET_EXIT_CODE_PROCESS;
    private static final MethodHandle INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST;
    private static final MethodHandle UPDATE_PROC_THREAD_ATTRIBUTE;
    private static final MethodHandle DELETE_PROC_THREAD_ATTRIBUTE_LIST;
    private static final MethodHandle GET_LAST_ERROR;
    private static final MethodHandle READ_FILE;
    private static final MethodHandle OPEN_PROCESS;
    private static final MethodHandle SET_PRIORITY_CLASS;

    // ---- 常量 ----
    /** PROC_THREAD_ATTRIBUTE_JOB_LIST: 将子进程在创建时归入 JobObject，使任务管理器"进程"页签下归组 */
    /**
     * PROC_THREAD_ATTRIBUTE_JOB_LIST: ProcThreadAttributeValue(13, FALSE, TRUE, FALSE) = 0x0002000D
     */
    private static final int PROC_THREAD_ATTRIBUTE_JOB_LIST = 0x0002000D;
    private static final int EXTENDED_STARTUPINFO_PRESENT = 0x00080000;
    private static final int CREATE_NO_WINDOW = 0x08000000;
    private static final int STARTF_USESTDHANDLES = 0x00000100;
    private static final int STILL_ACTIVE = 259;
    private static final int HIGH_PRIORITY_CLASS = 0x00000080;

    // STARTUPINFOEXW layout (x64, 自然对齐)
    private static final int STARTUPINFOEX_SIZE = 112;
    private static final long SUI_CB = 0;
    private static final long SUI_FLAGS = 60;
    private static final long SUI_HSTDIN = 80;
    private static final long SUI_HSTDOUT = 88;
    private static final long SUI_HSTDERR = 96;
    private static final long SUI_ATTR_LIST = 104;

    // PROCESS_INFORMATION: hProcess(8) + hThread(8) + dwPid(4) + dwTid(4)
    private static final int PROC_INFO_SIZE = 24;

    // captureCallState("GetLastError") 布局: struct { int GetLastError; }
    private static final StructLayout CAPTURE_STATE_LAYOUT =
            Linker.Option.captureStateLayout();

    static {
        try {
            CREATE_PROCESS_W = LINKER.downcallHandle(
                    KERNEL32.find("CreateProcessW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, // app, cmd
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, // procSec, threadSec
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, // inherit, flags
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, // env, dir
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS), // startup, procInfo
                    Linker.Option.captureCallState("GetLastError"));

            CREATE_PIPE = LINKER.downcallHandle(
                    KERNEL32.find("CreatePipe").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            WAIT_FOR_SINGLE_OBJECT = LINKER.downcallHandle(
                    KERNEL32.find("WaitForSingleObject").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

            TERMINATE_PROCESS = LINKER.downcallHandle(
                    KERNEL32.find("TerminateProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

            GET_EXIT_CODE_PROCESS = LINKER.downcallHandle(
                    KERNEL32.find("GetExitCodeProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST = LINKER.downcallHandle(
                    KERNEL32.find("InitializeProcThreadAttributeList").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

            UPDATE_PROC_THREAD_ATTRIBUTE = LINKER.downcallHandle(
                    KERNEL32.find("UpdateProcThreadAttribute").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));

            DELETE_PROC_THREAD_ATTRIBUTE_LIST = LINKER.downcallHandle(
                    KERNEL32.find("DeleteProcThreadAttributeList").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            GET_LAST_ERROR = LINKER.downcallHandle(
                    KERNEL32.find("GetLastError").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));

            OPEN_PROCESS = LINKER.downcallHandle(
                    KERNEL32.find("OpenProcess").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            SET_PRIORITY_CLASS = LINKER.downcallHandle(
                    KERNEL32.find("SetPriorityClass").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

            READ_FILE = LINKER.downcallHandle(
                    KERNEL32.find("ReadFile").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS));
        } catch (Exception e) {
            throw new RuntimeException("Failed to init kernel32 FFM handles for NativeProcess", e);
        }
    }

    // ---- 实例字段 ----
    private final long hProcess;
    private final long hThread;
    private final int pid;
    private final long hStdoutRead;
    private volatile boolean destroyed;

    private NativeProcess(long hProcess, long hThread, int pid, long hStdoutRead) {
        this.hProcess = hProcess;
        this.hThread = hThread;
        this.pid = pid;
        this.hStdoutRead = hStdoutRead;
    }

    /**
     * 使用 FFM CreateProcessW 创建子进程，事后通过 AssignProcessToJobObject 归入 JobObject.
     *
     * @param commandLine    完整命令行 (e.g. {@code "D:\path\sift_match.exe" 12670})
     * @param hJob           JobObject 句柄 (0 = 不加入 Job)
     * @param redirectStdout 是否重定向 stdout
     * @return NativeProcess, 失败返回 null
     */
    public static NativeProcess create(String commandLine, long hJob, boolean redirectStdout) {
        try (Arena arena = Arena.ofConfined()) {
            // 1. 创建 stdout pipe (同时重定向 stderr 到同一 pipe)
            long hRead = 0, hWrite = 0;
            if (redirectStdout) {
                MemorySegment pRead = arena.allocate(8);
                MemorySegment pWrite = arena.allocate(8);
                int pipeOk = (int) CREATE_PIPE.invoke(pRead, pWrite,
                        MemorySegment.NULL, 0);
                if (pipeOk == 0) {
                    log.error("CreatePipe 失败 err={}", lastError());
                    return null;
                }
                hRead = pRead.get(ValueLayout.JAVA_LONG, 0);
                hWrite = pWrite.get(ValueLayout.JAVA_LONG, 0);
            }

            // 2. 构建 STARTUPINFOEXW
            MemorySegment suiEx = arena.allocate(STARTUPINFOEX_SIZE);

            if (redirectStdout) {
                suiEx.set(ValueLayout.JAVA_INT, SUI_FLAGS, STARTF_USESTDHANDLES);
                suiEx.set(ValueLayout.JAVA_LONG, SUI_HSTDOUT, hWrite);
                suiEx.set(ValueLayout.JAVA_LONG, SUI_HSTDERR, hWrite);
                suiEx.set(ValueLayout.JAVA_LONG, SUI_HSTDIN, 0L);
            }

            // 3. 初始化 PROC_THREAD_ATTRIBUTE_LIST (JOB_LIST — 任务管理器"进程"页签归组)
            //    hJob 是干净的 JobObject (未预先 AssignProcessToJobObject Java 自身)，
            //    避免 CreateProcessW 报 err=87。
            MemorySegment attrList = null;
            if (hJob != 0) {
                MemorySegment sizeBuf = arena.allocate(8);
                INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST.invoke(
                        MemorySegment.NULL, 1, 0, sizeBuf);
                long attrSize = sizeBuf.get(ValueLayout.JAVA_LONG, 0);

                if (attrSize > 0) {
                    MemorySegment sizeBuf2 = arena.allocate(8);
                    sizeBuf2.set(ValueLayout.JAVA_LONG, 0, attrSize);
                    attrList = arena.allocate(attrSize);
                    int init2 = (int) INITIALIZE_PROC_THREAD_ATTRIBUTE_LIST.invoke(
                            attrList, 1, 0, sizeBuf2);
                    if (init2 != 0) {
                        MemorySegment jobPtr = arena.allocate(8);
                        jobPtr.set(ValueLayout.JAVA_LONG, 0, hJob);
                        int updOk = (int) UPDATE_PROC_THREAD_ATTRIBUTE.invoke(
                                attrList, 0, (long) PROC_THREAD_ATTRIBUTE_JOB_LIST,
                                jobPtr, 8L,
                                MemorySegment.NULL, MemorySegment.NULL);
                        if (updOk == 0) {
                            log.warn("UpdateProcThreadAttribute(JOB_LIST) 失败 err={}", lastError());
                            DELETE_PROC_THREAD_ATTRIBUTE_LIST.invoke(attrList);
                            attrList = null;
                        }
                    } else {
                        log.warn("InitializeProcThreadAttributeList 失败 err={}", lastError());
                        attrList = null;
                    }
                }
            }

            if (attrList != null) {
                suiEx.set(ValueLayout.JAVA_INT, SUI_CB, STARTUPINFOEX_SIZE); // cb=112 for EXTENDED
                suiEx.set(ValueLayout.ADDRESS, SUI_ATTR_LIST, attrList);
            } else {
                suiEx.set(ValueLayout.JAVA_INT, SUI_CB, 104); // cb=104 for plain STARTUPINFO
            }

            // 4. PROCESS_INFORMATION 输出
            MemorySegment procInfo = arena.allocate(PROC_INFO_SIZE);

            // 5. 编码命令行 (UTF-16LE, null-terminated)
            byte[] cmdBytes = commandLine.getBytes(StandardCharsets.UTF_16LE);
            MemorySegment cmdSeg = arena.allocate(cmdBytes.length + 2);
            MemorySegment.copy(MemorySegment.ofArray(cmdBytes), 0, cmdSeg, 0, cmdBytes.length);

            // 6. 调用 CreateProcessW
            int createFlags = CREATE_NO_WINDOW;
            if (attrList != null) {
                createFlags |= EXTENDED_STARTUPINFO_PRESENT;
            }

            log.info("CreateProcessW: cmd={} flags=0x{}", commandLine, Integer.toHexString(createFlags));

            MemorySegment capturedState = arena.allocate(CAPTURE_STATE_LAYOUT);
            int ok = (int) CREATE_PROCESS_W.invoke(
                    capturedState,          // captureCallState 段 (由 Linker.Option 前置)
                    MemorySegment.NULL,     // lpApplicationName
                    cmdSeg,                 // lpCommandLine
                    MemorySegment.NULL,     // lpProcessAttributes
                    MemorySegment.NULL,     // lpThreadAttributes
                    redirectStdout ? 1 : 0, // bInheritHandles
                    createFlags,            // dwCreationFlags
                    MemorySegment.NULL,     // lpEnvironment
                    MemorySegment.NULL,     // lpCurrentDirectory
                    suiEx,                  // lpStartupInfo
                    procInfo                // lpProcessInformation
            );

            // 从 captureCallState 读取 CreateProcessW 调用后的真实 GetLastError
            int savedError = capturedState.get(ValueLayout.JAVA_INT, 0);

            if (ok == 0) {
                long hProcFail = procInfo.get(ValueLayout.JAVA_LONG, 0);
                long hThrdFail = procInfo.get(ValueLayout.JAVA_LONG, 8);
                int pidFail = procInfo.get(ValueLayout.JAVA_INT, 16);
                log.error("CreateProcessW 失败 err={} (0x{}) cmd={} procInfo(h={} t={} pid={})",
                        savedError, Integer.toHexString(savedError), commandLine,
                        Long.toHexString(hProcFail), Long.toHexString(hThrdFail), pidFail);
                if (attrList != null) DELETE_PROC_THREAD_ATTRIBUTE_LIST.invoke(attrList);
                if (hWrite != 0) closeHandle(hWrite);
                if (hRead != 0) closeHandle(hRead);
                return null;
            }

            // 7. 清理
            if (attrList != null) DELETE_PROC_THREAD_ATTRIBUTE_LIST.invoke(attrList);
            if (hWrite != 0) closeHandle(hWrite);

            long hProc = procInfo.get(ValueLayout.JAVA_LONG, 0);
            long hThrd = procInfo.get(ValueLayout.JAVA_LONG, 8);
            int pid = procInfo.get(ValueLayout.JAVA_INT, 16);

            // 8. 子进程已通过 JOB_LIST 在创建时归入 JobObject。
            //    现在将 Java 自身也加入同一 JobObject (之前为保持 JobObject 干净未加入)，
            //    使父子进程在同一 JobObject 中 — 任务管理器"进程"页签归组 + KILL_ON_JOB_CLOSE 生效。
            if (hJob != 0) {
                JobObjectManager.attachPid(pid);   // 兜底: 确保子进程一定在 Job 中
                JobObjectManager.attachSelf();     // 首次调用将 Java 加入 JobObject (幂等)
            }

            // 9. 提升子进程优先级，防止浏览器等应用抢占 CPU 时间片导致匹配变慢
            try {
                int priOk = (int) SET_PRIORITY_CLASS.invoke(hProc, HIGH_PRIORITY_CLASS);
                if (priOk == 0) {
                    log.warn("SetPriorityClass(HIGH) 失败 pid={} err={}", pid, lastError());
                } else {
                    log.info("子进程 pid={} 优先级已提升至 HIGH_PRIORITY_CLASS", pid);
                }
            } catch (Throwable e) {
                // SetPriorityClass FFM 调用，保留通用捕获
                log.warn("SetPriorityClass 调用异常 pid={}: {}", pid, e.toString());
            }

            log.info("NativeProcess created: pid={}", pid);
            return new NativeProcess(hProc, hThrd, pid, hRead);
        } catch (Throwable e) {
            log.error("NativeProcess.create 异常: {}", e.toString());
            return null;
        }
    }

    private static void closeHandle(long handle) {
        try {
            LINKER.downcallHandle(
                            KERNEL32.find("CloseHandle").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG))
                    .invoke(handle);
        } catch (Throwable ignored) {
        }
    }

    private static int lastError() {
        try {
            return (int) GET_LAST_ERROR.invoke();
        } catch (Throwable e) {
            return -1;
        }
    }

    public InputStream getInputStream() {
        if (hStdoutRead == 0) return InputStream.nullInputStream();
        return new BufferedInputStream(new PipeInputStream(hStdoutRead));
    }

    public int pid() {
        return pid;
    }

    public boolean isAlive() {
        if (destroyed) return false;
        return getExitCode() == STILL_ACTIVE;
    }

    public int exitCode() {
        int code = getExitCode();
        return code == STILL_ACTIVE ? -1 : code;
    }

    private int getExitCode() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment codeBuf = arena.allocate(4);
            int ok = (int) GET_EXIT_CODE_PROCESS.invoke(hProcess, codeBuf);
            if (ok == 0) return 0;
            return codeBuf.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    public void destroy() {
        terminate(1);
    }

    public void destroyForcibly() {
        terminate(1);
    }

    // ---- private helpers ----

    private void terminate(int exitCode) {
        if (destroyed) return;
        destroyed = true;
        try {
            TERMINATE_PROCESS.invoke(hProcess, exitCode);
        } catch (Throwable ignored) {
        }
        closeHandle(hProcess);
        closeHandle(hThread);
        if (hStdoutRead != 0) closeHandle(hStdoutRead);
    }

    public boolean waitFor(long timeout, TimeUnit unit) {
        try {
            int ms = (int) Math.min(unit.toMillis(timeout), Integer.MAX_VALUE);
            return (int) WAIT_FOR_SINGLE_OBJECT.invoke(hProcess, ms) == 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 将 Windows pipe 读句柄包装为 InputStream.
     */
    private static class PipeInputStream extends InputStream {
        private final long handle;
        private volatile boolean closed;

        PipeInputStream(long handle) {
            this.handle = handle;
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n == -1 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (closed) return -1;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment buf = arena.allocate(len);
                MemorySegment nRead = arena.allocate(4);
                int ok = (int) READ_FILE.invoke(handle, buf, len, nRead, MemorySegment.NULL);
                if (ok == 0) {
                    closed = true;
                    return -1;
                }
                int count = nRead.get(ValueLayout.JAVA_INT, 0);
                if (count == 0) {
                    closed = true;
                    return -1;
                }
                // 直接从 segment 读取到目标数组
                for (int i = 0; i < count; i++) {
                    b[off + i] = buf.get(ValueLayout.JAVA_BYTE, i);
                }
                return count;
            } catch (Throwable e) {
                closed = true;
                throw new IOException("ReadFile failed", e);
            }
        }

        @Override
        public void close() {
            closed = true;
            closeHandle(handle);
        }
    }
}
