package io.github.kedaya0209.roco.app.ui.util;

import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

/**
 * Win32 WM_NCHITTEST 消息拦截 — 实现"标题栏响应、内容区穿透"。
 * <p>
 * 通过 Project Panama FFM API 子类化 JavaFX 窗口的 WndProc，
 * 在幽灵模式下将标题栏下方区域返回 HTTRANSPARENT，鼠标事件穿透到 3D 游戏；
 * 标题栏区域返回 HTCLIENT，按钮点击和窗口拖拽正常。
 */
@ThreadSafe
@Slf4j
public final class WindowHitTestHelper {

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_NCHITTEST = 0x0084;

    private static final int HTTRANSPARENT = -1;
    private static final int HTCLIENT = 1;

    private static final long COORD_MASK = 0xFFFFL;

    private static long prevWndProc;
    private static long currentHwnd;
    private static MemorySegment wndProcStub;
    private static Arena wndProcArena;
    private static volatile boolean enabled;

    /** 预分配的 RECT 结构 (left, top, right, bottom) */
    private static final MemorySegment rect = Arena.global().allocate(16);

    private WindowHitTestHelper() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 启用局部穿透：标题栏区域（Y ≤ titleBarHeight）返回 HTCLIENT，
     * 内容区域返回 HTTRANSPARENT，鼠标穿透到下层窗口。
     */
    public static void enablePartialPassthrough(Stage stage, int titleBarHeight) {
        if (enabled) return;
        try {
            long hwnd = getHwnd(stage);
            if (hwnd == 0) {
                log.warn("获取 HWND 失败，无法启用局部穿透");
                return;
            }
            currentHwnd = hwnd;

            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());

            MethodHandle setWindowLongPtr = linker.downcallHandle(
                    user32.find("SetWindowLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

            MethodHandle callWindowProc = linker.downcallHandle(
                    user32.find("CallWindowProcW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

            MethodHandle getWindowRect = linker.downcallHandle(
                    user32.find("GetWindowRect").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

            // 预绑定 API 句柄 → 高频回调中零查找
            MethodHandle target = MethodHandles.lookup().findStatic(
                    WindowHitTestHelper.class, "wndProcImpl",
                    MethodType.methodType(long.class,
                            MethodHandle.class, MethodHandle.class, int.class,
                            long.class, int.class, long.class, long.class));

            MethodHandle boundWndProc = MethodHandles.insertArguments(
                    target, 0, callWindowProc, getWindowRect, titleBarHeight);

            FunctionDescriptor wndProcDesc = FunctionDescriptor.of(
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG);

            wndProcArena = Arena.ofConfined();
            wndProcStub = linker.upcallStub(boundWndProc, wndProcDesc, wndProcArena);

            // 子类化：替换 WndProc
            prevWndProc = (long) setWindowLongPtr.invoke(hwnd, GWLP_WNDPROC, wndProcStub.address());
            enabled = true;
            log.info("WM_NCHITTEST 局部穿透已启用 | HWND={} | 标题栏高度={}px", hwnd, titleBarHeight);
        } catch (Throwable e) {
            log.warn("WM_NCHITTEST 拦截注入失败", e);
        }
    }

    /**
     * 禁用穿透：恢复原始 WndProc。
     */
    public static void disablePassthrough() {
        if (!enabled) return;
        try {
            long curHwnd = getCurrentHwnd();
            if (curHwnd != 0 && prevWndProc != 0) {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());

                MethodHandle setWindowLongPtr = linker.downcallHandle(
                        user32.find("SetWindowLongPtrW").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                                ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

                setWindowLongPtr.invoke(curHwnd, GWLP_WNDPROC, prevWndProc);
            }
            prevWndProc = 0;
            currentHwnd = 0;
            if (wndProcArena != null) {
                wndProcArena.close();
                wndProcArena = null;
                wndProcStub = null;
            }
            enabled = false;
            log.info("原始 WndProc 已恢复");
        } catch (Throwable e) {
            log.warn("WndProc 恢复失败", e);
        }
    }

    // ---- 回调实现 ----

    @SuppressWarnings("unused")
    private static long wndProcImpl(MethodHandle callWindowProc, MethodHandle getWindowRect,
                                    int titleBarHeight,
                                    long hwnd, int uMsg, long wParam, long lParam) throws Throwable {
        if (uMsg == WM_NCHITTEST) {
            int absX = (int) (lParam & COORD_MASK);
            int absY = (int) ((lParam >> 16) & COORD_MASK);

            // 调用 GetWindowRect 获取窗口屏幕坐标
            Object success = getWindowRect.invoke(hwnd, rect);
            if (success instanceof Boolean ok && ok) {
                int winTop = rect.get(ValueLayout.JAVA_INT, 4);
                int relY = absY - winTop;

                if (relY >= 0 && relY <= titleBarHeight) {
                    // 标题栏 → 正常响应
                    return HTCLIENT;
                }
                // 内容区 → 完全穿透
                return HTTRANSPARENT;
            }
        }

        // 非 NCHITTEST 消息 → 放行给 JavaFX
        return (long) callWindowProc.invoke(prevWndProc, hwnd, uMsg, wParam, lParam);
    }

    // ---- HWND 获取 ----

    /**
     * 从 JavaFX Glass Window 列表获取 HWND。
     * 复用 TaskbarIconHelper 已验证的方案。
     */
    private static long getHwnd(Stage stage) {
        String title = stage.getTitle();
        if (title == null || title.isEmpty()) {
            log.warn("窗口标题为空，无法查找 HWND");
            return 0;
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            MethodHandle findWindowW = linker.downcallHandle(
                    user32.find("FindWindowW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment titleSeg = arena.allocateFrom(title, StandardCharsets.UTF_16LE);
                long hwnd = (long) findWindowW.invoke(MemorySegment.NULL, titleSeg);
                if (hwnd != 0) {
                    log.info("FindWindowW 获取 HWND: {} (title: {})", hwnd, title);
                    return hwnd;
                }
                log.warn("FindWindowW 未找到窗口: {}", title);
            }
        } catch (Throwable e) {
            log.warn("FindWindowW 获取 HWND 失败", e);
        }
        return 0;
    }

    public static long getCurrentHwnd() {
        return currentHwnd;
    }

    // ---- 系统光标全局显隐 ----

    private static MethodHandle showCursor;

    /**
     * 隐藏系统光标（全局）。循环调用 ShowCursor(FALSE) 直到内部计数 < 0（光标实际隐藏）。
     * 解决从标题栏进入时 Windows 自动递增计数导致单次调用无法隐藏的问题。
     */
    public static void hideSystemCursor() {
        try {
            if (showCursor == null) {
                showCursor = Linker.nativeLinker().downcallHandle(
                        SymbolLookup.libraryLookup("user32", Arena.global()).find("ShowCursor").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            }
            while ((int) showCursor.invoke(0) >= 0) {
                // 持续递减直到光标隐藏
            }
        } catch (Throwable e) {
            log.warn("隐藏系统光标失败", e);
        }
    }

    /**
     * 恢复系统光标显示。循环调用 ShowCursor(TRUE) 直到内部计数 >= 0（光标实际显示）。
     */
    public static void showSystemCursor() {
        try {
            if (showCursor == null) {
                showCursor = Linker.nativeLinker().downcallHandle(
                        SymbolLookup.libraryLookup("user32", Arena.global()).find("ShowCursor").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            }
            while ((int) showCursor.invoke(1) < 0) {
                // 持续递增直到光标显示
            }
        } catch (Throwable e) {
            log.warn("恢复系统光标失败", e);
        }
    }

    // ---- 光标屏幕坐标查询 ----

    private static MethodHandle getCursorPos;

    /**
     * 获取光标当前屏幕坐标，不依赖 JavaFX 事件系统。
     * @return int[2] {screenX, screenY}，失败返回 null
     */
    public static int[] getCursorScreenPos() {
        try {
            if (getCursorPos == null) {
                getCursorPos = Linker.nativeLinker().downcallHandle(
                        SymbolLookup.libraryLookup("user32", Arena.global()).find("GetCursorPos").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment point = arena.allocate(8); // POINT: LONG x, LONG y
                boolean ok = (boolean) getCursorPos.invoke(point);
                if (ok) {
                    int x = point.get(ValueLayout.JAVA_INT, 0);
                    int y = point.get(ValueLayout.JAVA_INT, 4);
                    return new int[]{x, y};
                }
            }
        } catch (Throwable e) {
            log.warn("GetCursorPos 失败", e);
        }
        return null;
    }

    // ---- 纯 Win32 光标位置查询 ----

    private static MethodHandle getWindowRectStatic;

    /**
     * 检查光标是否在已缓存 HWND 窗口的内容区（标题栏下方），
     * 纯 Win32 实现，不依赖 JavaFX 坐标系统。
     * @param titleBarHeight 标题栏高度像素
     * @return true=光标在内容区, false=光标在标题栏/窗口外/无法判断
     */
    public static boolean isCursorOverContentArea(int titleBarHeight) {
        long hwnd = currentHwnd;
        if (hwnd == 0) return false;
        try {
            if (getCursorPos == null) {
                getCursorPos = Linker.nativeLinker().downcallHandle(
                        SymbolLookup.libraryLookup("user32", Arena.global()).find("GetCursorPos").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
            }
            if (getWindowRectStatic == null) {
                getWindowRectStatic = Linker.nativeLinker().downcallHandle(
                        SymbolLookup.libraryLookup("user32", Arena.global()).find("GetWindowRect").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN,
                                ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment point = arena.allocate(8);
                if (!(boolean) getCursorPos.invoke(point)) return false;
                int curX = point.get(ValueLayout.JAVA_INT, 0);
                int curY = point.get(ValueLayout.JAVA_INT, 4);

                MemorySegment rect = arena.allocate(16);
                if (!(boolean) getWindowRectStatic.invoke(hwnd, rect)) return false;
                int winLeft = rect.get(ValueLayout.JAVA_INT, 0);
                int winTop = rect.get(ValueLayout.JAVA_INT, 4);
                int winRight = rect.get(ValueLayout.JAVA_INT, 8);
                int winBottom = rect.get(ValueLayout.JAVA_INT, 12);

                return curX >= winLeft && curX <= winRight
                    && curY >= winTop + titleBarHeight && curY <= winBottom;
            }
        } catch (Throwable e) {
            log.warn("isCursorOverContentArea 失败", e);
            return false;
        }
    }
}
