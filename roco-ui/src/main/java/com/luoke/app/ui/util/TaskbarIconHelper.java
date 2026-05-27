package com.luoke.app.ui.util;

import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 通过 Win32 API 设置任务栏图标，解决 StageStyle.TRANSPARENT 在 Native Image 下图标不生效的问题。
 */
@Slf4j
@ThreadSafe
public class TaskbarIconHelper {

    private static final int WM_SETICON = 0x0080;
    private static final int ICON_SMALL = 0;
    private static final int ICON_BIG = 1;
    private static final int IMAGE_ICON = 1;
    private static final int LR_LOADFROMFILE = 0x0010;
    private static final int LR_DEFAULTSIZE = 0x0040;
    private static final int GCLP_HICON = -14;
    private static final int GCLP_HICONSM = -34;

    private TaskbarIconHelper() {
    }

    /**
     * 为 Stage 设置任务栏图标（通过 Win32 API）。
     *
     * @param stage   目标窗口
     * @param iconPath ico 文件绝对路径
     */
    public static void setIcon(Stage stage, String iconPath) {
        try {
            long hwnd = getHwnd(stage);
            if (hwnd == 0) {
                log.warn("无法获取窗口 HWND");
                return;
            }

            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());

            MethodHandle loadImageW = linker.downcallHandle(
                    user32.find("LoadImageW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            MethodHandle sendMessageW = linker.downcallHandle(
                    user32.find("SendMessageW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));

            MethodHandle setClassLongPtrW = linker.downcallHandle(
                    user32.find("SetClassLongPtrW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG));

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pathSeg = arena.allocateFrom(iconPath, StandardCharsets.UTF_16LE);
                long hIcon = (long) loadImageW.invoke(0, pathSeg, IMAGE_ICON, 0, 0,
                        LR_LOADFROMFILE | LR_DEFAULTSIZE);
                if (hIcon == 0) {
                    log.warn("LoadImageW 失败: {}", iconPath);
                    return;
                }

                sendMessageW.invoke(hwnd, WM_SETICON, ICON_SMALL, hIcon);
                sendMessageW.invoke(hwnd, WM_SETICON, ICON_BIG, hIcon);

                // 同时修改窗口类图标，确保任务栏使用正确的图标
                setClassLongPtrW.invoke(hwnd, GCLP_HICONSM, hIcon);
                setClassLongPtrW.invoke(hwnd, GCLP_HICON, hIcon);
            }
        } catch (Throwable e) {
            log.warn("设置任务栏图标失败", e);
        }
    }

    private static long getHwnd(Stage stage) {
        // 方法一：Glass Window API（反射调用，JVM/Native Image 均兼容）
        try {
            Class<?> windowClass = Class.forName("com.sun.glass.ui.Window");
            Method getWindows = windowClass.getMethod("getWindows");
            Object windows = getWindows.invoke(null);
            if (windows instanceof List<?> list && !list.isEmpty()) {
                Object first = list.get(0);
                Method getNativeWindow = first.getClass().getMethod("getNativeWindow");
                Object result = getNativeWindow.invoke(first);
                if (result instanceof Long hwndVal && hwndVal != 0) {
                    return hwndVal;
                }
            }
        } catch (Throwable e) {
            log.debug("Glass Window API 不可用", e);
        }

        // 方法二：FindWindowW 兜底（需 UTF-16LE 编码）
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
                    log.info("通过 FindWindowW 获取 HWND: {} (title: {})", hwnd, title);
                    return hwnd;
                }
                log.warn("FindWindowW 未找到窗口: {}", title);
            }
        } catch (Throwable e) {
            log.warn("FindWindowW 获取 HWND 失败", e);
        }
        return 0;
    }
}
