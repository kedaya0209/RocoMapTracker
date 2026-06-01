package io.github.kedaya0209.roco.app.ui.service.ui;

import atlantafx.base.controls.ModalPane;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.ui.component.*;
import io.github.kedaya0209.roco.app.ui.render.MapRenderer;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
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
                    RenderConfig.PLAYER_IMG_SIZE, RenderConfig.PLAYER_IMG_SIZE, true, true);
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
