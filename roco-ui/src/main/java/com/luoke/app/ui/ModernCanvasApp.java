package com.luoke.app.ui;

import atlantafx.base.theme.*;
import com.luoke.app.capture.CaptureService;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.impl.MapMatcherProcessor;
import com.luoke.app.capture.processor.impl.OcrProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.*;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.PlayerPositionEvent;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.impl.ResourceGrayHook;
import com.luoke.app.hook.impl.UiResponseHook;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.RenderLoop;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.WindowManager;
import com.luoke.app.utils.MultiResMapCache;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class ModernCanvasApp extends Application {

    private static final int RESIZE_MARGIN = 8;
    private static final String UNIFIED_BLUE = "#00BFFF";

    private static StackPane rootStack;
    private final WindowManager windowManager = new WindowManager(RESIZE_MARGIN);
    private final UiAnimator uiAnimator = new UiAnimator();

    private RenderLoop renderLoop;
    private LoadingOverlay globalLoading;
    private CaptureService mainCaptureService;
    private volatile boolean isAppRunning = true;
    private volatile Image playerIconImage;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() throws Exception {
        super.init();
    }

    /**
     * 所有可用主题
     */
    public static String[] getAvailableThemes() {
        return new String[]{"PrimerDark", "PrimerLight", "NordDark", "NordLight",
                "CupertinoDark", "CupertinoLight", "Dracula"};
    }

    // ==================== 主题管理 ====================

    /**
     * 应用指定主题，失败回退 PrimerDark
     */
    public static void applyTheme(String name) {
        Theme theme = switch (name) {
            case "PrimerLight" -> new PrimerLight();
            case "NordDark" -> new NordDark();
            case "NordLight" -> new NordLight();
            case "CupertinoDark" -> new CupertinoDark();
            case "CupertinoLight" -> new CupertinoLight();
            case "Dracula" -> new Dracula();
            default -> new PrimerDark();
        };
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
    }

    /**
     * 运行时切换主题并持久化
     */
    public static void switchTheme(String name) {
        AppConfig.THEME = name;
        AppConfig.save();
        applyTheme(name);
    }

    @Override
    public void start(Stage primaryStage) {
        applyTheme(AppConfig.THEME);

        // 外层容器：透明背景 + 内边距，为阴影留出空间
        StackPane wrapper = new StackPane();
        wrapper.setBackground(Background.EMPTY);
        wrapper.setPadding(new Insets(15));

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 30, 0, 0, 15);");

        // 圆角裁剪：确保所有子节点（侧边栏等）不会溢出直角挡住圆角
        Rectangle rootClip = new Rectangle();
        rootClip.widthProperty().bind(rootStack.widthProperty());
        rootClip.heightProperty().bind(rootStack.heightProperty());
        rootClip.setArcWidth(24);
        rootClip.setArcHeight(24);
        rootStack.setClip(rootClip);

        wrapper.getChildren().add(rootStack);

        globalLoading = new LoadingOverlay(null);
        rootStack.getChildren().add(globalLoading);
        // 注册事件分发逻辑
        HookRegistry.INSTANCE.register(new UiResponseHook(rootStack, globalLoading));

        Scene scene = new Scene(wrapper, 1100, 800);
        scene.setFill(Color.TRANSPARENT);

        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(scene);
        primaryStage.show();


        checkAndInitResourcesAsync(primaryStage);
    }

    private void checkAndInitResourcesAsync(Stage primaryStage) {
        Thread.ofVirtual().start(() -> {
            try {
                // 1. 初始化 OCR 引擎
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);

                File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
                if (initFile.exists()) {
                    // 分步骤发布进度，增强用户感官
                    publishInitStep(0.2, "初始化逻辑处理器...");
                    HookRegistry.INSTANCE.registers(new ResourceGrayHook());

                    publishInitStep(0.4, "正在载入大地图纹理...");
                    initBigMapResource();

                    publishInitStep(0.7, "构建坐标索引系统...");
                    ResourcePointContext.getInstance().loadAndInit();

                    publishInitStep(1.0, "核心引擎已就绪");

                    // 确保渲染主循环在 UI 构建前不被触发
                    Platform.runLater(() -> buildMainUI(primaryStage));
                } else {
                    handleFirstRun(primaryStage, initFile);
                }
            } catch (Exception e) {
                log.error("环境初始化致命异常: ", e);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("核心服务启动失败: " + e.getMessage(), NotificationType.ERROR));
            }
        });
    }

    private void handleFirstRun(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            globalLoading.dispose();
            rootStack.getChildren().remove(globalLoading);
            DialogUtils.showSimpleDialog(rootStack, "初始化配置",
                    "检测到本地资源不完整，是否立即从 WIKI 同步最新地图数据？", "立即同步", false,
                    () -> startResourceDownloadAsync(primaryStage, initFile));
        });
    }

    private void startResourceDownloadAsync(Stage primaryStage, File initFile) {
        // 1. 立即在 UI 线程准备好 Overlay
        Platform.runLater(() -> {
            // 先清理旧的 overlay，确保干净的层级
            rootStack.getChildren().stream()
                    .filter(node -> node instanceof LoadingOverlay)
                    .forEach(node -> ((LoadingOverlay) node).dispose());
            rootStack.getChildren().removeIf(node -> node instanceof LoadingOverlay);

            LoadingOverlay downloadOverlay = new LoadingOverlay(() -> {
                MapDownloader.stopDownload();
                IconDownloader.stopDownload();
                Platform.runLater(() -> checkAndInitResourcesAsync(primaryStage));
            });

            rootStack.getChildren().add(downloadOverlay);

            // 2. 绑定进度回调
            DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
                double progress = total <= 0 ? 0 : (double) completed / total;
                // 确保使用 Hook 发布，因为 UiResponseHook 在监听这个
                HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress,
                        String.format("%s (%d/%d)", DownloadProgressContext.getInstance().getStatusText(), completed, total)));
            });

            // 3. 进度绑定完成后，再启动下载线程
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("开始下载地图资源...");
                    MapResourceUpdater.updateAllResources();
                    if (initFile.createNewFile()) {
                        log.info("资源下载完成，重新进入初始化自检");
                        checkAndInitResourcesAsync(primaryStage);
                    }
                } catch (Exception e) {
                    log.error("地图资源下载异常", e);
                    HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                            new StatusEvent("资源同步中断，请检查网络", NotificationType.ERROR));
                }
            });
        });
    }

    private void buildMainUI(Stage primaryStage) {
        try {
            globalLoading.dispose();
            rootStack.getChildren().clear();

            // 1. 输入与 hover 层 — 处理事件 + hover 高亮
            InteractiveCanvas interactiveCanvas = new InteractiveCanvas();
            interactiveCanvas.setPickOnBounds(true);

            // 1.1 地图背景 ImageView — GPU viewport 平移
            ImageView mapView = new ImageView();
            mapView.setPreserveRatio(false);
            mapView.setSmooth(false);
            mapView.setPickOnBounds(false);
            mapView.setManaged(false);

            // 1.2 静态层 Canvas — 图标 + 路线，GPU translate 平移。mouseTransparent 避免拦截事件
            Canvas staticCanvas = new Canvas();
            staticCanvas.setMouseTransparent(true);
            staticCanvas.setPickOnBounds(false);

            // 1.3 玩家图标 ImageView — GPU transform，零纹理上传
            ImageView playerView = new ImageView();
            playerView.setMouseTransparent(true);
            playerView.setPickOnBounds(false);
            playerView.setManaged(false);
            playerView.setFitWidth(72);
            playerView.setFitHeight(72);
            if (playerIconImage != null) {
                playerView.setImage(playerIconImage);
            }

            // 1.4 4 层渲染器
            renderLoop = new RenderLoop(mapView, staticCanvas, playerView, interactiveCanvas);
            interactiveCanvas.setRenderLoop(renderLoop);
            if (playerIconImage != null) {
                renderLoop.setPlayerImage(playerIconImage);
            }

            StackPane canvasContainer = new StackPane(mapView, staticCanvas, playerView, interactiveCanvas);
            canvasContainer.setPickOnBounds(false);
            StackPane.setAlignment(mapView, Pos.CENTER);
            StackPane.setAlignment(staticCanvas, Pos.CENTER);
            StackPane.setAlignment(playerView, Pos.CENTER);
            StackPane.setAlignment(interactiveCanvas, Pos.CENTER);

            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(canvasContainer.widthProperty());
            clip.heightProperty().bind(canvasContainer.heightProperty());
            canvasContainer.setClip(clip);

            mapView.fitWidthProperty().bind(canvasContainer.widthProperty());
            mapView.fitHeightProperty().bind(canvasContainer.heightProperty());
            staticCanvas.widthProperty().bind(canvasContainer.widthProperty());
            staticCanvas.heightProperty().bind(canvasContainer.heightProperty());
            interactiveCanvas.widthProperty().bind(canvasContainer.widthProperty());
            interactiveCanvas.heightProperty().bind(canvasContainer.heightProperty());

            // 2. 覆盖层组件初始化 (单例)
            StatsOverlay statsOverlay = StatsOverlay.getInstance();
            ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();

            // 3. 布局组装
            Sidebar sidebar = new Sidebar();
            sidebar.setTranslateX(-240); // 初始完全隐藏
            AnchorPane sidebarContainer = new AnchorPane(sidebar);
            sidebarContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(sidebar, 45.0);
            AnchorPane.setBottomAnchor(sidebar, 0.0);

            AnchorPane panelAnchor = new AnchorPane(statsOverlay, resourcePanel);
            panelAnchor.setPickOnBounds(false);
            AnchorPane.setTopAnchor(statsOverlay, 45.0);
            AnchorPane.setRightAnchor(statsOverlay, 20.0);
            AnchorPane.setTopAnchor(resourcePanel, 90.0);
            AnchorPane.setRightAnchor(resourcePanel, 20.0);

            FloatToolbox floatToolbox = new FloatToolbox(resourcePanel, UNIFIED_BLUE);
            AnchorPane floatContainer = new AnchorPane(floatToolbox);
            floatContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(floatToolbox, 90.0);
            AnchorPane.setLeftAnchor(floatToolbox, 20.0);

            Button menuBtn = createMenuButton();
            TitleBar titleBar = TitleBar.getInstance(primaryStage, menuBtn,
                    canvasContainer, sidebarContainer, panelAnchor, floatContainer);

            VBox uiOverlay = new VBox(titleBar);
            uiOverlay.setPickOnBounds(false);

            AnchorPane resizeLayer = new AnchorPane();
            resizeLayer.setPickOnBounds(false);
            windowManager.install(primaryStage, resizeLayer);

            // 4. 层级挂载 → size listener 触发 → autoFitMap → markDirty → 首次渲染
            rootStack.getChildren().addAll(canvasContainer, sidebarContainer, panelAnchor, floatContainer, uiOverlay, resizeLayer);

            // 5. 交互行为绑定
            uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);
            interactiveCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY && uiAnimator.isSidebarVisible()) {
                    menuBtn.fire();
                }
            });

            // 6. 响应式渲染触发链
            // 6.1 玩家位置更新 → 仅更新 playerView (GPU transform) + 跟随模式时背景平移
            HookRegistry.INSTANCE.register(new AbstractGenericHook<PlayerPositionEvent>() {
                @Override
                public java.util.Set<HookEventType> supportedEvents() {
                    return java.util.Set.of(HookEventType.PLAYER_UPDATE);
                }
                @Override
                public void onEvent(HookEventType eventType, PlayerPositionEvent data) {
                    if (renderLoop != null) {
                        if (CameraContext.getInstance().isFollowMode()) {
                            renderLoop.markDirtyBg();    // 跟随：背景 GPU 平移
                        }
                        renderLoop.markDirtyOverlay();   // 玩家：ImageView transform
                    }
                }
            });
            // 6.2 跟随模式切换 → 重绘
            CameraContext.getInstance().followModeProperty().addListener((obs, old, val) -> {
                if (renderLoop != null) renderLoop.markDirty();
            });
            // 6.3 窗口恢复 → 重绘
            primaryStage.iconifiedProperty().addListener((obs, old, minimized) -> {
                if (!minimized && renderLoop != null) renderLoop.markDirty();
            });

            // 7. 开启核心服务
            startCaptureWatchdog();

            log.info("主界面构建完成，响应式渲染已就绪");
        } catch (Exception e) {
            log.error("UI渲染过程崩溃: ", e);
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent("界面加载失败，请尝试重启", NotificationType.ERROR));
        }
    }

    private void startCaptureWatchdog() {
        Thread.ofVirtual().start(() -> {
            while (isAppRunning) {
                try {
                    // 只有在服务未运行或已失效时才尝试重连
                    if (mainCaptureService == null || mainCaptureService.getId() <= 0) {
                        mainCaptureService = new CaptureService("洛克王国：世界");
                        if (mainCaptureService.tryConnect()) {
                            setupCaptureProcessors();
                        } else {
                            log.info("未找到游戏窗口，5秒后重试...");
                        }
                    }
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("采集监控守护进程异常", e);
                }
            }
        });
    }

    private void setupCaptureProcessors() {
        if (mainCaptureService == null) return;

        MapMatcherProcessor siftProcessor = new MapMatcherProcessor(0);
        OcrProcessor ocrProcessor = new OcrProcessor(1);
        mainCaptureService.addProcessors(siftProcessor, ocrProcessor);

        List<ROIData> rois = new ArrayList<>();
        rois.add(siftProcessor.getRoi());
        rois.add(ocrProcessor.getRoi());

        // 批量下发 ROI 配置到 C++ 核心
        mainCaptureService.setRois(ROIData.createContiguousArray(rois));
        log.info("采集处理器配置完成");
    }

    private void initBigMapResource() throws Exception {
        String mapPath = ResourceConfigContext.getShowMap();
        String cachePath = mapPath + ".raw";
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            MultiResMapCache cache = MultiResMapCache.getInstance();
            cache.ensureLevels(is, cachePath);

            int fullW = cache.getFullWidth();
            int fullH = cache.getFullHeight();

            // 占位 Image 仅用于 null 检查，实际渲染由 RenderLoop 通过 cropViewport() 完成
            // init() 会用 image 尺寸覆盖 mapWidth/mapHeight，事后手动修正
            javafx.scene.image.WritableImage placeholder = new javafx.scene.image.WritableImage(1, 1);
            MapContext mm = MapContext.getInstance();
            mm.initWithKey(placeholder, fullW, fullH, "G", null);
            mm.setMapWidth(fullW);
            mm.setMapHeight(fullH);
        }
        try (InputStream is = ResourceUtils.getResourceStream(ResourceConfigContext.getPlayerIcon())) {
            playerIconImage = new Image(is);
        }
    }

    private void publishInitStep(double progress, String message) {
        HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress, message));
    }

    private Button createMenuButton() {
        Button btn = new Button();
        SVGPath icon = new SVGPath();
        icon.setContent("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
        icon.setStyle("-fx-fill: -color-fg-default;");
        btn.setGraphic(icon);
        btn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);
        return btn;
    }

    @Override
    public void stop() {
        log.info("正在关闭程序并清理资源...");
        isAppRunning = false;

        // 1. 停止事件总线
        HookRegistry.INSTANCE.destroy();

        // 2. 停止渲染循环
        if (renderLoop != null) {
            renderLoop.dispose();
        }

        // 3. 停止采集服务 (释放 Windows WGC 资源)
        if (mainCaptureService != null) {
            mainCaptureService.stop();
        }

        // 4. 清理 OCR 线程池
        OcrAsyncManager.getInstance().close();

        Platform.exit();
    }
}