package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.ui.ModernCanvasApp;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

public class Sidebar extends VBox {

    private StackPane btnContainer;
    private Button updateBtn;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Label statusLabel; // 新增：显示具体在下哪个地图

    public Sidebar() {
        super(15);
        setPadding(new Insets(20));
        setPrefWidth(220);
        setStyle("-fx-background-color: #1e1e1e; -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 0 1 0 0;");

        Label title = new Label("系统设置");
        title.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TEXT_CAPTION);
        title.setTextFill(Color.web("#888888"));

        getChildren().add(title);

        createProgressButton();
        getChildren().add(btnContainer);
    }

    private void createProgressButton() {
        btnContainer = new StackPane();
        btnContainer.setAlignment(Pos.CENTER);

        // 1. 原始按钮
        updateBtn = new Button("更新资源");
        updateBtn.setMaxWidth(Double.MAX_VALUE);
        SVGPath downloadIcon = new SVGPath();
        downloadIcon.setContent("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z");
        downloadIcon.setFill(Color.WHITE);
        updateBtn.setGraphic(downloadIcon);
        updateBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SUCCESS);
        updateBtn.setOnAction(e -> showCustomConfirmDialog());

        // 2. 进度条容器 (包含进度条和文字)
        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(32);
        progressBar.setVisible(false);
        progressBar.setOpacity(0);
        // 给进度条加个提示，告诉用户点击可以取消
        Tooltip.install(progressBar, new Tooltip("点击可取消下载任务"));
        progressBar.setOnMouseClicked(e -> showCancelConfirmDialog());

        // 3. 进度文字 (居中显示数值)
        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: white; -fx-font-weight: bold;");
        progressLabel.setMouseTransparent(true); // 让点击事件穿透给进度条
        progressLabel.setVisible(false);

        // 4. 状态小字 (显示在按钮下方或上方，这里我们把它叠在进度条上方一点)
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #00BFFF;");
        statusLabel.setTranslateY(-22); // 向上偏移，不遮挡进度数值
        statusLabel.setVisible(false);

        btnContainer.getChildren().addAll(updateBtn, progressBar, progressLabel, statusLabel);
    }

    private void showCustomConfirmDialog() {
        // ... (保持你原有的确认更新弹窗代码不变)
        StackPane root = (StackPane) getScene().getRoot();
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.7);");

        VBox dialog = new VBox(20);
        dialog.setMaxSize(360, 200);
        dialog.setPadding(new Insets(25));
        dialog.setAlignment(Pos.CENTER_LEFT);
        dialog.setStyle("-fx-background-color: #252525; -fx-background-radius: 12; -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1;");

        Label header = new Label("确认更新");
        header.getStyleClass().add(Styles.TITLE_3);
        header.setTextFill(Color.WHITE);

        Label body = new Label("确认开始更新任务？\n下载过程中请勿断开网络。");
        body.setTextFill(Color.web("#BBBBBB"));

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER_RIGHT);

        Button cancelBtn = new Button("取消");
        cancelBtn.getStyleClass().add(Styles.FLAT);
        Button confirmBtn = new Button("确定更新");
        confirmBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SUCCESS);

        actions.getChildren().addAll(cancelBtn, confirmBtn);
        dialog.getChildren().addAll(header, body, actions);
        mask.getChildren().add(dialog);
        root.getChildren().add(mask);

        cancelBtn.setOnAction(e -> root.getChildren().remove(mask));
        confirmBtn.setOnAction(e -> {
            root.getChildren().remove(mask);
            switchToLoadingState();
            startDownloadTask();
        });
    }

    /**
     * 新增：下载中点击取消的二次确认
     */
    private void showCancelConfirmDialog() {
        StackPane root = (StackPane) getScene().getRoot();
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.6);");

        VBox dialog = new VBox(15);
        dialog.setMaxSize(300, 150);
        dialog.setPadding(new Insets(20));
        dialog.setAlignment(Pos.CENTER);
        dialog.setStyle("-fx-background-color: #2b2b2b; -fx-background-radius: 10; -fx-border-color: #ff4444;");

        Label msg = new Label("确定要中止当前的下载任务吗？");
        msg.setTextFill(Color.WHITE);

        HBox actions = new HBox(10,
                new Button("继续下载") {{
                    getStyleClass().add(Styles.FLAT);
                    setOnAction(e -> root.getChildren().remove(mask));
                }},
                new Button("中止") {{
                    getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.DANGER);
                    setOnAction(e -> {
                        root.getChildren().remove(mask);
                        MapDownloader.stopDownload();   // 停止地图
                        IconDownloader.stopDownload();  // 停止图标
                        switchToNormalState();          // 复原 UI
                    });
                }}
        );
        actions.setAlignment(Pos.CENTER);

        dialog.getChildren().addAll(msg, actions);
        mask.getChildren().add(dialog);
        root.getChildren().add(mask);
    }

    private void switchToLoadingState() {
        updateBtn.setDisable(true);
        updateBtn.setOpacity(0);

        progressBar.setVisible(true);
        progressBar.setOpacity(1);
        progressLabel.setVisible(true);
        statusLabel.setVisible(true);

        DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
            Platform.runLater(() -> {
                double p = (total == 0) ? 0 : (double) completed / total;
                progressBar.setProgress(p);
                progressLabel.setText(completed + " / " + total);
                statusLabel.setText(DownloadProgressContext.getInstance().getStatusText());

                if (completed >= total && total > 0) {
                    // 延迟一秒回弹，让用户看清 100%
                    Platform.runLater(() -> {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                        }
                        switchToNormalState();
                    });
                }
            });
        });
    }

    private void switchToNormalState() {
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        statusLabel.setVisible(false);
        updateBtn.setDisable(false);
        updateBtn.setOpacity(1);
        progressLabel.setText("");
        statusLabel.setText("");
        progressBar.setProgress(0);
    }

    private void startDownloadTask() {
        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                // 成功提示
                ModernCanvasApp.notify("地图资源更新成功！", NotificationToast.Type.SUCCESS);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    switchToNormalState();
                    // 失败提示
                    ModernCanvasApp.notify("下载失败：网络连接超时", NotificationToast.Type.ERROR);
                });
            }
        });
    }
}