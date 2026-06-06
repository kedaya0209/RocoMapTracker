package io.github.kedaya0209.roco.app.ui.command;

import net.jcip.annotations.ThreadSafe;

/**
 * 视口命令 — 拖拽、缩放、重置、尺寸变更。
 */
@ThreadSafe
public final class ViewportCommands {

    private ViewportCommands() {
    }

    /** 拖拽视口 */
    @ThreadSafe
    public record DragViewportCommand(double dx, double dy) implements UiCommand {
    }

    /** 缩放视口（factor > 1 放大，factor < 1 缩小） */
    @ThreadSafe
    public record ZoomViewportCommand(double factor, double mx, double my) implements UiCommand {
    }

    /** 重置视口到 auto-fit */
    @ThreadSafe
    public record ResetViewportCommand() implements UiCommand {
    }

    /** 画布尺寸变更 */
    @ThreadSafe
    public record SetViewportSizeCommand(double width, double height) implements UiCommand {
    }
}
