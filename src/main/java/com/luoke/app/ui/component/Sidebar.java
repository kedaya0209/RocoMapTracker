package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.ui.ModernCanvasApp;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

public class Sidebar extends VBox {

    private StackPane btnContainer;
    private Button updateBtn;
    private ProgressBar progressBar;
    private Label progressLabel;
    private final List<Button> algoButtons = new ArrayList<>();
    private final String SIDEBAR_BG = "#1e1e1e";
    private final String BUTTON_BG = "#2b2b2b";
    private final String BUTTON_TEXT = "#FFFFFF";
    private final String ACCENT_COLOR = "#409EFF";
    private final String SELECTED_GREEN = "#4CAF50";
    private final double ITEM_HEIGHT = 38;
    private Label statusLabel;
    private VBox menuContent;
    private HBox menuHeader;
    private Button selectedButton = null;
    // 状态锁
    private boolean isAlgorithmLoading = false;

    public Sidebar() {
        super(0);
        setPadding(new Insets(20, 15, 20, 15));
        setPrefWidth(240);
        setStyle("-fx-background-color: " + SIDEBAR_BG + "; -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 1 0 0;");

        Label title = new Label("系统设置");
        title.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TEXT_CAPTION);
        title.setTextFill(Color.web("#888888"));
        title.setPadding(new Insets(0, 0, 15, 5));

        VBox controlsGroup = new VBox(8);

        createAnimatedMenu();
        createProgressButton();

