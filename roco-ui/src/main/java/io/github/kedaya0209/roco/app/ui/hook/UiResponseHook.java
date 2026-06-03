package io.github.kedaya0209.roco.app.ui.hook;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.CaptureStateEvent;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.ProgressEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.component.overlay.LoadingOverlay;
import io.github.kedaya0209.roco.app.ui.component.overlay.StatsOverlay;
import io.github.kedaya0209.roco.app.ui.component.overlay.ToastManager;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;


@NotThreadSafe
public class UiResponseHook {

    private final StackPane rootStack;
    private final LoadingOverlay globalLoading;

    public UiResponseHook(StackPane rootStack, LoadingOverlay globalLoading) {
        this.rootStack = rootStack;
        this.globalLoading = globalLoading;

        AppEvents.subscribe(StatusEvent.class, event -> {
            if (event.displayMode() == StatusEvent.DisplayMode.CAROUSEL) return;
            Platform.runLater(() -> ToastManager.show(rootStack, event.message(), event.type()));
        });
        AppEvents.subscribe(ProgressEvent.class, event ->
                Platform.runLater(() -> {
                    if (globalLoading != null) {
                        globalLoading.updateProgress(event.value(), event.text());
                    }
                }));
        AppEvents.subscribe(CaptureStateEvent.class, event ->
                Platform.runLater(() -> {
                    if (event.connected()) {
                        ToastManager.show(rootStack, "窗口连接成功 ID: " + event.id(), NotificationType.SUCCESS);
                    } else {
                        ToastManager.show(rootStack, "游戏连接断开，等待重连", NotificationType.ERROR);
                    }
                    StatsOverlay.getInstance().update();
                }));
    }
}