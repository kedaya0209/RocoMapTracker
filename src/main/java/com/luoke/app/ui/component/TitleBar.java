package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.AppConfig;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import lombok.Getter;

public class TitleBar extends HBox {

    private final String unifiedBlue;
    private double xOffset = 0;
    private double yOffset = 0;
    @Getter
    private boolean ghostMode = false;

    private TitleBar(Stage stage, Button menuBtn, String unifiedBlue, Node... overlayNodes) {
        super(12);
        this.unifiedBlue = unifiedBlue;

        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(5, 10, 5, 10));
        setStyle("-fx-background-color: rgba(30, 30, 30, 0.95); -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 0 0 1 0;");

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
        opacitySlider.setStyle("-fx-control-inner-background: " + unifiedBlue + ";");

        // --- 2. 幽灵模式锚点图标 ---
        Button ghostBtn = new Button();
        ghostBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);

        SVGPath ghostIcon = new SVGPath();
        ghostIcon.setContent("M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z");
        ghostIcon.setFill(Color.web("C0C0C0"));
        ghostBtn.setGraphic(ghostIcon);

        ghostBtn.setOnAction(e -> {
            ghostMode = !ghostMode;

            // 切换状态显示
            ghostIcon.setFill(ghostMode ? Color.web(unifiedBlue) : Color.web("C0C0C0"));

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

    private static Button getCloseButton(Stage stage) {
        Button closeBtn = new Button("✕");

        // 初始样式：无边框，白色文字，透明背景
        closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" + // 显式去掉边框
                        "-fx-text-fill: white;" +          // 初始颜色设为白色
                        "-fx-font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif;" +
                        "-fx-font-size: 16px;" +           // 稍微调大一点点，视觉更清晰
                        "-fx-font-weight: normal;" +       // 细一点更精致
                        "-fx-padding: 6px 12px;" +
                        "-fx-cursor: hand;"                // 加上手型，增强交互感
        );

        // Hover 逻辑：变黑底，文字保持白色或略微变亮
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle(
                closeBtn.getStyle()
                        .replace("-fx-text-fill: white;", "-fx-text-fill: red;") +
                        "-fx-background-color: #1a1a1a;" // 悬停时的黑底效果
        ));

        closeBtn.setOnMouseExited(e -> closeBtn.setStyle(
                closeBtn.getStyle().replace("-fx-background-color: #1a1a1a;", "-fx-background-color: transparent;")
                        .replace("-fx-text-fill: red;", "-fx-text-fill: white;")
        ));

        closeBtn.setOnAction(e -> stage.close());
        return closeBtn;
    }

    public static TitleBar getInstance(Stage stage, Button menuBtn, String unifiedBlue, Node... overlayNodes) {
        return Holder.getINSTANCE(stage, menuBtn, unifiedBlue, overlayNodes);
    }

    public static TitleBar getInstance() {
        return Holder.getINSTANCE();
    }

    private static class Holder {
        private static volatile TitleBar INSTANCE;

        public static TitleBar getINSTANCE(Stage stage, Button menuBtn, String unifiedBlue, Node... overlayNodes) {
            if (INSTANCE == null) {
                INSTANCE = new TitleBar(stage, menuBtn, unifiedBlue, overlayNodes);
            }
            return INSTANCE;
        }

        public static TitleBar getINSTANCE() {
            if (INSTANCE == null) {
                throw new RuntimeException("实力未初始化");
            }
            return INSTANCE;
        }
    }

}