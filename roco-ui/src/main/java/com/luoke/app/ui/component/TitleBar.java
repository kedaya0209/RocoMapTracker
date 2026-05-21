package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.NavigConfig;
import com.luoke.app.config.PathConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
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
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

@NotThreadSafe
@Slf4j

public class TitleBar extends HBox implements IHook<Object> {

    private double xOffset = 0;
    private double yOffset = 0;
    @Getter
    private boolean ghostMode = false;
    @Getter
    private boolean navMode = false;
    /** 导航模式按钮，供外部引用图标着色 */
    private final Button navBtn;
    /** 匹配开关按钮 */
    private final Button matchToggleBtn;
    /** 幽灵模式按钮 */
    private final Button ghostBtn;
    /** 幽灵模式图标 */
    private final Node ghostIcon;
    /** 幽灵模式透明度滑块 */
    private final Slider opacitySlider;
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
        opacitySlider = new Slider(0.1, 1.0, 1.0);
        opacitySlider.setPrefWidth(120);
        // 核心改动：默认完全透明，但不隐藏（Managed保持为true保证占位）
        opacitySlider.setOpacity(0.0);
        opacitySlider.setDisable(true); // 非幽灵模式下禁用，防止误触
        opacitySlider.setStyle("-fx-control-inner-background: -color-accent-emphasis;");
        // 滑块值变化 → 设置窗口透明度；导航模式下同步回 NavigConfig
        opacitySlider.valueProperty().addListener((_, _, val) -> {
            stage.setOpacity(val.doubleValue());
            if (navMode) {
                NavigConfig.NAV_WINDOW_OPACITY = val.doubleValue();
            }
        });
        // 拖动结束 → 持久化透明度
        opacitySlider.setOnMouseReleased(_ -> ConfigPersistence.save());

