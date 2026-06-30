package io.github.kedaya0209.roco.app.platform;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * FFM 版 Windows 窗口查找工具
 * 用 java.lang.foreign 替换 JNA User32
 */
@ThreadSafe
@Slf4j
public class WindowFinder {

    private static final Linker LINKER = Linker.nativeLinker();

    // --- user32.dll 函数句柄 ---
    private static final MethodHandle ENUM_WINDOWS;
    private static final MethodHandle IS_WINDOW_VISIBLE;
    private static final MethodHandle GET_WINDOW_TEXT_W;

    // WNDENUMPROC: BOOL CALLBACK(HWND, LPARAM)
    // HWND=long, LPARAM=ADDRESS (MemorySegment)
    private static final FunctionDescriptor WNDENUMPROC_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,   // BOOL
            ValueLayout.JAVA_LONG,  // HWND
            ValueLayout.ADDRESS     // LPARAM
    );

    // EnumWindows 回调实现 MethodHandle
    private static final MethodHandle ENUM_PROC;
    private static final MethodHandle ENUM_PROC_FIND_ALL;

    // --- 新增: GetWindowRect / GetWindowThreadProcessId / IsIconic ---
    private static final MethodHandle GET_WINDOW_RECT;
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID;
    private static final MethodHandle IS_ICONIC;

    // 上下文结构 (findWindowByKeyword): keyword(64 B) + result_hwnd(8 B) = 72 B
    private static final long KW_OFFSET = 0;
    private static final long RESULT_OFFSET = 64;

    // 上下文结构 (findWindowsByKeyword): keyword(64 B) + padding(4 B) + resultHwnd[64](512 B) = 584 B
    private static final long ALL_KW_OFFSET = 0;
    private static final long ALL_COUNT_OFFSET = 64;    // JAVA_INT, 4 字节对齐
    // ALL_COUNT_OFFSET + 4 = 68, 但 JAVA_LONG 需 8 字节对齐, padding 到 72
    private static final long ALL_HWND_OFFSET = 72;     // JAVA_LONG, 8 字节对齐
    private static final int MAX_WINDOWS = 64;

    static {
        try {
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());

            ENUM_WINDOWS = LINKER.downcallHandle(
                    user32.find("EnumWindows").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,   // WNDENUMPROC
                            ValueLayout.ADDRESS)); // LPARAM

            IS_WINDOW_VISIBLE = LINKER.downcallHandle(
                    user32.find("IsWindowVisible").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            GET_WINDOW_TEXT_W = LINKER.downcallHandle(
                    user32.find("GetWindowTextW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            GET_WINDOW_RECT = LINKER.downcallHandle(
                    user32.find("GetWindowRect").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            GET_WINDOW_THREAD_PROCESS_ID = LINKER.downcallHandle(
                    user32.find("GetWindowThreadProcessId").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            IS_ICONIC = LINKER.downcallHandle(
                    user32.find("IsIconic").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            ENUM_PROC = MethodHandles.lookup().findStatic(
                    WindowFinder.class, "enumProcImpl",
                    MethodType.methodType(int.class, long.class, MemorySegment.class));

            ENUM_PROC_FIND_ALL = MethodHandles.lookup().findStatic(
                    WindowFinder.class, "enumProcFindAllImpl",
                    MethodType.methodType(int.class, long.class, MemorySegment.class));
        } catch (Exception e) {
            // FFM 查找可能抛出多种异常，保留通用捕获
            throw new RuntimeException("Failed to init user32 FFM handles", e);
        }
    }

    /**
     * EnumWindowsProc 实现 — lParam 是上下文 MemorySegment
     */
    private static int enumProcImpl(long hwnd, MemorySegment lParamSeg) {
        try {
            // 过滤不可见窗口
            int visible = (int) IS_WINDOW_VISIBLE.invoke(hwnd);
            if (visible == 0) return 1;

            // 重新解释 lParam 为已知大小的上下文段
            MemorySegment ctx = lParamSeg.reinterpret(64 + 8);

            // 读取 keyword (UTF-8 字节, 以 0 结尾)
            byte[] kwBytes = new byte[64];
            int kwLen = 0;
            for (int i = 0; i < 64; i++) {
                byte b = ctx.get(ValueLayout.JAVA_BYTE, KW_OFFSET + i);
                if (b == 0) {
                    kwLen = i;
                    break;
                }
                kwBytes[i] = b;
            }
            if (kwLen == 0) return 0;

            // 获取窗口标题 (Unicode)
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment textBuf = temp.allocate(1024);
                int charCount = (int) GET_WINDOW_TEXT_W.invoke(hwnd, textBuf, 512);
                if (charCount <= 0) return 1;

                // 读取 UTF-16LE 字节（2 字节/字符），以双字节 null 结尾
                byte[] utf16Bytes = new byte[charCount * 2];
                for (int i = 0; i < utf16Bytes.length; i++) {
                    utf16Bytes[i] = textBuf.get(ValueLayout.JAVA_BYTE, i);
                }

                String title = cleanString(new String(utf16Bytes, StandardCharsets.UTF_16LE));
                String keyword = new String(Arrays.copyOf(kwBytes, kwLen), StandardCharsets.UTF_8);

                if (title.equals(cleanString(keyword))) {
                    ctx.set(ValueLayout.JAVA_LONG, RESULT_OFFSET, hwnd);
                    return 0; // found
                }
            }

            return 1; // continue
        } catch (Throwable e) {
            // 原生回调中可能抛出多种异常，保留通用捕获
            return 0;
        }
    }

    /**
     * EnumWindowsProc 实现 — 收集所有匹配 HWND，不停止枚举。
     * 上下文格式: keyword(64 B) + resultCount(4 B) + resultHwnd[64](512 B)
     */
    private static int enumProcFindAllImpl(long hwnd, MemorySegment lParamSeg) {
        try {
            int visible = (int) IS_WINDOW_VISIBLE.invoke(hwnd);
            if (visible == 0) return 1;

            MemorySegment ctx = lParamSeg.reinterpret(ALL_HWND_OFFSET + MAX_WINDOWS * 8L);

            // 读取 keyword
            byte[] kwBytes = new byte[64];
            int kwLen = 0;
            for (int i = 0; i < 64; i++) {
                byte b = ctx.get(ValueLayout.JAVA_BYTE, ALL_KW_OFFSET + i);
                if (b == 0) {
                    kwLen = i;
                    break;
                }
                kwBytes[i] = b;
            }
            if (kwLen == 0) return 1;

            // 获取窗口标题
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment textBuf = temp.allocate(1024);
                int charCount = (int) GET_WINDOW_TEXT_W.invoke(hwnd, textBuf, 512);
                if (charCount <= 0) return 1;

                byte[] utf16Bytes = new byte[charCount * 2];
                for (int i = 0; i < utf16Bytes.length; i++) {
                    utf16Bytes[i] = textBuf.get(ValueLayout.JAVA_BYTE, i);
                }

                String title = cleanString(new String(utf16Bytes, StandardCharsets.UTF_16LE));
                String keyword = new String(Arrays.copyOf(kwBytes, kwLen), StandardCharsets.UTF_8);

                if (title.equals(cleanString(keyword))) {
                    int count = ctx.get(ValueLayout.JAVA_INT, ALL_COUNT_OFFSET);
                    if (count < MAX_WINDOWS) {
                        ctx.set(ValueLayout.JAVA_LONG, ALL_HWND_OFFSET + count * 8L, hwnd);
                        ctx.set(ValueLayout.JAVA_INT, ALL_COUNT_OFFSET, count + 1);
                    }
                }
            }

            return 1; // 始终继续枚举
        } catch (Throwable e) {
            return 1;
        }
    }

    /**
     * 根据关键字查找窗口句柄
     */
    public static long findWindowByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return 0;

        byte[] kwBytes = keyword.getBytes(StandardCharsets.UTF_8);
        if (kwBytes.length > 63) {
            kwBytes = Arrays.copyOf(kwBytes, 63);
        }

        try (Arena arena = Arena.ofConfined()) {
            // 上下文: 64B keyword + 8B result
            MemorySegment ctx = arena.allocate(64 + 8);

            // 写入 keyword (null-terminated)
            for (int i = 0; i < kwBytes.length; i++) {
                ctx.set(ValueLayout.JAVA_BYTE, KW_OFFSET + i, kwBytes[i]);
            }
            ctx.set(ValueLayout.JAVA_BYTE, KW_OFFSET + kwBytes.length, (byte) 0);

            // 初始化 result = 0
            ctx.set(ValueLayout.JAVA_LONG, RESULT_OFFSET, 0L);

            // 创建 upcall stub (arena 管理生命周期)
            MemorySegment upcall = LINKER.upcallStub(ENUM_PROC, WNDENUMPROC_DESC, arena);

            // 调用 EnumWindows — ctx 作为 MemorySegment 传递, linker 自动转地址
            ENUM_WINDOWS.invoke(upcall, ctx);

            return ctx.get(ValueLayout.JAVA_LONG, RESULT_OFFSET);
        } catch (Throwable e) {
            // FFM 调用可能抛出多种异常，保留通用捕获
            log.error("EnumWindows FFM 调用失败", e);
            return 0;
        }
    }

    /**
     * 根据关键字查找所有匹配的可见窗口句柄。
     *
     * @param keyword 窗口标题关键字
     * @return HWND 列表，按 Z-order 从上到下排序
     */
    public static List<Long> findWindowsByKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();

        byte[] kwBytes = keyword.getBytes(StandardCharsets.UTF_8);
        if (kwBytes.length > 63) {
            kwBytes = Arrays.copyOf(kwBytes, 63);
        }

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment ctx = arena.allocate(ALL_HWND_OFFSET + MAX_WINDOWS * 8L);

            // 写入 keyword (null-terminated)
            for (int i = 0; i < kwBytes.length; i++) {
                ctx.set(ValueLayout.JAVA_BYTE, ALL_KW_OFFSET + i, kwBytes[i]);
            }
            ctx.set(ValueLayout.JAVA_BYTE, ALL_KW_OFFSET + kwBytes.length, (byte) 0);

            // 初始化 count = 0
            ctx.set(ValueLayout.JAVA_INT, ALL_COUNT_OFFSET, 0);

            MemorySegment upcall = LINKER.upcallStub(ENUM_PROC_FIND_ALL, WNDENUMPROC_DESC, arena);
            ENUM_WINDOWS.invoke(upcall, ctx);

            int count = ctx.get(ValueLayout.JAVA_INT, ALL_COUNT_OFFSET);
            if (count <= 0) return List.of();

            List<Long> hwnds = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long h = ctx.get(ValueLayout.JAVA_LONG, ALL_HWND_OFFSET + i * 8L);
                hwnds.add(h);
            }
            return hwnds;
        } catch (Throwable e) {
            log.error("EnumWindows (findAll) FFM 调用失败", e);
            return List.of();
        }
    }

    /**
     * 根据 HWND 构建窗口描述符。
     *
     * @param hwnd 窗口句柄
     * @return WindowDescriptor，失败返回 null
     */
    public static WindowDescriptor buildWindowDescriptor(long hwnd) {
        if (hwnd <= 0) return null;
        try {
            String title = getWindowTitle(hwnd);
            if (title.isEmpty()) return null;

            int pid = getWindowPid(hwnd);
            int[] rect = getWindowRect(hwnd);
            int l = rect != null ? rect[0] : 0;
            int t = rect != null ? rect[1] : 0;
            int w = rect != null ? rect[2] - rect[0] : 0;
            int h = rect != null ? rect[3] - rect[1] : 0;
            boolean minimized = (int) IS_ICONIC.invoke(hwnd) != 0;

            return new WindowDescriptor(hwnd, title, pid, l, t, w, h, minimized);
        } catch (Throwable e) {
            log.warn("buildWindowDescriptor 失败, hwnd=0x{}", Long.toHexString(hwnd), e);
            return null;
        }
    }

    /**
     * 获取窗口标题。
     */
    public static String getWindowTitle(long hwnd) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment textBuf = temp.allocate(1024);
            int charCount = (int) GET_WINDOW_TEXT_W.invoke(hwnd, textBuf, 512);
            if (charCount <= 0) return "";

            byte[] utf16Bytes = new byte[charCount * 2];
            for (int i = 0; i < utf16Bytes.length; i++) {
                utf16Bytes[i] = textBuf.get(ValueLayout.JAVA_BYTE, i);
            }
            return new String(utf16Bytes, StandardCharsets.UTF_16LE);
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 获取窗口所属进程 PID。
     */
    private static int getWindowPid(long hwnd) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pidSeg = temp.allocate(ValueLayout.JAVA_INT.byteSize());
            GET_WINDOW_THREAD_PROCESS_ID.invoke(hwnd, pidSeg);
            return pidSeg.get(ValueLayout.JAVA_INT, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * 获取窗口矩形 [left, top, right, bottom]。
     */
    private static int[] getWindowRect(long hwnd) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment rect = temp.allocate(16); // RECT: 4 ints
            int ok = (int) GET_WINDOW_RECT.invoke(hwnd, rect);
            if (ok == 0) return null;
            return new int[]{
                    rect.get(ValueLayout.JAVA_INT, 0),
                    rect.get(ValueLayout.JAVA_INT, 4),
                    rect.get(ValueLayout.JAVA_INT, 8),
                    rect.get(ValueLayout.JAVA_INT, 12)
            };
        } catch (Throwable e) {
            return null;
        }
    }

    // === 字符串工具 ===

    private static String cleanString(String input) {
        if (input == null) return "";
        return input
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll("[\\p{Cf}\\p{Zp}\\p{Zl}]", "")
                .replace('\u00A0', ' ')
                .trim();
    }
}
