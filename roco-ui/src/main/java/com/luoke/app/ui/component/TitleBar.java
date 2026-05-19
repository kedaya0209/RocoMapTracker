package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.PathConfig;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.IHook;
import com.luoke.app.hook.event.StatusCarouselEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.ui.service.SvgManager;
import com.luoke.app.ui.util.DialogUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.util.Duration;
import lombok.Getter;

import java.util.Set;

public class TitleBar extends HBox implements IHook<Object> {

    private double xOffset = 0;
    private double yOffset = 0;
    @Getter
    private boolean ghostMode = false;
    /** 标题栏内联状态轮播标签 */
    private final Label statusLabel = new Label();
    private static final double STATUS_H = 20;

    private TitleBar(Stage stage, Button menuBtn, Node... overlayNodes) {
        super(12);

        setAlignment(Pos.CENTER_LEFT);
        setPadding(new Insets(5, 10, 5, 10));
        setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-muted; -fx-border-width: 0 0 1 0;");

        Label titleLabel = new Label(CaptureConfig.APP_MAIN_TITLE);
        titleLabel.getStyleClass().add(Styles.TITLE_4);

        // --- 内联状态轮播 ---
        statusLabel.setPrefHeight(STATUS_H);
        statusLabel.setMaxHeight(STATUS_H);
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        // clip 容器，实现从下至上的滚动效果
        Rectangle statusClip = new Rectangle(0, 0, 0, STATUS_H);
        StackPane statusContainer = new StackPane(statusLabel);
        statusClip.widthProperty().bind(statusContainer.widthProperty());
        statusContainer.setClip(statusClip);
        statusContainer.setPrefHeight(STATUS_H);
        statusContainer.setMaxHeight(STATUS_H);
        statusContainer.setMinWidth(0);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // --- 1. 进度条（滑块）设置 ---
        Slider opacitySlider = new Slider(0.1, 1.0, 1.0);
        opacitySlider.setPrefWidth(120);
        // 核心改动：默认完全透明，但不隐藏（Managed保持为true保证占位）
        opacitySlider.setOpacity(0.0);
        opacitySlider.setDisable(true); // 非幽灵模式下禁用，防止误触
        opacitySlider.valueProperty().addListener((_, _, val) -> stage.setOpacity(val.doubleValue()));
        opacitySlider.setStyle("-fx-control-inner-background: -color-accent-emphasis;");

        // --- 2. 幽灵模式锚点图标 ---
        Button ghostBtn = new Button();
        ghostBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-padding: 6px;" +
                        "-fx-cursor: hand;"
        );

        Node ghostIcon = createGhostIcon();
        setSvgFill(ghostIcon, "-color-fg-muted");
        ghostBtn.setGraphic(ghostIcon);
        ghostBtn.setPrefSize(32, 32);
        ghostBtn.setMinSize(32, 32);
        ghostBtn.setMaxSize(32, 32);

        // Hover 背景高亮（baseStyle 重建避免累积）
        String ghostBaseStyle = ghostBtn.getStyle();
        ghostBtn.setOnMouseEntered(_ -> ghostBtn.setStyle(
                ghostBaseStyle + "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 6px;"
        ));
        ghostBtn.setOnMouseExited(_ -> ghostBtn.setStyle(ghostBaseStyle));

        ghostBtn.setOnAction(_ -> {
            ghostMode = !ghostMode;

            // 切换状态显示：激活态使用 accent，非激活恢复 fg-muted
            setSvgFill(ghostIcon, ghostMode ? "-color-accent-emphasis" : "-color-fg-muted");

            // --- 核心改动：滑块透明度切换 ---
            opacitySlider.setOpacity(ghostMode ? 1.0 : 0.0);
            opacitySlider.setDisable(!ghostMode);

            stage.setAlwaysOnTop(ghostMode);
            menuBtn.setMouseTransparent(ghostMode);
            for (Node node : overlayNodes) node.setMouseTransparent(ghostMode);

            if (!ghostMode) {
                stage.setOpacity(1.0);
                opacitySlider.setValue(1.0);
            }
        });

        Button closeBtn = getCloseButton(stage);

        // --- 4. 调整子组件顺序：滑块在图标左侧，图标靠右锚定 ---
        getChildren().addAll(menuBtn, titleLabel, statusContainer, spacer, opacitySlider, ghostBtn, closeBtn);

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