        // --- 2. 幽灵模式锚点图标 ---
        ghostBtn = new Button();
        ghostBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-padding: 6px;" +
                        "-fx-cursor: hand;"
        );

        ghostIcon = createGhostIcon();
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
                // 关闭幽灵模式：若导航模式处于激活状态则恢复导航透明度，否则恢复全透明
                if (navMode) {
                    stage.setOpacity(NavigConfig.NAV_WINDOW_OPACITY);
                    opacitySlider.setValue(NavigConfig.NAV_WINDOW_OPACITY);
                } else {
                    stage.setOpacity(1.0);
                    opacitySlider.setValue(1.0);
                }
            } else if (navMode) {
                // 进入幽灵模式且导航模式已启用 → 应用导航透明度
                stage.setOpacity(NavigConfig.NAV_WINDOW_OPACITY);
                opacitySlider.setValue(NavigConfig.NAV_WINDOW_OPACITY);
            }
        });

        // --- 3. 导航模式按钮 ---
        navBtn = new Button();
        navBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-padding: 6px;" +
                        "-fx-cursor: hand;"
        );

        Node navIcon = createNavIcon();
        setSvgFill(navIcon, "-color-fg-muted");
        navBtn.setGraphic(navIcon);
        navBtn.setPrefSize(32, 32);
        navBtn.setMinSize(32, 32);
        navBtn.setMaxSize(32, 32);

        String navBaseStyle = navBtn.getStyle();
        navBtn.setOnMouseEntered(_ -> navBtn.setStyle(
                navBaseStyle + "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 6px;"
        ));
        navBtn.setOnMouseExited(_ -> navBtn.setStyle(navBaseStyle));

        navBtn.setOnAction(_ -> toggleNavMode(stage, menuBtn, overlayNodes));

        // --- 3.5 匹配开关按钮 ---
        matchToggleBtn = new Button();
        matchToggleBtn.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-border-color: transparent;" +
                        "-fx-padding: 6px;" +
                        "-fx-cursor: hand;"
        );

        Node matchIcon = createMatchToggleIcon();
        boolean matchOn = SiftConfig.SIFT_MATCHING_ENABLED;
        setSvgFill(matchIcon, matchOn ? "-color-accent-emphasis" : "-color-fg-muted");
        matchToggleBtn.setGraphic(matchIcon);
        matchToggleBtn.setPrefSize(32, 32);
        matchToggleBtn.setMinSize(32, 32);
        matchToggleBtn.setMaxSize(32, 32);

        String matchBaseStyle = matchToggleBtn.getStyle();
        matchToggleBtn.setOnMouseEntered(_ -> matchToggleBtn.setStyle(
                matchBaseStyle + "-fx-background-color: -color-bg-subtle;" +
                        "-fx-background-radius: 6px;"
        ));
        matchToggleBtn.setOnMouseExited(_ -> matchToggleBtn.setStyle(matchBaseStyle));

        matchToggleBtn.setOnAction(_ -> {
            SiftConfig.SIFT_MATCHING_ENABLED = !SiftConfig.SIFT_MATCHING_ENABLED;
            boolean nowOn = SiftConfig.SIFT_MATCHING_ENABLED;
            setSvgFill(matchIcon, nowOn ? "-color-accent-emphasis" : "-color-fg-muted");
            // 发布轮播事件
            HookRegistry.INSTANCE.publish(HookEventType.STATUS_CAROUSEL,
                    nowOn ? StatusCarouselEvent.matchingResumed()
                          : StatusCarouselEvent.matchingPaused());
        });

        Button closeBtn = getCloseButton(stage);

        // --- 4. 调整子组件顺序：滑块在图标左侧，图标靠右锚定 ---
        getChildren().addAll(menuBtn, titleLabel, statusContainer, spacer, opacitySlider, ghostBtn, matchToggleBtn, navBtn, closeBtn);

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
    // 导航模式
    // ============================================================

    /**
     * 创建导航模式图标 — 指南针 SVG
     */
    private static Node createNavIcon() {
        try {
            return SvgManager.createIcon(PathConfig.NAVIGATION, 20);
        } catch (Exception ex) {
            log.warn("加载导航图标失败，回退到幽灵图标", ex);
            return createGhostIcon();
        }
    }

    /**
     * 创建匹配开关图标
     */
    private static Node createMatchToggleIcon() {
        try {
            return SvgManager.createIcon(PathConfig.MATCH_TOGGLE, 20);
        } catch (Exception ex) {
            log.warn("加载匹配开关图标失败，回退到幽灵图标", ex);
            return createGhostIcon();
        }
    }

    /**
     * 切换导航模式
     */
    private void toggleNavMode(Stage stage, Button menuBtn, Node... overlayNodes) {
        navMode = !navMode;
        NavigConfig.NAVIGATION_ENABLED = navMode;

        Node navGraphic = navBtn.getGraphic();
        setSvgFill(navGraphic, navMode ? "-color-accent-emphasis" : "-color-fg-muted");

        CameraContext cam = CameraContext.getInstance();
        cam.setNavMode(navMode);

        if (navMode) {
            // 应用导航透明度
            stage.setOpacity(NavigConfig.NAV_WINDOW_OPACITY);
            opacitySlider.setValue(NavigConfig.NAV_WINDOW_OPACITY);

            // 自动开启跟随模式（updateViewport 内部有 hasValidPlayerPosition 保护）
            if (NavigConfig.AUTO_FOLLOW_MODE) {
                CameraContext.getInstance().setFollowMode(true);
            }
        } else {
            // 恢复窗口正常状态
            if (!ghostMode) {
                stage.setOpacity(1.0);
                opacitySlider.setValue(1.0);
            }
            cam.setNavAngle(0);
        }
    }

    /**
     * 外部调用 — 当 Sidebar/Setting 切换导航模式时同步 UI
     */
    public void setNavModeFromExternal(boolean enabled) {
        if (enabled == navMode) return;
        navBtn.fire();
    }

    /**
     * 外部调用 — Sidebar 切换匹配开关时同步标题栏图标与轮播事件
     */
    public void publishMatchToggleEvent() {
        boolean on = SiftConfig.SIFT_MATCHING_ENABLED;
        Node icon = matchToggleBtn.getGraphic();
        setSvgFill(icon, on ? "-color-accent-emphasis" : "-color-fg-muted");
        HookRegistry.INSTANCE.publish(HookEventType.STATUS_CAROUSEL,
                on ? StatusCarouselEvent.matchingResumed()
                    : StatusCarouselEvent.matchingPaused());
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