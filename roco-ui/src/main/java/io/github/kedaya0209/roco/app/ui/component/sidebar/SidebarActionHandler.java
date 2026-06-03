package io.github.kedaya0209.roco.app.ui.component.sidebar;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleNavModeCommand;
import io.github.kedaya0209.roco.app.ui.command.CommandBus;
import io.github.kedaya0209.roco.app.ui.command.SidebarCommands.SwitchAlgorithmCommand;
import io.github.kedaya0209.roco.app.ui.command.SidebarCommands.SwitchResourceCommand;
import io.github.kedaya0209.roco.app.ui.command.SidebarCommands.SwitchThemeCommand;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import io.github.kedaya0209.roco.app.ui.util.RestartUtils;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

@NotThreadSafe
@Slf4j
public class SidebarActionHandler {

    private volatile boolean isAlgorithmLoading = false;

    public void switchAlgorithm(String algo,
                                Runnable onUpdateHeader,
                                Runnable onCloseSidebar) {
        if (isAlgorithmLoading) return;
        isAlgorithmLoading = true;
        onUpdateHeader.run();

        Thread.ofPlatform().daemon(true).name("sidebar-switch-algo").start(() -> {
            try {
                CommandBus.dispatch(new SwitchAlgorithmCommand(algo));
            } catch (Exception e) {
                log.error("切换算法失败", e);
            } finally {
                Platform.runLater(() -> isAlgorithmLoading = false);
            }
        });

        onCloseSidebar.run();
    }

    public void switchResource(String resource, StackPane rootPane) {
        boolean isInternal = resource.equals("内置资源");
        if (isInternal == DownloadConfig.INTERNAL_RESOURCE) return;

        ConfirmDialog.showConfirmDialog(
                rootPane,
                "模式切换",
                "切换资源模式需要重启程序生效，是否继续？",
                "立即重启",
                () -> {
                    CommandBus.dispatch(new SwitchResourceCommand(isInternal));
                    RestartUtils.restart();
                },
                () -> {});
    }

    public void switchTheme(String name, Runnable onUpdateHeader) {
        onUpdateHeader.run();
        CommandBus.dispatch(new SwitchThemeCommand(name));
    }

    public void handleNavToggle(Consumer<String> onUpdateHeader, Runnable onCloseSidebar) {
        CommandBus.dispatch(new ToggleNavModeCommand());
        onUpdateHeader.accept(ViewportState.getInstance().isNavMode() ? "已开启" : "已关闭");
        onCloseSidebar.run();
    }
}
