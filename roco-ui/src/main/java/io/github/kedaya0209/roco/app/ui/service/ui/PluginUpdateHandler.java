package io.github.kedaya0209.roco.app.ui.service.ui;

import net.jcip.annotations.NotThreadSafe;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.component.dialog.PluginUpdateDialog;
import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateUiDelegate;

@NotThreadSafe
public class PluginUpdateHandler implements PluginUpdateUiDelegate {

    private final StackPane rootStack;

    /** 上次通知的进度（按 10% 档位节流） */
    private final Map<String, Integer> lastNotified = new ConcurrentHashMap<>();

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
        int pct = (int) (progress * 100);
        int prev = lastNotified.getOrDefault(pluginId, -1);
        if (pct - prev >= 10 || pct >= 100) {
            lastNotified.put(pluginId, pct);
            Platform.runLater(() ->
                    AppEvents.publish(StatusEvent.class,
                            new StatusEvent(pluginId + " 下载中 " + pct + "%", NotificationType.INFO)));
        }
    }

    @Override
    public void hideDownloadProgress(String pluginId) {
        lastNotified.remove(pluginId);
        Platform.runLater(() ->
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent(pluginId + " 下载完成", NotificationType.SUCCESS)));
    }

    @Override
    public void showUpdateReady(String pluginId, String message, Runnable onOk) {
        Platform.runLater(() ->
                ConfirmDialog.showSimpleDialog(rootStack, "插件更新", message,
                        "确定", false, onOk));
    }
}
