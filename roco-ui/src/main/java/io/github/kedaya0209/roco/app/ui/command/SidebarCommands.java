package io.github.kedaya0209.roco.app.ui.command;

import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import net.jcip.annotations.ThreadSafe;

/**
 * 侧边栏命令 — 算法、资源、主题、版本切换。
 */
@ThreadSafe
public final class SidebarCommands {

    private SidebarCommands() {
    }

    /** 切换匹配算法 */
    @ThreadSafe
    public record SwitchAlgorithmCommand(String algorithm) implements UiCommand {
    }

    /** 切换资源模式（内置 / 外部） */
    @ThreadSafe
    public record SwitchResourceCommand(boolean isInternal) implements UiCommand {
    }

    /** 切换主题 */
    @ThreadSafe
    public record SwitchThemeCommand(String name) implements UiCommand {
    }

    /** 切换版本模式 */
    @ThreadSafe
    public record SwitchVersionCommand(VersionMode mode) implements UiCommand {
    }
}
