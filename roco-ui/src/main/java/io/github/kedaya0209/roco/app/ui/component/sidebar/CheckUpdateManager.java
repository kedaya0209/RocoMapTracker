package io.github.kedaya0209.roco.app.ui.component.sidebar;

import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import io.github.kedaya0209.roco.app.update.UpdateManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import net.jcip.annotations.NotThreadSafe;

/**
 * 检查更新管理器 — 侧边栏"检查更新"按钮及下载进度 UI。
 * <p>
 * 状态：普通按钮 → 进度条模式 → 恢复按钮。
 * 独立管理自身 UI，不依赖 ObservableList 更新，避免触发全列表刷新。
 */
@NotThreadSafe
public class CheckUpdateManager implements SidebarComponent {

    private final StackPane container;
    private final Button updateBtn;
    private final ProgressBar progressBar;
    private final Label progressLabel;

    public CheckUpdateManager() {
        container = new StackPane();
        container.setPadding(new Insets(0, 0, 0, 0));

        Node icon = SvgManager.createHoverDrawIcon("/icon/update.svg", 18, 1.5, 400);
        Label btnText = new Label("检查更新");
        btnText.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");
        HBox btnContent = new HBox(8, icon, btnText);
        btnContent.setAlignment(Pos.CENTER_LEFT);

        updateBtn = new Button();
        updateBtn.setGraphic(btnContent);
        updateBtn.setMaxWidth(Double.MAX_VALUE);
        updateBtn.setPrefHeight(36);
        updateBtn.setAlignment(Pos.BASELINE_LEFT);
        updateBtn.setStyle("-fx-background-color: -color-bg-subtle; -fx-text-fill: -color-fg-default; -fx-background-radius: 6; -fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 0 12 0 12;");
        FxRippleUtil.install(updateBtn);

        updateBtn.setOnMouseEntered(e -> SvgManager.animateHoverDrawIcon(icon, true, 400));
        updateBtn.setOnMouseExited(e -> SvgManager.animateHoverDrawIcon(icon, false, 400));

        updateBtn.setOnAction(_ -> {
            var updateMgr = UpdateManager.getInstance();
            if (updateMgr.isChecking() || updateMgr.isDownloading()) {
                return;
            }
            switchToProgress(0);
            updateMgr.manualCheck(() -> Platform.runLater(() -> {
                if (!UpdateManager.getInstance().isDownloading()) {
                    switchToNormal();
                }
            }));
        });

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(36);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-background-radius: 6; -fx-accent: -color-accent-emphasis;");

        progressLabel = new Label("检查更新");
        progressLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: -color-fg-default;");
        progressLabel.setMouseTransparent(true);
        progressLabel.setVisible(false);

        container.getChildren().addAll(updateBtn, progressBar, progressLabel);
    }

    @Override
    public StackPane getContainer() {
        return container;
    }

    /** 更新下载进度（0~1），progress < 0 表示恢复正常按钮状态 */
    public void setProgress(double progress) {
        Platform.runLater(() -> {
            if (progress < 0) {
                switchToNormal();
            } else {
                switchToProgress(progress);
            }
        });
    }

    private void switchToProgress(double progress) {
        updateBtn.setDisable(true);
        updateBtn.setOpacity(0);
        updateBtn.setVisible(false);
        progressBar.setVisible(true);
        progressBar.setProgress(progress);
        progressLabel.setVisible(true);
        progressLabel.setText(progress > 0
                ? String.format("检查更新 (%.0f%%)", progress * 100)
                : "正在检查...");
    }

    private void switchToNormal() {
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        updateBtn.setDisable(false);
        updateBtn.setOpacity(1);
        updateBtn.setVisible(true);
    }
}
