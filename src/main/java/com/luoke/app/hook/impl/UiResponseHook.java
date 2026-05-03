package com.luoke.app.hook.impl;

import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.CaptureStateEvent;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.ui.component.LoadingOverlay;
import com.luoke.app.ui.component.NotificationToast;
import com.luoke.app.ui.component.StatsOverlay;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;

import java.util.Set;

public class UiResponseHook extends AbstractGenericHook<Object> {

    private final StackPane rootStack;
    private final LoadingOverlay globalLoading;

    public UiResponseHook(StackPane rootStack, LoadingOverlay globalLoading) {
        this.rootStack = rootStack;
        this.globalLoading = globalLoading;
    }

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(
                HookEventType.UI_NOTIFICATION,
                HookEventType.INIT_PROGRESS,
                HookEventType.CAPTURE_STATE
        );
    }

    @Override
    public void onEvent(HookEventType type, Object data) {
        Platform.runLater(() -> {
            switch (type) {
                case UI_NOTIFICATION -> {
                    if (data instanceof StatusEvent(String message, NotificationToast.Type type1)) {
                        NotificationToast.show(rootStack, message, type1);
                    }
                }
                case INIT_PROGRESS -> {
                    if (data instanceof ProgressEvent(double value, String text) && globalLoading != null) {
                        globalLoading.updateProgress(value, text);
                    }
                }
                case CAPTURE_STATE -> {
                    if (data instanceof CaptureStateEvent state) {
                        if (state.connected()) {
                            NotificationToast.show(rootStack, "窗口连接成功 ID: " + state.id(), NotificationToast.Type.SUCCESS);
                        } else {
                            NotificationToast.show(rootStack, "游戏连接断开，等待重连", NotificationToast.Type.ERROR);
                        }
                        // 这里调用你实际拥有的 update 方法来刷新显示逻辑
                        StatsOverlay.getInstance().update();
                    }
                }
            }
        });
    }
}