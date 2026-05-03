package com.luoke.app.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.luoke.app.capture.CaptureService;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.impl.MapMatcherProcessor;
import com.luoke.app.capture.processor.impl.OcrProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.context.ResourceContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.HookRegistry;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.impl.ResourceGrayHook;
import com.luoke.app.hook.impl.UiResponseHook;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.PlayerRenderer;
import com.luoke.app.ui.render.RenderLoop;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.WindowManager;
import com.luoke.app.utils.MapRawCache;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
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

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() throws Exception {
        super.init();
        // 静态资源解压
        ResourceUtils.extractAll();
    }

    @Override
    public void start(Stage primaryStage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: #1e1e1e;");

        globalLoading = new LoadingOverlay(null);
        rootStack.getChildren().add(globalLoading);
        // 注册事件分发逻辑
        HookRegistry.INSTANCE.register(new UiResponseHook(rootStack, globalLoading));

        Scene scene = new Scene(rootStack, 1100, 800);
        scene.setFill(Color.TRANSPARENT);

        primaryStage.initStyle(StageStyle.UNDECORATED);
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
                        new StatusEvent("核心服务启动失败: " + e.getMessage(), NotificationToast.Type.ERROR));
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
                            new StatusEvent("资源同步中断，请检查网络", NotificationToast.Type.ERROR));
                }
            });
        });
    }

    private void buildMainUI(Stage primaryStage) {
        try {
            globalLoading.dispose();
            rootStack.getChildren().clear();

            // 1. 画布与视口控制
            InteractiveCanvas interactiveCanvas = new InteractiveCanvas();
            interactiveCanvas.setPickOnBounds(true);

            StackPane canvasContainer = new StackPane(interactiveCanvas);
            canvasContainer.setPickOnBounds(false);
            StackPane.setAlignment(interactiveCanvas, Pos.CENTER);

            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(canvasContainer.widthProperty());
            clip.heightProperty().bind(canvasContainer.heightProperty());
            canvasContainer.setClip(clip);

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
            TitleBar titleBar = TitleBar.getInstance(primaryStage, menuBtn, UNIFIED_BLUE,
                    canvasContainer, sidebarContainer, panelAnchor, floatContainer);

            VBox uiOverlay = new VBox(titleBar);
            uiOverlay.setPickOnBounds(false);

            AnchorPane resizeLayer = new AnchorPane();
            resizeLayer.setPickOnBounds(false);
            windowManager.install(primaryStage, resizeLayer);

            // 4. 层级挂载
            rootStack.getChildren().addAll(canvasContainer, sidebarContainer, panelAnchor, floatContainer, uiOverlay, resizeLayer);

            // 5. 交互行为绑定
            uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);
            interactiveCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                // 点击地图空白处时，如果侧边栏打开则自动收起
                if (e.getButton() == MouseButton.PRIMARY && uiAnimator.isSidebarVisible()) {
                    menuBtn.fire();
                }
            });

            // 6. 开启核心服务
            startCaptureWatchdog();

            // 确保 RenderLoop 拥有合法的上下文后再启动
            if (renderLoop != null) renderLoop.stop();
            renderLoop = new RenderLoop(interactiveCanvas.getGraphicsContext2D());
            renderLoop.start();

            log.info("主界面构建完成，渲染循环已启动");
        } catch (Exception e) {
            log.error("UI渲染过程崩溃: ", e);
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent("界面加载失败，请尝试重启", NotificationToast.Type.ERROR));
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
        String mapPath = ResourceContext.getShowMap();
        String cachePath = mapPath + ".raw";
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            if (is == null) throw new RuntimeException("无法读取地图纹理资源");
            MapRawCache.MappedImage mapped = MapRawCache.loadOrCreate(is, cachePath);
            MapContext.getInstance().initWithKey(mapped.image(),
                    mapped.width(), mapped.height(), "G", mapped.mappedBuffer());
        }
        try (InputStream is = ResourceUtils.getResourceStream(ResourceContext.getPlayerIcon())) {
            if (is != null) {
                PlayerRenderer.getInstance().init(new Image(is));
            }
        }
    }

    private void publishInitStep(double progress, String message) {
        HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress, message));
    }

    private Button createMenuButton() {
        Button btn = new Button();
        SVGPath icon = new SVGPath();
        icon.setContent("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
        icon.setFill(Color.WHITE);
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

        // 2. 停止渲染循环 (避免操作已销毁的 GC)
        if (renderLoop != null) {
            renderLoop.stop();
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