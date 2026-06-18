package io.github.kedaya0209.roco.app.ui.service.ui;

import atlantafx.base.controls.ModalPane;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.ui.component.canvas.InteractiveCanvas;
import io.github.kedaya0209.roco.app.ui.component.overlay.ResourceCounterPanel;
import io.github.kedaya0209.roco.app.ui.component.overlay.StatsOverlay;
import io.github.kedaya0209.roco.app.ui.component.sidebar.Sidebar;
import io.github.kedaya0209.roco.app.ui.component.sidebar.UiAnimator;
import io.github.kedaya0209.roco.app.ui.component.widget.FloatToolbox;
import io.github.kedaya0209.roco.app.ui.component.widget.TitleBar;
import io.github.kedaya0209.roco.app.ui.component.widget.VersionSelectorPanel;
import io.github.kedaya0209.roco.app.ui.render.MapRenderer;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.animation.FadeTransition;
import javafx.util.Duration;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;

/**
 * 主界面 UI 组装器。
 * 纯静态工具类，无业务逻辑，仅负责 JavaFX 场景图构建。
 */
@Slf4j
@NotThreadSafe
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
        canvasContainer.setStyle("-fx-background-color: -color-bg-canvas;");
        canvasContainer.prefWidthProperty().bind(rootStack.widthProperty());
        canvasContainer.prefHeightProperty().bind(rootStack.heightProperty());

        // 地图渲染器
        MapRenderer renderer = new MapRenderer(canvasContainer);
        MapContext mapCtx = MapContext.getInstance();
        CompositeMapMetadata metadata = mapCtx.getMultiMapMetadata();
        renderer.init((int) mapCtx.getMapWidth(),
                (int) mapCtx.getMapHeight(), metadata);

        // 玩家图标
        try {
            // 全分辨率加载，裁剪空白在 PlayerRenderer.setPlayerImage 中处理
            Image playerIcon = new Image(ResourceUtils.getResourceStream(
                    ResourceConfigContext.getPlayerIcon()));
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

        // 覆盖层组件
        StatsOverlay statsOverlay = StatsOverlay.getInstance();
        ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();

        // 侧边栏（ModalPane 抽屉式遮罩层，在标题栏下方展开）
        Sidebar sidebar = new Sidebar();
        sidebar.setRootStack(rootStack);
        sidebar.setPrefWidth(284);
        sidebar.setMaxWidth(284);
        sidebar.setPadding(new Insets(45, 12, 0, 12));
        ModalPane sidebarModal = new ModalPane(-10);
        sidebarModal.setAlignment(Pos.CENTER_LEFT);
        sidebarModal.setPadding(new Insets(0, 0, 0, 0));
        sidebarModal.usePredefinedTransitionFactories(Side.LEFT);

        // 初始化时鼠标穿透，侧边栏关闭时保持穿透以避免拦截标题栏点击
        sidebarModal.setMouseTransparent(true);
        // 侧边栏打开时地图毛玻璃效果
        // AtomicBoolean 确保首次 showing=true 之后才执行效果，防止初始化阶段误触发
        AtomicBoolean blurReady = new AtomicBoolean(false);
        sidebarModal.displayProperty().addListener((_, _, showing) -> {
            sidebarModal.setMouseTransparent(!showing);
            if (!blurReady.get()) {
                if (showing) {
                    blurReady.set(true);
                } else {
                    return; // 初始化阶段 showing=false → 跳过
                }
                // 首次 showing=true → 初始化标记 + 继续执行设置 blur
            }
            if (showing) {
                canvasContainer.setEffect(new GaussianBlur(6));
            } else {
                FadeTransition ft = new FadeTransition(
                        Duration.millis(200), canvasContainer);
                ft.setToValue(1.0);
                ft.setOnFinished(_ -> canvasContainer.setEffect(null));
                ft.play();
            }
        });

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
                canvasContainer, panelAnchor, floatContainer, sidebarModal);

        // 侧边栏切换
        uiAnimator.setupSidebarToggle(menuBtn, sidebarModal, sidebar);
        sidebar.setAnimator(uiAnimator);

        VBox uiOverlay = new VBox(titleBar);
        uiOverlay.setPickOnBounds(false);
        uiOverlay.setViewOrder(-15); // 永远在侧边栏 ModalPane 之上
        uiOverlay.setMinHeight(40);
        uiOverlay.setAlignment(Pos.TOP_CENTER);

        AnchorPane resizeLayer = new AnchorPane();
        resizeLayer.setPickOnBounds(false);
        windowManager.setMaxSize(primaryStage.getWidth(), primaryStage.getHeight());
        windowManager.install(primaryStage, resizeLayer);

        // 层级：画布 → 右侧面板 → 浮动工具栏 → 缩放 → 侧边栏遮罩 → 标题栏（最上层）
        rootStack.getChildren().addAll(canvasContainer,
                panelAnchor, floatContainer, resizeLayer, sidebarModal, uiOverlay);

        // 版本选择覆盖层
        VersionSelectorPanel versionPanel = new VersionSelectorPanel(rootStack);
        sidebar.setOnShowVersionSelector(versionPanel::show);

        return new UiBuildResult(renderer, canvasContainer, sidebar, floatToolbox);
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
                baseStyle + "-fx-background-color: -color-bg-subtle;-fx-background-radius: 6px;"));
        btn.setOnMouseExited(e -> btn.setStyle(baseStyle));
        FxRippleUtil.install(btn);
        return btn;
    }

    /**
     * 主界面构建结果
     */
    @ThreadSafe
    public record UiBuildResult(MapRenderer renderer, Pane canvasContainer, Sidebar sidebar, FloatToolbox floatToolbox) {
    }
}
