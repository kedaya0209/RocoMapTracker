package io.github.kedaya0209.roco.app.ui.service.ui;

import net.jcip.annotations.NotThreadSafe;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.component.dialog.PluginUpdateDialog;
import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateUiDelegate;

@NotThreadSafe
public class PluginUpdateHandler implements PluginUpdateUiDelegate {

    private final StackPane rootStack;

    public PluginUpdateHandler(StackPane rootStack) {
        this.rootStack = rootStack;
    }

    @Override
    public void showPluginUpdatesAvailable(Map<PluginInfo, PluginUpdateInfo> updates,
                                            Consumer<List<String>> onDownloadSelected) {
        Platform.runLater(() ->
                PluginUpdateDialog.showPluginUpdatesDialog(rootStack, updates, onDownloadSelected));
    }

    @Override
    public void showDownloadProgress(String pluginId, String version, double progress) {
    }

    @Override
    public void hideDownloadProgress(String pluginId) {
    }

    @Override
    public void showUpdateReady(String pluginId, String message, Runnable onOk) {
        Platform.runLater(() ->
                ConfirmDialog.showSimpleDialog(rootStack, "插件更新", message,
                        "确定", false, onOk));
    }
}
