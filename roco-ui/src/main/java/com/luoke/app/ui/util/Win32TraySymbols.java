package com.luoke.app.ui.util;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Win32 系统托盘常量 + FFM 符号绑定。
 * <p>
 * 从 TrayManager 提取的静态字段，供消息泵、HICON 渲染等模块共享。
 * 首次调用 {@link #ensureSymbols()} 时惰性加载 user32/shell32/gdi32 符号。
 * </p>
 */
@ThreadSafe
@Slf4j
final class Win32TraySymbols {

    private Win32TraySymbols() {
    }

    // ============================================================
    // Win32 常量
    // ============================================================

    static final long HWND_MESSAGE = -3L;

    static final int WM_DESTROY = 0x0002;
    static final int WM_QUIT = 0x0012;
    static final int WM_LBUTTONDBLCLK = 0x0203;
    static final int WM_RBUTTONUP = 0x0205;

    static final int NIM_ADD = 0;
    static final int NIM_MODIFY = 1;
    static final int NIM_DELETE = 2;
    static final int NIM_SETVERSION = 4;
    static final int NIF_MESSAGE = 0x0001;
    static final int NIF_ICON = 0x0002;
    static final int NIF_TIP = 0x0004;
    static final int NIF_SHOWTIP = 0x0080;
    static final int NOTIFYICON_VERSION_4 = 4;

    // NOTIFYICONDATAW x64 字段偏移
    static final long NID_CBSIZE = 0L;
    static final long NID_HWND = 8L;
    static final long NID_UID = 16L;
    static final long NID_UFLAGS = 20L;
    static final long NID_UCALLBACKMSG = 24L;
    static final long NID_HICON = 32L;
    static final long NID_SZTIP = 40L;
    static final long NID_UVERSION = 816L;
    static final long NID_SIZE = 1024L;

    // NOTIFYICONDATAW x64 cbSize 值（含 Vista+ hBalloonIcon 字段）
    static final int NID_CBSIZE_VAL = 976;

    // ICONINFO x64 结构
    static final int ICONINFO_SIZE = 32;
    static final long ICONINFO_HBMMASK = 16L;
    static final long ICONINFO_HBMCOLOR = 24L;

    // WNDCLASSEXW x64 大小
    static final int WNDCLASSEX_SIZE = 80;

    // MSG x64 大小（8 字节对齐）
    static final int MSG_SIZE = 48;

    // ============================================================
    // FFM 符号（首次使用时惰性初始化）
    // ============================================================

    static final Linker linker = Linker.nativeLinker();

    static volatile boolean symbolsLoaded;
    static MethodHandle ShellNotifyIconW;
    static MethodHandle CreateWindowExW;
    static MethodHandle DefWindowProcW;
    static MethodHandle RegisterClassExW;
    static MethodHandle DestroyWindow;
    static MethodHandle GetMessageW;
    static MethodHandle TranslateMessage;
    static MethodHandle DispatchMessageW;
    static MethodHandle PostThreadMessageW;
    static MethodHandle GetWindowThreadProcessId;
    static MethodHandle GetCursorPos;
    static MethodHandle CreateDIBSection;
    static MethodHandle CreateIconIndirect;
    static MethodHandle DestroyIcon;
    static MethodHandle DeleteObject;
    static MethodHandle GetDC;
    static MethodHandle ReleaseDC;
    static MethodHandle CreateBitmap;

    static void ensureSymbols() {
        if (symbolsLoaded) return;
        synchronized (Win32TraySymbols.class) {
            if (symbolsLoaded) return;
            try {
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
                SymbolLookup shell32 = SymbolLookup.libraryLookup("shell32", Arena.global());
                SymbolLookup gdi32 = SymbolLookup.libraryLookup("gdi32", Arena.global());

                ShellNotifyIconW = linker.downcallHandle(
                        shell32.findOrThrow("Shell_NotifyIconW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                CreateWindowExW = linker.downcallHandle(
                        user32.findOrThrow("CreateWindowExW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                DefWindowProcW = linker.downcallHandle(
                        user32.findOrThrow("DefWindowProcW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                RegisterClassExW = linker.downcallHandle(
                        user32.findOrThrow("RegisterClassExW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_SHORT, ValueLayout.ADDRESS));

                DestroyWindow = linker.downcallHandle(
                        user32.findOrThrow("DestroyWindow"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                GetMessageW = linker.downcallHandle(
                        user32.findOrThrow("GetMessageW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

                TranslateMessage = linker.downcallHandle(
                        user32.findOrThrow("TranslateMessage"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                DispatchMessageW = linker.downcallHandle(
                        user32.findOrThrow("DispatchMessageW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                PostThreadMessageW = linker.downcallHandle(
                        user32.findOrThrow("PostThreadMessageW"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                GetWindowThreadProcessId = linker.downcallHandle(
                        user32.findOrThrow("GetWindowThreadProcessId"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                GetCursorPos = linker.downcallHandle(
                        user32.findOrThrow("GetCursorPos"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                CreateDIBSection = linker.downcallHandle(
                        gdi32.findOrThrow("CreateDIBSection"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));

                CreateIconIndirect = linker.downcallHandle(
                        user32.findOrThrow("CreateIconIndirect"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

                DestroyIcon = linker.downcallHandle(
                        user32.findOrThrow("DestroyIcon"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                DeleteObject = linker.downcallHandle(
                        gdi32.findOrThrow("DeleteObject"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                GetDC = linker.downcallHandle(
                        user32.findOrThrow("GetDC"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                ReleaseDC = linker.downcallHandle(
                        user32.findOrThrow("ReleaseDC"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

                CreateBitmap = linker.downcallHandle(
                        gdi32.findOrThrow("CreateBitmap"),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

                symbolsLoaded = true;
                log.info("FFM Win32 符号加载完成");
            } catch (Throwable e) {
                log.error("FFM Win32 符号加载失败，系统托盘不可用", e);
            }
        }
    }
}
