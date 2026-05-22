package com.luoke.app.ui.util;

import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

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

            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pathSeg = arena.allocateFrom(iconPath);
                long hIcon = (long) loadImageW.invoke(0, pathSeg, IMAGE_ICON, 0, 0,
                        LR_LOADFROMFILE | LR_DEFAULTSIZE);
                if (hIcon == 0) {
                    log.warn("LoadImageW 失败: {}", iconPath);
                    return;
                }

                sendMessageW.invoke(hwnd, WM_SETICON, ICON_SMALL, hIcon);
                sendMessageW.invoke(hwnd, WM_SETICON, ICON_BIG, hIcon);
                log.info("任务栏图标已设置: {}", iconPath);
            }
        } catch (Throwable e) {
            log.warn("设置任务栏图标失败", e);
        }
    }

    private static long getHwnd(Stage stage) {
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
            MethodHandle findWindowW = linker.downcallHandle(
                    user32.find("FindWindowW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment title = arena.allocateFrom(stage.getTitle());
                return (long) findWindowW.invoke(MemorySegment.NULL, title);
            }
        } catch (Throwable e) {
            log.debug("FindWindowW 获取 HWND 失败", e);
        }
        return 0;
    }
}