        controlsGroup.getChildren().addAll(menuHeader, menuContent, btnContainer);
        getChildren().addAll(title, controlsGroup);
    }

    private void createAnimatedMenu() {
        menuHeader = new HBox(10);
        menuHeader.setAlignment(Pos.CENTER_LEFT);
        menuHeader.setPrefHeight(ITEM_HEIGHT);
        menuHeader.setCursor(Cursor.HAND);
        menuHeader.setPadding(new Insets(0, 12, 0, 12));
        menuHeader.setStyle("-fx-background-color: " + BUTTON_BG + "; -fx-background-radius: 6;");

        Label label = new Label("匹配算法选择");
        label.setTextFill(Color.web(BUTTON_TEXT));
        label.setStyle("-fx-font-size: 13px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        SVGPath arrow = new SVGPath();
        arrow.setContent("M7 10l5 5 5-5z");
        arrow.setFill(Color.WHITE);

        menuHeader.getChildren().addAll(label, spacer, arrow);

        menuContent = new VBox(5);
        menuContent.setOpacity(0);
        menuContent.setPrefHeight(0);
        menuContent.setMinHeight(0);
        menuContent.setStyle("-fx-background-color: transparent;");

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(this.widthProperty());
        clip.heightProperty().bind(menuContent.prefHeightProperty());
        menuContent.setClip(clip);

        String[] algos = {
                "SIFT", "SIFT-PCA", "SIFT-ULTRA", "SIFT-PCA-ULTRA"
        };

        for (String algo : algos) {
            Button item = new Button(algo);
            item.setMaxWidth(Double.MAX_VALUE);
            item.setPrefHeight(34);
            item.setAlignment(Pos.CENTER_LEFT);
            item.setPadding(new Insets(0, 0, 0, 15));
            item.setCursor(Cursor.HAND);
            item.setStyle("-fx-background-color: transparent; -fx-text-fill: #888888; -fx-font-size: 12px;");

            item.setOnMouseEntered(e -> {
                if (!isAlgorithmLoading && item != selectedButton) {
                    item.setStyle("-fx-background-color: rgba(255,255,255,0.03); -fx-text-fill: white;");
                }
            });
            item.setOnMouseExited(e -> {
                if (!isAlgorithmLoading && item != selectedButton) {
                    item.setStyle("-fx-background-color: transparent; -fx-text-fill: #888888;");
                }
            });

            item.setOnAction(e -> switchAlgorithm(algo, item));
            //默认选中
            if (AppConfig.MAP_MATCHAER.equalsIgnoreCase(algo)) {
                selectedButton = item; // 必须赋值给成员变量，否则下次切换时无法清除此按钮样式
                // 直接应用选中时的绿色样式
                item.setStyle("-fx-background-color: rgba(76,175,80,0.1); " +
                        "-fx-text-fill: " + SELECTED_GREEN + "; " +
                        "-fx-font-size: 12px; " +
                        "-fx-font-weight: bold;");
            }

            algoButtons.add(item);
            menuContent.getChildren().add(item);
        }

        double expandedHeight = algos.length * 39;
        Timeline animation = new Timeline();

        menuHeader.setOnMouseClicked(e -> {
            boolean opening = menuContent.getPrefHeight() == 0;
            animation.stop();
            animation.getKeyFrames().setAll(
                    new KeyFrame(Duration.millis(250),
                            new KeyValue(menuContent.prefHeightProperty(), opening ? expandedHeight : 0, javafx.animation.Interpolator.EASE_BOTH),
                            new KeyValue(menuContent.opacityProperty(), opening ? 1 : 0, javafx.animation.Interpolator.EASE_BOTH),
                            new KeyValue(arrow.rotateProperty(), opening ? 180 : 0, javafx.animation.Interpolator.EASE_BOTH)
                    )
            );
            animation.play();
        });
    }

    private void switchAlgorithm(String algo, Button clickedBtn) {
        if (isAlgorithmLoading) return;

        isAlgorithmLoading = true;
        // 锁定所有按钮并更改指针
        algoButtons.forEach(btn -> {
            btn.setDisable(true);
            btn.setCursor(Cursor.WAIT);
        });
        updateBtn.setDisable(true);

        // UI 表现：选中效果
        if (selectedButton != null) {
            selectedButton.setStyle("-fx-background-color: transparent; -fx-text-fill: #888888; -fx-font-size: 12px;");
        }
        selectedButton = clickedBtn;
        selectedButton.setStyle("-fx-background-color: rgba(76,175,80,0.1); -fx-text-fill: " + SELECTED_GREEN + "; -fx-font-size: 12px; -fx-font-weight: bold;");

        Thread.ofVirtual().start(() -> {
            try {
                SwitchMapMatcher.getInstance().switchMapMatcher(algo);
                Platform.runLater(() -> ModernCanvasApp.notify("算法已就绪: " + algo, NotificationToast.Type.SUCCESS));
            } catch (Exception e) {
                Platform.runLater(() -> ModernCanvasApp.notify("切换失败", NotificationToast.Type.ERROR));
            } finally {
                Platform.runLater(() -> {
                    isAlgorithmLoading = false;
                    algoButtons.forEach(btn -> {
                        btn.setDisable(false);
                        btn.setCursor(Cursor.HAND);
                    });
                    updateBtn.setDisable(false);
                });
            }
        });
    }

    private void createProgressButton() {
        btnContainer = new StackPane();
        btnContainer.setAlignment(Pos.CENTER);

        updateBtn = new Button("更新资源");
        updateBtn.setMaxWidth(Double.MAX_VALUE);
        updateBtn.setPrefHeight(ITEM_HEIGHT);
        updateBtn.setStyle("-fx-background-color: " + BUTTON_BG + "; -fx-text-fill: " + BUTTON_TEXT + "; -fx-background-radius: 6; -fx-font-size: 13px; -fx-cursor: hand;");

        SVGPath icon = new SVGPath();
        icon.setContent("M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z");
        icon.setFill(Color.WHITE);
        updateBtn.setGraphic(icon);

        updateBtn.setOnAction(e -> {
            if (!isAlgorithmLoading) {
                showCustomConfirmDialog();
            }
        });

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(ITEM_HEIGHT);
        progressBar.setVisible(false);

        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: white; -fx-font-weight: bold;");
        progressLabel.setMouseTransparent(true);
        progressLabel.setVisible(false);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + ACCENT_COLOR + ";");
        statusLabel.setTranslateY(-28);
        statusLabel.setVisible(false);

        btnContainer.getChildren().addAll(updateBtn, progressBar, progressLabel, statusLabel);
    }

    private void showCustomConfirmDialog() {
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

    private void startDownloadTask() {
        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                Platform.runLater(() -> ModernCanvasApp.notify("地图资源更新成功！", NotificationToast.Type.SUCCESS));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    switchToNormalState();
                    ModernCanvasApp.notify("下载失败：网络连接超时", NotificationToast.Type.ERROR);
                });
            }
        });
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
}