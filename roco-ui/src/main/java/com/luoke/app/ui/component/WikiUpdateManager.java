package com.luoke.app.ui.component;

import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.ui.util.DialogUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;

/**
 * WIKI 资源更新管理器 — 负责 WIKI 数据下载的 UI 与交互逻辑。
 * 从 Sidebar 拆分，遵循单一职责原则。
 */
public class WikiUpdateManager {

    private static final double ITEM_HEIGHT = 38;

    private final StackPane btnContainer;
    private final Button updateBtn;
    private final ProgressBar progressBar;
    private final Label progressLabel;

    public WikiUpdateManager() {
        btnContainer = new StackPane();
        btnContainer.setPadding(new Insets(0, 0, 0, 0));

        updateBtn = new Button("更新WIKI资源");
        updateBtn.setMaxWidth(Double.MAX_VALUE);
        updateBtn.setPrefHeight(ITEM_HEIGHT);
        updateBtn.setStyle("-fx-background-color: -color-bg-subtle; -fx-text-fill: -color-fg-default; -fx-background-radius: 6; -fx-font-size: 13px; -fx-cursor: hand;");

        updateBtn.setOnAction(e -> {
            StackPane root = (StackPane) updateBtn.getScene().getRoot();
            DialogUtils.showConfirmDialog(
                    root,
                    "确认更新",
                    "确认同步最新WIKI数据？下载过程中请保持网络畅通。",
                    () -> {
                        switchToLoadingState();
                        startDownloadTask();
                    },
                    () -> {}
            );
        });

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(ITEM_HEIGHT);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-background-radius: 6; -fx-accent: -color-accent-emphasis;");

        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-default; -fx-font-weight: bold;");
        progressLabel.setMouseTransparent(true);
        progressLabel.setVisible(false);

        btnContainer.getChildren().addAll(updateBtn, progressBar, progressLabel);
    }

    /** 返回容器节点，供父布局添加 */
    public StackPane getContainer() {
        return btnContainer;
    }

    /** 检测是否有正在进行的后台下载，有则切换到进度显示 */
    public void checkAndShowProgress() {
        DownloadProgressContext ctx = DownloadProgressContext.getInstance();
        if (ctx.getTotalTasks().get() > 0 && ctx.getCompletedTasks().get() < ctx.getTotalTasks().get()) {
            switchToLoadingState();
        }
    }

    private void startDownloadTask() {
        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                Platform.runLater(this::switchToNormalState);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("WIKI资源同步完成", NotificationType.SUCCESS));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    switchToNormalState();
                    HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                            new StatusEvent("资源同步失败，请检查网络", NotificationType.ERROR));
                });
            }
        });
    }

    private void switchToLoadingState() {
        updateBtn.setDisable(true);
        updateBtn.setOpacity(0);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);

        DownloadProgressContext ctx = DownloadProgressContext.getInstance();
        int completed = ctx.getCompletedTasks().get();
        int total = ctx.getTotalTasks().get();
        updateProgressLabel(ctx.getStatusText(), completed, total);
        progressBar.setProgress(total == 0 ? 0 : (double) completed / total);

        ctx.setOnProgressUpdate((c, t) -> {
            Platform.runLater(() -> {
                progressBar.setProgress(t == 0 ? 0 : (double) c / t);
                updateProgressLabel(ctx.getStatusText(), c, t);

                HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS,
                        new ProgressEvent(t == 0 ? 0 : (double) c / t, "WIKI同步: " + ctx.getStatusText()));
            });
        });
    }

    private void updateProgressLabel(String status, int completed, int total) {
        progressLabel.setText(status + "（" + completed + "/" + total + "）");
    }

    private void switchToNormalState() {
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        updateBtn.setDisable(false);
        updateBtn.setOpacity(1);
    }
}
