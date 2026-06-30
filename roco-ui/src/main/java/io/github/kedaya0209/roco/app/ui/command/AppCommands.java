package io.github.kedaya0209.roco.app.ui.command;

import net.jcip.annotations.ThreadSafe;

/**
 * 应用级命令 — 功能开关、模式切换、窗口控制。
 */
@ThreadSafe
public final class AppCommands {

    private AppCommands() {
    }

    /** 切换匹配开关 */
    @ThreadSafe
    public record ToggleMatchingCommand() implements UiCommand {
    }

    /** 切换物资采集面板显示 */
    @ThreadSafe
    public record ToggleMaterialCollectionCommand() implements UiCommand {
    }

    /** 切换幽灵模式 */
    @ThreadSafe
    public record ToggleGhostModeCommand() implements UiCommand {
    }

    /** 设置窗口透明度 */
    @ThreadSafe
    public record SetWindowOpacityCommand(double opacity) implements UiCommand {
    }

    /** 设置跟随模式 */
    @ThreadSafe
    public record SetFollowModeCommand(boolean enabled) implements UiCommand {
    }

    /** 切换导航模式 */
    @ThreadSafe
    public record ToggleNavModeCommand() implements UiCommand {
    }

    /** 设置图层覆盖（大陆/洞穴手动切换，-1=自动，{@code >=0}=显示该层所有洞穴） */
    @ThreadSafe
    public record SetLayerCommand(int layer) implements UiCommand {
    }

    /** 显示窗口选择面板（多游戏窗口切换） */
    @ThreadSafe
    public record SwitchWindowCommand() implements UiCommand {
    }
}
