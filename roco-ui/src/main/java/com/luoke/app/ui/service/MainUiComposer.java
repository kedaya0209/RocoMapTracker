package com.luoke.app.ui.service;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.MapRenderer;
import com.luoke.app.ui.util.FxRippleUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * 主界面 UI 组装器。
 * 纯静态工具类，无业务逻辑，仅负责 JavaFX 场景图构建。
 */
@Slf4j
public final class MainUiComposer {

    private MainUiComposer() {
    }

    /**
     * 构建主界面 UI。
     *
     * @return 包含 MapRenderer 和 canvasContainer 的结果记录
     */
    public static UiBuildResult buildMainUI(
            Stage primaryStage, StackPane rootStack,
            WindowManager windowManager, UiAnimator uiAnimator) {

        // 画布容器
        Pane canvasContainer = new Pane();
        canvasContainer.setStyle("-fx-background-color: #1a1a2e;");
        canvasContainer.prefWidthProperty().bind(rootStack.widthProperty());
        canvasContainer.prefHeightProperty().bind(rootStack.heightProperty());

        // 地图渲染器
        MapRenderer renderer = new MapRenderer(canvasContainer);
        renderer.init((int) MapContext.getInstance().getMapWidth(),
                (int) MapContext.getInstance().getMapHeight());

        // 玩家图标
        try {
            Image playerIcon = new Image(ResourceUtils.getResourceStream(
                    ResourceConfigContext.getPlayerIcon()),
                    AppConfig.PLAYER_IMG_SIZE, AppConfig.PLAYER_IMG_SIZE, true, false);
            if (!playerIcon.isError()) {
                renderer.setPlayerImage(playerIcon);
            }
        } catch (Exception e) {
            log.warn("玩家图标加载失败", e);
        }

        // InteractiveCanvas
        InteractiveCanvas interactiveCanvas = new InteractiveCanvas();
        interactiveCanvas.setMapRenderer(renderer);
        interactiveCanvas.setUiAnimator(uiAnimator);
        interactiveCanvas.widthProperty().bind(canvasContainer.widthProperty());
        interactiveCanvas.heightProperty().bind(canvasContainer.heightProperty());
        canvasContainer.getChildren().add(interactiveCanvas);

        // 视口大小变化 → 标记脏
        canvasContainer.widthProperty().addListener(e -> renderer.markDirty());
        canvasContainer.heightProperty().addListener(e -> renderer.markDirty());

        // 资源点变化 → 标记脏
        HookRegistry.INSTANCE.register(new AbstractGenericHook<>() {
            @Override
            public Set<HookEventType> supportedEvents() {
                return Set.of(HookEventType.RESOURCE_POINT_CHANGED);
            }

            @Override
            public void onEvent(HookEventType eventType, Object data) {
                Platform.runLater(renderer::markDirty);
            }
        });

        // 覆盖层组件
        StatsOverlay statsOverlay = StatsOverlay.getInstance();
        ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();

        // 侧边栏
        Sidebar sidebar = new Sidebar();
        sidebar.setTranslateX(-240);
        AnchorPane sidebarContainer = new AnchorPane(sidebar);
        sidebarContainer.setPickOnBounds(false);
        AnchorPane.setTopAnchor(sidebar, 45.0);
        AnchorPane.setBottomAnchor(sidebar, 0.0);

        // 右侧面板
        AnchorPane panelAnchor = new AnchorPane(statsOverlay, resourcePanel);
        panelAnchor.setPickOnBounds(false);
        AnchorPane.setTopAnchor(statsOverlay, 45.0);
        AnchorPane.setRightAnchor(statsOverlay, 20.0);
        AnchorPane.setTopAnchor(resourcePanel, 90.0);
        AnchorPane.setRightAnchor(resourcePanel, 20.0);

        // 浮动工具箱
        FloatToolbox floatToolbox = new FloatToolbox(resourcePanel, "#00BFFF");
        AnchorPane floatContainer = new AnchorPane(floatToolbox);
        floatContainer.setPickOnBounds(false);
        AnchorPane.setTopAnchor(floatToolbox, 90.0);
        AnchorPane.setLeftAnchor(floatToolbox, 20.0);

        // 菜单按钮
        Button menuBtn = createMenuButton();
        TitleBar titleBar = TitleBar.getInstance(primaryStage, menuBtn,
                canvasContainer, sidebarContainer, panelAnchor, floatContainer);

        VBox uiOverlay = new VBox(titleBar);
        uiOverlay.setPickOnBounds(false);

        AnchorPane resizeLayer = new AnchorPane();
        resizeLayer.setPickOnBounds(false);
        windowManager.setMaxSize(primaryStage.getWidth(), primaryStage.getHeight());
        windowManager.install(primaryStage, resizeLayer);

        // 层级
        rootStack.getChildren().addAll(canvasContainer, sidebarContainer,
                panelAnchor, floatContainer, uiOverlay, resizeLayer);

        // 侧边栏切换
        uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);

        return new UiBuildResult(renderer, canvasContainer);
    }

    private static Button createMenuButton() {
        Button btn = new Button();
        try {
            Node graphic = SvgManager.createIcon("/icon/rmt.svg", 20, null);
            btn.setGraphic(graphic);
            btn.setEffect(new DropShadow(3, 1, 1, Color.web("#000000", 0.25)));
        } catch (Exception e) {
            log.warn("菜单按钮 SVG 加载失败", e);
            SVGPath fallback = new SVGPath();
            fallback.setContent("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
            fallback.setStyle("-fx-fill: -color-fg-default;");
            btn.setGraphic(fallback);
        }
        String baseStyle = "-fx-background-color: transparent;"
                + "-fx-border-color: transparent;"
                + "-fx-padding: 6px;"
                + "-fx-cursor: hand;";
        btn.setStyle(baseStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(
                baseStyle + "-fx-background-color: -color-bg-subtle;" + "-fx-background-radius: 6px;"));
        btn.setOnMouseExited(e -> btn.setStyle(baseStyle));
        FxRippleUtil.install(btn);
        return btn;
    }

    /**
     * 主界面构建结果
     */
    public record UiBuildResult(MapRenderer renderer, Pane canvasContainer) {
    }
}
