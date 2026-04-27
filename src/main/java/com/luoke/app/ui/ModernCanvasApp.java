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
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.hook.HookRegistry;
import com.luoke.app.hook.impl.ResourceGrayHook;
import com.luoke.app.hook.multicast.HookMulticaster;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.PlayerRenderer;
import com.luoke.app.ui.render.RenderLoop;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.WindowManager;
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

@Slf4j
@NoArgsConstructor
public class ModernCanvasApp extends Application {

    private static final int RESIZE_MARGIN = 8;
    private static final String UNIFIED_BLUE = "#00BFFF";

    private static StackPane rootStack;
    private final WindowManager windowManager = new WindowManager(RESIZE_MARGIN);

    private RenderLoop renderLoop;
    private final UiAnimator uiAnimator = new UiAnimator();
    private LoadingOverlay globalLoading;

    // 🔥 核心服务句柄
    private CaptureService mainCaptureService;
    private volatile boolean isAppRunning = true;

    public static void main(String[] args) {
        launch(args);
    }

    public static void notify(String message, NotificationToast.Type type) {
        if (rootStack != null) {
            Platform.runLater(() -> NotificationToast.show(rootStack, message, type));
        }
    }

    @Override
    public void init() throws Exception {
        super.init();
        ResourceUtils.extractAll();
    }

