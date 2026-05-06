package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.AppConfig;
import com.luoke.app.ui.util.DialogUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import lombok.Getter;

public class TitleBar extends HBox {

    private double xOffset = 0;
    private double yOffset = 0;
    @Getter
    private boolean ghostMode = false;

    private TitleBar(Stage stage, Button menuBtn, Node... overlayNodes) {
        super(12);

        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(5, 10, 5, 10));
        setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-muted; -fx-border-width: 0 0 1 0;");

        Label titleLabel = new Label(AppConfig.APP_MAIN_TITLE);
        titleLabel.getStyleClass().add(Styles.TITLE_4);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // --- 1. 进度条（滑块）设置 ---
        Slider opacitySlider = new Slider(0.1, 1.0, 1.0);
        opacitySlider.setPrefWidth(120);
        // 核心改动：默认完全透明，但不隐藏（Managed保持为true保证占位）
        opacitySlider.setOpacity(0.0);
        opacitySlider.setDisable(true); // 非幽灵模式下禁用，防止误触
        opacitySlider.valueProperty().addListener((obs, old, val) -> stage.setOpacity(val.doubleValue()));
        opacitySlider.setStyle("-fx-control-inner-background: -color-accent-emphasis;");

        // --- 2. 幽灵模式锚点图标 ---
        Button ghostBtn = new Button();
        ghostBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);

        SVGPath ghostIcon = new SVGPath();
        ghostIcon.setContent("M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z");
        ghostIcon.setStyle("-fx-fill: -color-fg-muted;");
        ghostBtn.setGraphic(ghostIcon);

        ghostBtn.setOnAction(e -> {
            ghostMode = !ghostMode;

            // 切换状态显示：激活态使用 accent，非激活恢复 fg-muted
            ghostIcon.setStyle(ghostMode ? "-fx-fill: -color-accent-emphasis;" : "-fx-fill: -color-fg-muted;");

            // --- 核心改动：滑块透明度切换 ---
            opacitySlider.setOpacity(ghostMode ? 1.0 : 0.0);
            opacitySlider.setDisable(!ghostMode);

            stage.setAlwaysOnTop(ghostMode);
            for (Node node : overlayNodes) node.setMouseTransparent(ghostMode);

            if (!ghostMode) {
                stage.setOpacity(1.0);
                opacitySlider.setValue(1.0);
            }
        });

        Button closeBtn = getCloseButton(stage);

        // --- 4. 调整子组件顺序：滑块在图标左侧，图标靠右锚定 ---
        getChildren().addAll(menuBtn, titleLabel, spacer, opacitySlider, ghostBtn, closeBtn);

        // 窗口移动逻辑保持不变
        setOnMousePressed(e -> {
            if (!ghostMode) {
                xOffset = e.getSceneX();
                yOffset = e.getSceneY();
            }
        });
        setOnMouseDragged(e -> {
            if (!ghostMode) {
                stage.setX(e.getScreenX() - xOffset);
                stage.setY(e.getScreenY() - yOffset);
            }
        });
    }

    public static TitleBar getInstance(Stage stage, Button menuBtn, Node... overlayNodes) {
        return Holder.getINSTANCE(stage, menuBtn, overlayNodes);
    }

    private Button getCloseButton(Stage stage) {
        Button closeBtn = new Button("✕");

        // 初始样式：无边框，透明背景
        closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-text-fill: -color-fg-muted;" +
                        "-fx-font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;" +
                        "-fx-font-size: 16px;" +
                        "-fx-font-weight: normal;" +
                        "-fx-padding: 6px 12px;" +
                        "-fx-cursor: hand;"
        );

        // Hover 逻辑
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
                closeBtn.getStyle()
                        .replace("-fx-text-fill: -color-fg-muted;", "-fx-text-fill: -color-danger-emphasis;") +
                        "-fx-background-color: -color-bg-subtle;"
        ));

        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
                closeBtn.getStyle()
                        .replace("-fx-background-color: -color-bg-subtle;", "-fx-background-color: transparent;")
                        .replace("-fx-text-fill: -color-danger-emphasis;", "-fx-text-fill: -color-fg-muted;")
        ));

        // ===================== 【核心：添加关闭确认弹窗】 =====================
        closeBtn.setOnAction(e -> {
            if (this.getParent() != null && this.getParent().getParent() instanceof StackPane rootStack) {
                DialogUtils.showConfirmDialog(
                        rootStack,
                        "确认退出",
                        "确定要关闭程序吗？\n所有识别与渲染服务将会停止运行。",
                        // 确认：关闭窗口
                        stage::close,
                        // 取消：什么都不做，直接关闭弹窗
                        () -> {}
                );
            } else {
                stage.close();
            }
        });
        // ====================================================================

        return closeBtn;
    }

    public static TitleBar getInstance() {
        return Holder.getINSTANCE();
    }

    private static class Holder {
        private static volatile TitleBar INSTANCE;

        public static TitleBar getINSTANCE(Stage stage, Button menuBtn, Node... overlayNodes) {
            if (INSTANCE == null) {
                INSTANCE = new TitleBar(stage, menuBtn, overlayNodes);
            }
            return INSTANCE;
        }

        public static TitleBar getINSTANCE() {
            if (INSTANCE == null) {
                throw new RuntimeException("实例未初始化");
            }
            return INSTANCE;
        }
    }

}