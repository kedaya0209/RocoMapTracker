package com.luoke.app.capture;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    private static final MethodHandle GET_WINDOW_TEXT_A;

    // WNDENUMPROC: BOOL CALLBACK(HWND, LPARAM)
    // HWND=long, LPARAM=ADDRESS (MemorySegment)
    private static final FunctionDescriptor WNDENUMPROC_DESC = FunctionDescriptor.of(
            ValueLayout.JAVA_INT,   // BOOL
            ValueLayout.JAVA_LONG,  // HWND
            ValueLayout.ADDRESS     // LPARAM
    );

    // EnumWindows 回调实现 MethodHandle
    private static final MethodHandle ENUM_PROC;

    // 上下文结构: keyword(64 B) + result_hwnd(8 B) = 72 B
    private static final long KW_OFFSET = 0;
    private static final long RESULT_OFFSET = 64;

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

            GET_WINDOW_TEXT_A = LINKER.downcallHandle(
                    user32.find("GetWindowTextA").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            ENUM_PROC = MethodHandles.lookup().findStatic(
                    WindowFinder.class, "enumProcImpl",
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

            // 获取窗口标题
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment textBuf = temp.allocate(512);
                int len = (int) GET_WINDOW_TEXT_A.invoke(hwnd, textBuf, 512);
                if (len <= 0) return 1;

                byte[] titleBytes = new byte[len];
                for (int i = 0; i < len; i++) {
                    titleBytes[i] = textBuf.get(ValueLayout.JAVA_BYTE, i);
                }

                String title = cleanNativeString(titleBytes);
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
            log.error("EnumWindows FFM call failed", e);
            return 0;
        }
    }

    // === 字符串工具 ===

    private static String cleanNativeString(byte[] bytes) {
        try {
            return cleanString(new String(bytes, "GBK"));
        } catch (IOException e) {
            return cleanString(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static String cleanString(String input) {
        if (input == null) return "";
        return input
                .replaceAll("\\p{Cntrl}", "")
                .replaceAll("[\\p{Cf}\\p{Zp}\\p{Zl}]", "")
                .replace('\u00A0', ' ')
                .trim();
    }
}