    @Override
    public void start(Stage primaryStage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: #1e1e1e;");

        globalLoading = new LoadingOverlay(null);
        globalLoading.updateProgress(0.1, "正在检查运行环境...");
        rootStack.getChildren().add(globalLoading);

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
                File rootDir = ResourceUtils.getExternalFile(AppConfig.SOURCE_ROOT_DIR);
                if (!rootDir.exists()) rootDir.mkdirs();
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);
                File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
                if (initFile.exists()) {
                    updateLoadingProgress(0.3, "正在初始化钩子组件...");
                    registerHook();
                    updateLoadingProgress(0.5, "正在载入大地图资源...");
                    initBigMapResource();
                    updateLoadingProgress(0.8, "正在载入坐标索引...");
                    ResourcePointContext.getInstance().loadAndInit();
                    updateLoadingProgress(1.0, "准备就绪");
                    Platform.runLater(() -> buildMainUI(primaryStage));
                } else {
                    Platform.runLater(() -> {
                        rootStack.getChildren().remove(globalLoading);
                        DialogUtils.showSimpleDialog(rootStack, "首次启动", "程序需要下载必要的地图资源。", "开始下载", false,
                                () -> startResourceDownloadAsync(primaryStage, initFile));
                    });
                }
            } catch (Exception e) {
                log.error("资源自检异常", e);
                showErrorAndExit("环境检查失败，请检查文件权限。或删除resource目录");
            }
        });
    }

    private void updateLoadingProgress(double progress, String text) {
        Platform.runLater(() -> {
            if (globalLoading != null) globalLoading.updateProgress(progress, text);
        });
    }

    private void startResourceDownloadAsync(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            rootStack.getChildren().clear();
            LoadingOverlay downloadOverlay = new LoadingOverlay(() -> {
                MapDownloader.stopDownload();
                IconDownloader.stopDownload();
                Platform.runLater(() -> checkAndInitResourcesAsync(primaryStage));
            });
            rootStack.getChildren().add(downloadOverlay);

            DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
                Platform.runLater(() -> {
                    double progress = total == 0 ? 0 : (double) completed / total;
                    downloadOverlay.updateProgress(progress, String.format("%s (%d/%d)",
                            DownloadProgressContext.getInstance().getStatusText(), completed, total));
                });
            });
        });

        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                initFile.createNewFile();
                registerHook();
                initBigMapResource();
                ResourcePointContext.getInstance().loadAndInit();
                Platform.runLater(() -> buildMainUI(primaryStage));
            } catch (Exception e) {
                log.error("资源同步失败", e);
                showErrorAndExit("资源下载流程中断。");
            }
        });
    }

    private void buildMainUI(Stage primaryStage) {
        try {
            rootStack.getChildren().clear();

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

            Sidebar sidebar = new Sidebar();
            sidebar.setTranslateX(-220);
            AnchorPane sidebarContainer = new AnchorPane(sidebar);
            sidebarContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(sidebar, 45.0);
            AnchorPane.setBottomAnchor(sidebar, 0.0);

            StatsOverlay statsOverlay = StatsOverlay.getInstance();
            AnchorPane.setTopAnchor(statsOverlay, 40.0);
            AnchorPane.setRightAnchor(statsOverlay, 20.0);

            ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();
            AnchorPane panelAnchor = new AnchorPane(statsOverlay, resourcePanel);
            panelAnchor.setPickOnBounds(false);
            AnchorPane.setTopAnchor(resourcePanel, 80.0);
            AnchorPane.setRightAnchor(resourcePanel, 20.0);
            FloatToolbox floatToolbox = new FloatToolbox(resourcePanel, UNIFIED_BLUE);


            AnchorPane floatContainer = new AnchorPane(floatToolbox);
            floatContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(floatToolbox, 80.0);
            AnchorPane.setLeftAnchor(floatToolbox, 15.0);

            Button menuBtn = createMenuButton();
            TitleBar titleBar = TitleBar.getInstance(primaryStage, menuBtn, UNIFIED_BLUE,
                    canvasContainer, sidebarContainer, panelAnchor, floatContainer);

            VBox uiOverlay = new VBox(titleBar);
            uiOverlay.setPickOnBounds(false);

            AnchorPane resizeLayer = new AnchorPane();
            resizeLayer.setPickOnBounds(false);
            resizeLayer.setMouseTransparent(false);
            windowManager.install(primaryStage, resizeLayer);

            rootStack.getChildren().addAll(
                    canvasContainer,
                    sidebarContainer,
                    panelAnchor,
                    floatContainer,
                    uiOverlay,
                    resizeLayer
            );

            uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);
            interactiveCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY && uiAnimator.isSidebarVisible()) menuBtn.fire();
            });

            // 🔥 启动自动重连监控守护进程 (替代旧版 WindowsMonitor)
            startCaptureWatchdog();

            renderLoop = new RenderLoop(interactiveCanvas.getGraphicsContext2D());
            renderLoop.start();

        } catch (Exception e) {
            log.error("UI 构建逻辑失败: ", e);
            showErrorAndExit("界面挂载异常。");
        }
    }

    /**
     * 🔥 采集服务守护进程：支持窗口关闭自动重连
     */
    private void startCaptureWatchdog() {
        Thread.ofVirtual().start(() -> {
            log.info("启动采集监控守护线程...");
            while (isAppRunning) {
                try {
                    // 1. 尝试连接窗口
                    mainCaptureService = new CaptureService("洛克王国：世界");
                    mainCaptureService.tryConnect();
                    if (mainCaptureService.getId() <= 0) {
                        // 窗口没开，心跳等待
                        log.info("未找到窗口");
                        Thread.sleep(5000);
                        continue;
                    }

                    // 2. 窗口连接成功，配置处理器
                    setupCaptureProcessors();
                    notify("已成功连接游戏窗口 (ID: " + mainCaptureService.getId() + ")", NotificationToast.Type.SUCCESS);

                    // 3. 阻塞观察：直到窗口失效
                    while (isAppRunning && mainCaptureService.getId() > 0) {
                        // 此处利用 Rust 内部心跳，若 Rust 侧检测到窗口关闭，id 会失效或 stop 会被触发
                        // 简单起见，我们每隔 2 秒确认一次服务状态
                        Thread.sleep(2000);
                    }

                    log.warn("检测到游戏窗口断开，准备重连...");
                    notify("游戏窗口已断开，正在等待重新运行...", NotificationToast.Type.ERROR);

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    log.error("监控守护线程异常", e);
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException ignored) {
                    }
                } finally {
                    if (mainCaptureService != null) {
                        mainCaptureService.stop();
                        mainCaptureService = null;
                    }
                }
            }
        });
    }

    /**
     * 配置 SIFT 和 OCR 处理器并同步 ROI
     */
    private void setupCaptureProcessors() {
        if (mainCaptureService == null) return;

        // 初始化处理器
        MapMatcherProcessor siftProcessor = new MapMatcherProcessor(0, (msg, color) -> {
        });
        OcrProcessor ocrProcessor = new OcrProcessor(1);
//        SaveImageProcessor saveImageProcessor = new SaveImageProcessor(0, "C:\\Users\\tangh\\Desktop\\test\\arrow");
        // 挂载
        mainCaptureService.addProcessors(siftProcessor, ocrProcessor);

        // 同步连续内存 ROI 数组 (JNA 核心逻辑)
        List<ROIData> rois = new ArrayList<>();
        rois.add(siftProcessor.getRoi());
        rois.add(ocrProcessor.getRoi());

        mainCaptureService.setRois(ROIData.createContiguousArray(rois));
    }

    private void updateStatus(String msg, Color color) {
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

    private void registerHook() {
        HookRegistry.INSTANCE.registers(new ResourceGrayHook());
    }

    private void initBigMapResource() throws Exception {
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            MapContext.getInstance().initWithKey(rawImage, rawImage.getWidth(), rawImage.getHeight(), "G");
        }
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.PLAYER_ICON_PATH)) {
            Image rawImage = new Image(is);
            PlayerRenderer.getInstance().init(rawImage);
        }
    }

    private void showErrorAndExit(String msg) {
        Platform.runLater(() -> {
            DialogUtils.showSimpleDialog(rootStack, "严重错误", msg, "退出", true, () -> {
                Platform.exit();
                System.exit(0);
            });
        });
    }

    @Override
    public void stop() {
        isAppRunning = false;
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(1500);
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignored) {
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            if (mainCaptureService != null) {
                mainCaptureService.stop();
            }
            if (renderLoop != null) renderLoop.stop();
            HookMulticaster.getInstance().shutdown();
            OcrAsyncManager.getInstance().close();
            Platform.exit();
            System.exit(0);
        } catch (Exception e) {
            Runtime.getRuntime().halt(1);
        }
    }
}