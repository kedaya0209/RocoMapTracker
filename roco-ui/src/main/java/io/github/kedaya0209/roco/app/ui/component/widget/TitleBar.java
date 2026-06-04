package io.github.kedaya0209.roco.app.ui.component.widget;

import lombok.Setter;
import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.config.NavigConfig;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.SetWindowOpacityCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleGhostModeCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleMatchingCommand;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.ToggleNavModeCommand;
import io.github.kedaya0209.roco.app.ui.command.CommandBus;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.ui.util.WindowHitTestHelper;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import io.github.kedaya0209.roco.app.ui.component.dialog.ModalConfirmDialog;
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
import javafx.animation.AnimationTimer;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;


@NotThreadSafe
@Slf4j

public class TitleBar extends HBox {

    /** 标题栏高度（像素），与 WindowHitTestHelper 穿透区域划分一致 */
    private static final int TITLE_BAR_HEIGHT = 40;

    private double xOffset = 0;
    private double yOffset = 0;
    @Getter
    private boolean ghostMode = false;
    @Getter
    private boolean navMode = false;
    /** 导航模式按钮，供外部引用图标着色 */
    private final Button navBtn;
    /** 窗口引用，供 syncNavUi 设置透明度 */
    private final Stage stageRef;
    /** 按位置显隐光标的过滤器 */
    private EventHandler<MouseEvent> cursorFilter;
    private boolean cursorShown = true;
    /** 光标强制执行器：通过 Win32 GetCursorPos 轮询位置，不依赖 JavaFX 事件 */
    private AnimationTimer cursorEnforcer;
    /** 幽灵模式图标 */
    private final Node ghostIcon;
    /** 幽灵模式透明度滑块 */
    private final Slider opacitySlider;
    /** 标题栏内联状态轮播标签 */
    private final Label statusLabel = new Label();
    private static final double STATUS_H = 20;
    /** 最小化至托盘回调
     * -- SETTER --
     *  设置最小化至托盘回调。
     */
    @Setter
    private Runnable minimizeHandler;