        // 注册状态轮播事件
        HookRegistry.INSTANCE.register(this);
    }

    public static TitleBar getInstance(Stage stage, Button menuBtn, Node... overlayNodes) {
        return Holder.getINSTANCE(stage, menuBtn, overlayNodes);
    }

    public static TitleBar getInstance() {
        return Holder.getINSTANCE();
    }

    /**
     * 为 SVG 图标节点设置 CSS fill 颜色
     */
    private static void setSvgFill(Node iconNode, String cssColor) {
        String style = "-fx-fill: " + cssColor + ";";
        if (iconNode instanceof StackPane sp) {
            for (Node child : sp.getChildren()) {
                if (child instanceof Group g) {
                    for (Node gc : g.getChildren()) {
                        gc.setStyle(style);
                    }
                }
            }
        } else if (iconNode instanceof Group g) {
            for (Node child : g.getChildren()) {
                child.setStyle(style);
            }
        }
    }

    private static Node createGhostIcon() {
        try {
            return SvgManager.createIcon(PathConfig.GHOST, 20);
        } catch (Exception ex) {
            // fallback: 圆环图标
            SVGPath fallback = new SVGPath();
            fallback.setContent("M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z");
            fallback.setStyle("-fx-fill: -color-fg-muted;");
            StackPane box = new StackPane(fallback);
            box.setPrefSize(20, 20);
            box.setMinSize(20, 20);
            box.setMaxSize(20, 20);
            return box;
        }
    }

    // ============================================================
    // 状态轮播
    // ============================================================

    /**
     * 更新标题栏内联状态文本，附带从下至上的滚动动画效果。
     * 每个新状态文本从下方滚入，旧状态从上方滚出。
     */
    public void updateStatus(StatusCarouselEvent event) {
        if (event == null) return;
        if (event.text() == null || event.text().isBlank()) {
            statusLabel.setText("");
            statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
            return;
        }

        String color = switch (event.type()) {
            case LOADING -> "#FFD700";
            case SUCCESS -> "#00E676";
            case ERROR -> "#FF5252";
            case INFO -> "-color-fg-muted";
        };
        String newStyle = String.format("-fx-text-fill: %s; -fx-font-size: 12px;", color);

        // 首次设置 / 当前为空 → 直接显示，不动画
        if (statusLabel.getText().isEmpty()) {
            statusLabel.setText(event.text());
            statusLabel.setStyle(newStyle);
            return;
        }

        // 文本未变 → 忽略
        if (event.text().equals(statusLabel.getText())) return;

        // 从下至上滚动动画：旧文本向上滚出，新文本从下方滚入
        // Step 1: 旧文本向上平移移出 clip
        TranslateTransition exit = getTranslateTransition(event, newStyle);
        exit.play();
    }

    private TranslateTransition getTranslateTransition(StatusCarouselEvent event, String newStyle) {
        TranslateTransition exit = new TranslateTransition(Duration.millis(120), statusLabel);
        exit.setToY(-STATUS_H);
        exit.setOnFinished(_ -> {
            // Step 2: 在 clip 外更换文本 + 重置到下方
            statusLabel.setText(event.text());
            statusLabel.setStyle(newStyle);
            statusLabel.setTranslateY(STATUS_H);

            // Step 3: 新文本向上平移进入 clip
            TranslateTransition enter = new TranslateTransition(Duration.millis(120), statusLabel);
            enter.setToY(0);
            enter.play();
        });
        return exit;
    }

    private Button getCloseButton(Stage stage) {
        // SVG X 图标（与路线管理器一致）
        SVGPath closeIcon = new SVGPath();
        closeIcon.setContent("M1 1 L9 9 M9 1 L1 9");
        closeIcon.setStyle("-fx-stroke: -color-fg-muted; -fx-stroke-width: 2; -fx-stroke-line-cap: round;");
        StackPane closeGraphic = new StackPane(closeIcon);
        closeGraphic.setPrefSize(20, 20);
        closeGraphic.setMinSize(20, 20);
        closeGraphic.setMaxSize(20, 20);

        Button closeBtn = new Button();
        closeBtn.setGraphic(closeGraphic);

        // 初始样式：无边框，透明背景
        closeBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-padding: 6px;" +
                        "-fx-cursor: hand;"
        );

        // Hover 逻辑（baseStyle 重建避免累积）
        String closeBaseStyle = closeBtn.getStyle();
        closeBtn.setOnMouseEntered(_ -> {
            closeIcon.setStyle("-fx-stroke: -color-danger-emphasis; -fx-stroke-width: 2; -fx-stroke-line-cap: round;");
            closeBtn.setStyle(closeBaseStyle + "-fx-background-color: -color-bg-subtle;" +
                    "-fx-background-radius: 6px;");
        });

        closeBtn.setOnMouseExited(_ -> {
            closeIcon.setStyle("-fx-stroke: -color-fg-muted; -fx-stroke-width: 2; -fx-stroke-line-cap: round;");
            closeBtn.setStyle(closeBaseStyle);
        });

        // ===================== 【核心：添加关闭确认弹窗】 =====================
        closeBtn.setOnAction(_ -> {
            if (this.getParent() != null && this.getParent().getParent() instanceof StackPane rootStack) {
                DialogUtils.showConfirmDialog(
                        rootStack,
                        "确认退出",
                        "确定要关闭程序吗？\n所有识别与渲染服务将会停止运行。",
                        // 确认：关闭窗口
                        stage::close,
                        // 取消：什么都不做，直接关闭弹窗
                        () -> {
                        }
                );
            } else {
                stage.close();
            }
        });
        // ====================================================================

        return closeBtn;
    }

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.STATUS_CAROUSEL);
    }

    @Override
    public void onEvent(HookEventType type, Object data) {
        if (data instanceof StatusCarouselEvent event) {
            Platform.runLater(() -> updateStatus(event));
        }
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