    private TitleBar(Stage stage, Button menuBtn, Node... overlayNodes) {
        super(12);
        this.stageRef = stage;

        setMinHeight(TITLE_BAR_HEIGHT);
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
        // 核心改动：默认隐藏，幽灵模式或导航模式下才显示
        opacitySlider.setVisible(false);
        opacitySlider.setDisable(true); // 非幽灵模式下禁用，防止误触
        opacitySlider.setStyle("-fx-control-inner-background: -color-accent-emphasis;");
        // 滑块值变化 → 设置窗口透明度；导航模式下同步回 NavigConfig
        opacitySlider.valueProperty().addListener((_, _, val) ->
                stage.setOpacity(val.doubleValue()));
        // 拖动结束 → 持久化透明度
        opacitySlider.setOnMouseReleased(_ ->
                CommandBus.dispatch(new SetWindowOpacityCommand(opacitySlider.getValue())));

        // --- 2. 幽灵模式锚点图标 ---
        /** 幽灵模式按钮 */
        Button ghostBtn = new Button();
        ghostBtn.setFocusTraversable(false);
        ghostBtn.getStyleClass().add("title-bar-btn");

        ghostIcon = createGhostIcon();
        setSvgFill(ghostIcon, "-color-fg-muted");
        ghostBtn.setGraphic(ghostIcon);

        ghostBtn.setOnAction(_ ->
                CommandBus.dispatch(new ToggleGhostModeCommand()));

        // 幽灵模式 Property → UI 副作用 + HWND 穿透
        AppState.getInstance().ghostModeProperty().addListener((_, _, now) -> {
            ghostMode = now;
            setSvgFill(ghostIcon, now ? "-color-accent-emphasis" : "-color-fg-muted");
            updateSliderVisibility();
            opacitySlider.setDisable(!now && !navMode);
            stage.setAlwaysOnTop(now);
            Scene scene = stage.getScene();
            if (now) {
                // 鼠标事件过滤器：仅消费内容区事件阻止 UI 交互
                if (cursorFilter == null) {
                    cursorFilter = e -> {
                        if (e.getSceneY() > TITLE_BAR_HEIGHT) e.consume();
                    };
                }
                scene.addEventFilter(MouseEvent.ANY, cursorFilter);
                WindowHitTestHelper.enablePartialPassthrough(stage, TITLE_BAR_HEIGHT);

                // AnimationTimer 每帧轮询 Win32 GetCursorPos + GetWindowRect，
                // 纯 Win32 判断光标是否在内容区，当在内容区时调用 ShowCursor(FALSE) 全局隐藏。
                // 移出内容区后调用 ShowCursor(TRUE) 恢复，以保证标题栏和外部窗口光标正常。
                cursorEnforcer = new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (WindowHitTestHelper.isCursorOverContentArea(TITLE_BAR_HEIGHT)) {
                            if (cursorShown) {
                                WindowHitTestHelper.hideSystemCursor();
                                cursorShown = false;
                            }
                        } else if (!cursorShown) {
                            WindowHitTestHelper.showSystemCursor();
                            cursorShown = true;
                        }
                    }
                };
                cursorEnforcer.start();
            } else {
                scene.removeEventFilter(MouseEvent.ANY, cursorFilter);
                if (!cursorShown) {
                    WindowHitTestHelper.showSystemCursor();
                    cursorShown = true;
                }
                WindowHitTestHelper.disablePassthrough();
                if (cursorEnforcer != null) {
                    cursorEnforcer.stop();
                    cursorEnforcer = null;
                }
            }
            if (!now) {
                if (navMode) {
                    stage.setOpacity(NavigConfig.NAV_WINDOW_OPACITY);
                    opacitySlider.setValue(NavigConfig.NAV_WINDOW_OPACITY);
                } else {
                    stage.setOpacity(1.0);
                    opacitySlider.setValue(1.0);
                }
            } else if (navMode) {
                stage.setOpacity(NavigConfig.NAV_WINDOW_OPACITY);
                opacitySlider.setValue(NavigConfig.NAV_WINDOW_OPACITY);
            }
        });

        // --- 3. 导航模式按钮 ---
        navBtn = new Button();
        navBtn.setFocusTraversable(false);
        navBtn.getStyleClass().add("title-bar-btn");

        Node navIcon = createNavIcon();
        setSvgFill(navIcon, "-color-fg-muted");
        navBtn.setGraphic(navIcon);

        navBtn.setOnAction(_ -> CommandBus.dispatch(new ToggleNavModeCommand()));

        // --- 3.5 匹配开关按钮 ---
        /** 匹配开关按钮 */
        Button matchToggleBtn = new Button();
        matchToggleBtn.setFocusTraversable(false);
        matchToggleBtn.getStyleClass().add("title-bar-btn");

        Node matchIcon = createMatchToggleIcon();
        AppState appState = AppState.getInstance();
        // 响应式绑定：匹配状态变化时自动更新图标颜色
        appState.matchingEnabledProperty().addListener((_, _, now) ->
                setSvgFill(matchIcon, now ? "-color-accent-emphasis" : "-color-fg-muted"));
        // 初始同步当前匹配状态
        setSvgFill(matchIcon, appState.isMatchingEnabled() ? "-color-accent-emphasis" : "-color-fg-muted");
        matchToggleBtn.setGraphic(matchIcon);

        matchToggleBtn.setOnAction(_ ->
                CommandBus.dispatch(new ToggleMatchingCommand()));

        Button closeBtn = getCloseButton(stage);
        /** 最小化按钮 */
        Button minimizeBtn = createMinimizeButton();

        // --- 4. 调整子组件顺序：滑块在图标左侧，图标靠右锚定 ---
        getChildren().addAll(menuBtn, titleLabel, statusContainer, spacer, opacitySlider, ghostBtn, matchToggleBtn, navBtn, minimizeBtn, closeBtn);

        // 窗口移动逻辑 — WM_NCHITTEST 拦截控制穿透，此处无需 ghostMode 守卫
        setOnMousePressed(e -> {
            xOffset = e.getSceneX();
            yOffset = e.getSceneY();
        });
        setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - xOffset);
            stage.setY(e.getScreenY() - yOffset);
        });

        // 注册状态轮播事件
        AppEvents.subscribe(StatusEvent.class,
                event -> {
                    if (event.displayMode() == StatusEvent.DisplayMode.TOAST) return;
                    Platform.runLater(() -> updateStatus(event));
                });
        // 导航模式通过 ViewportState property 响应外部变更
        ViewportState.getInstance().navModeProperty().addListener((_, _, now) ->
                syncNavUi(now));
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
     * 同步导航模式 UI（图标、滑块、透明度）。
     * 来自用户点击（toggleNavMode 内部调用）或 ViewportState 外部变更。
     */
    private void syncNavUi(boolean enabled) {
        if (enabled == navMode) return;
        navMode = enabled;

        Node navGraphic = navBtn.getGraphic();
        setSvgFill(navGraphic, enabled ? "-color-accent-emphasis" : "-color-fg-muted");

        updateSliderVisibility();
        opacitySlider.setDisable(!ghostMode && !enabled);

        if (enabled) {
            stageRef.setOpacity(NavigConfig.NAV_WINDOW_OPACITY);
            opacitySlider.setValue(NavigConfig.NAV_WINDOW_OPACITY);
        } else if (!ghostMode) {
            stageRef.setOpacity(1.0);
            opacitySlider.setValue(1.0);
        }
    }

    // ============================================================
    // 状态轮播
    // ============================================================

    /**
     * 更新标题栏内联状态文本，附带从下至上的滚动动画效果。
     * 每个新状态文本从下方滚入，旧状态从上方滚出。
     */
    public void updateStatus(StatusEvent event) {
        if (event == null) return;
        if (event.message() == null || event.message().isBlank()) {
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
            statusLabel.setText(event.message());
            statusLabel.setStyle(newStyle);
            return;
        }

        // 文本未变 → 忽略
        if (event.message().equals(statusLabel.getText())) return;

        // 从下至上滚动动画：旧文本向上滚出，新文本从下方滚入
        // Step 1: 旧文本向上平移移出 clip
        TranslateTransition exit = getTranslateTransition(event, newStyle);
        exit.play();
    }

    private TranslateTransition getTranslateTransition(StatusEvent event, String newStyle) {
        TranslateTransition exit = new TranslateTransition(Duration.millis(120), statusLabel);
        exit.setToY(-STATUS_H);
        exit.setOnFinished(_ -> {
            // Step 2: 在 clip 外更换文本 + 重置到下方
            statusLabel.setText(event.message());
            statusLabel.setStyle(newStyle);
            statusLabel.setTranslateY(STATUS_H);

            // Step 3: 新文本向上平移进入 clip
            TranslateTransition enter = new TranslateTransition(Duration.millis(120), statusLabel);
            enter.setToY(0);
            enter.play();
        });
        return exit;
    }

    /**
     * 幽灵模式或导航模式激活时显示透明度滑块，否则隐藏。
     * 保持 managed=true 确保布局位置不丢失。
     */
    private void updateSliderVisibility() {
        boolean show = ghostMode || navMode;
        opacitySlider.setVisible(show);
    }

    private Button createMinimizeButton() {
        SVGPath minimizeIcon = new SVGPath();
        minimizeIcon.setContent("M2 10 L14 10");
        minimizeIcon.setStyle("-fx-stroke: -color-fg-muted; -fx-stroke-width: 2; -fx-stroke-line-cap: round;");
        StackPane minimizeGraphic = new StackPane(minimizeIcon);
        minimizeGraphic.setPrefSize(20, 20);
        minimizeGraphic.setMinSize(20, 20);
        minimizeGraphic.setMaxSize(20, 20);

        Button btn = new Button();
        btn.setFocusTraversable(false);
        btn.setGraphic(minimizeGraphic);
        btn.getStyleClass().add("title-bar-btn");

        btn.setOnMouseEntered(_ -> minimizeIcon.setStyle("-fx-stroke: -color-accent-emphasis; -fx-stroke-width: 2; -fx-stroke-line-cap: round;"));
        btn.setOnMouseExited(_ -> minimizeIcon.setStyle("-fx-stroke: -color-fg-muted; -fx-stroke-width: 2; -fx-stroke-line-cap: round;"));
        btn.setOnAction(_ -> {
            if (minimizeHandler != null) {
                minimizeHandler.run();
            }
        });
        return btn;
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
        closeBtn.setFocusTraversable(false);
        closeBtn.setGraphic(closeGraphic);
        closeBtn.getStyleClass().add("title-bar-btn");

        closeBtn.setOnMouseEntered(_ -> closeIcon.setStyle("-fx-stroke: -color-danger-emphasis; -fx-stroke-width: 2; -fx-stroke-line-cap: round;"));
        closeBtn.setOnMouseExited(_ -> closeIcon.setStyle("-fx-stroke: -color-fg-muted; -fx-stroke-width: 2; -fx-stroke-line-cap: round;"));

        // ============ 【关闭确认弹窗 — 使用模态 Stage 确保置顶】 ============
        closeBtn.setOnAction(_ -> ModalConfirmDialog.showModalConfirmDialog(
                stage,
                "确认退出",
                "确定要关闭程序吗？\n所有识别与渲染服务将会停止运行。",
                "立即退出",
                Platform::exit,
                () -> { }
        ));
        // ====================================================================

        return closeBtn;
    }

    @ThreadSafe
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