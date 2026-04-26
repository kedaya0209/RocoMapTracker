package com.luoke.app.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.context.StatsContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.HookRegistry;
import com.luoke.app.hook.impl.RealOcrHook;
import com.luoke.app.hook.impl.ResourceGrayHook;
import com.luoke.app.hook.multicast.HookMulticaster;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.SiftMapMatcher;
import com.luoke.app.macher.minimap.MapTracker;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.CutterPlayerRenderer;
import com.luoke.app.ui.render.PlayerRenderer;
import com.luoke.app.ui.render.RenderLoop;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@NoArgsConstructor
public class ModernCanvasApp extends Application {

    // ====================== 【状态与单例管理 - 绝对完整】 ======================
    private static final MapTracker mapTracker = MapTracker.getInstance();
    private static final StatsContext stats = StatsContext.getInstance();
    private static final AtomicBoolean isMatcherReady = new AtomicBoolean(false);

    // UI 边缘配置
    private static final int RESIZE_MARGIN = 8;
    private static MapMatcher mapMatcher;
    private static WindowsMonitor windowsMonitor;

    // 全局容器
    private static StackPane rootStack;
    private RenderLoop renderLoop;
    private static final String UNIFIED_BLUE = "#00BFFF";
    private final UiAnimator uiAnimator = new UiAnimator();
    private final boolean isGhostMode = false;

    // 缩放偏移变量
    private double xOffset = 0;
    private double yOffset = 0;

    public static void main(String[] args) {
        launch(args);
    }

    public static void notify(String message, NotificationToast.Type type) {
        if (rootStack != null) {
            Platform.runLater(() -> NotificationToast.show(rootStack, message, type));
        }
    }

    private static void processFrame(Frame frame) {
        if (frame == null || !isMatcherReady.get()) return;
        stats.onFrameProcessed();
        try {
            HookMulticaster.getInstance().enqueue(HookEventType.FRAME_CAPTURED, frame);
            long t0 = System.currentTimeMillis();
            CaptureFrameRecord miniMap = mapTracker.getMiniMapImage(frame);
            stats.recordMapDetect(System.currentTimeMillis() - t0);

            if (miniMap == null) {
                updateStatus(AppConfig.STATUS_MINIMAP_NOT_FOUND, Color.RED);
                return;
            }

            CutterPlayerRenderer.getInstance().updateArrow(miniMap);
            long t1 = System.currentTimeMillis();
            double[][] corners = mapMatcher.match(miniMap.bytes(), miniMap.width(), miniMap.height());
            stats.recordMatch(System.currentTimeMillis() - t1);

            if (corners == null || corners.length < 3) {
                updateStatus(AppConfig.STATUS_MATCH_FAILED, Color.RED);
                return;
            }

            double[] center = MapMathUtil.getCentroid(corners);
            long t2 = System.currentTimeMillis();
            Player player;
            try (Mat mat = ImageUtil.convertToMat(miniMap)) {
                player = ArrowDetector.detectPlayer(mat);
            }
            stats.recordDirection(System.currentTimeMillis() - t2);

            if (!player.isFound()) {
                updateStatus(AppConfig.STATUS_PLAYER_NOT_FOUND, Color.ORANGE);
                return;
            }

            MapContext.getInstance().updatePlayerState(center[0], center[1], player.getAngle());
            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], AppConfig.COORDINATE_SMOOTH_FACTOR);
            updateStatus(AppConfig.STATUS_RUNNING, Color.LIGHTGREEN);

        } catch (Exception e) {
            log.error("帧处理过程发生严重异常: ", e);
        }
    }

    private static void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                log.info("开始后台载入 SIFT 匹配引擎...");
                mapMatcher = new SiftMapMatcher();
                mapMatcher.init(AppConfig.MAP_RESOURCE_PATH);
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);
                isMatcherReady.set(true);
                log.info("匹配引擎就绪，监听窗口状态...");
                try {
                    startCapture();
                } catch (Exception e) {
                    log.warn("初始截图监听启动失败: {}", e.getMessage());
                }
            } catch (Exception e) {
                log.error("SIFT 匹配引擎初始化失败", e);
            }
        });
    }

    private static void updateStatus(String msg, Color color) {
        // 更新 UI 状态文字逻辑
    }

    private static void startCapture() {
        windowsMonitor = new WindowsMonitor(AppConfig.TARGET_WINDOW_NAME);
        windowsMonitor.startMonitor(ModernCanvasApp::processFrame);
    }

    // ====================== 【生命周期：初始化】 ======================
    @Override
    public void init() throws Exception {
        super.init();
        log.info("生命周期 1: 提取本地资源文件");
        ResourceUtils.extractAll();
    }

    @Override
    public void start(Stage primaryStage) {
        log.info("生命周期 2: 启动 UI 壳子");
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: #1e1e1e;");

        Scene scene = new Scene(rootStack, 1100, 800);
        scene.setFill(Color.TRANSPARENT);

        makeResizable(primaryStage, scene);

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

                File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
                if (initFile.exists()) {
                    log.info("自检通过，开始载入重型地图资源（同步模式以防展示一角）");
                    // 【修正点：此处必须同步加载，确保 buildMainUI 时地图已载入内存】
                    registerHook();
                    initBigMapResource();
                    ResourcePointContext.getInstance().loadAndInit();

                    Platform.runLater(() -> buildMainUI(primaryStage));
                } else {
                    log.info("资源缺失，进入下载模式");
                    Platform.runLater(() -> {
                        DialogUtils.showSimpleDialog(
                                rootStack, "首次启动", "程序需要下载必要的地图资源。", "开始下载", false,
                                () -> startResourceDownloadAsync(primaryStage, initFile)
                        );
                    });
                }
            } catch (Exception e) {
                log.error("资源自检异常", e);
                showErrorAndExit("环境检查失败，请检查文件权限。");
            }
        });
    }

    private void startResourceDownloadAsync(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            rootStack.getChildren().clear();
            LoadingOverlay loadingOverlay = new LoadingOverlay(() -> {
                MapDownloader.stopDownload();
                IconDownloader.stopDownload();
                Platform.runLater(() -> {
                    rootStack.getChildren().clear();
                    checkAndInitResourcesAsync(primaryStage);
                });
            });
            rootStack.getChildren().add(loadingOverlay);

            DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
                Platform.runLater(() -> {
                    double progress = total == 0 ? 0 : (double) completed / total;
                    String statusText = DownloadProgressContext.getInstance().getStatusText();
                    loadingOverlay.updateProgress(progress, String.format("%s (%d/%d)", statusText, completed, total));
                });
            });
        });

        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                initFile.createNewFile();

                // 下载完成后立即初始化资源
                registerHook();
                initBigMapResource();
                ResourcePointContext.getInstance().loadAndInit();

                Platform.runLater(() -> {
                    rootStack.getChildren().clear();
                    buildMainUI(primaryStage);
                });
            } catch (Exception e) {
                log.error("资源同步失败", e);
                showErrorAndExit("资源下载流程中断。");
            }
        });
    }

    private void showErrorAndExit(String msg) {
        Platform.runLater(() -> {
            DialogUtils.showSimpleDialog(rootStack, "严重错误", msg, "退出", true, () -> {
                Platform.exit();
                System.exit(0);
            });
        });
    }

    // ====================== 【生命周期：业务 UI 渲染】 ======================
    private void buildMainUI(Stage primaryStage) {
        try {
            log.info("生命周期 3: 渲染业务 UI 树并绑定 Canvas");

            InteractiveCanvas interactiveCanvas = new InteractiveCanvas();
            interactiveCanvas.setPickOnBounds(true); // 保证地图可被点击拖拽

            // 【保留内边距修正逻辑】
            StackPane canvasContainer = new StackPane(interactiveCanvas);
            canvasContainer.setPadding(new Insets(RESIZE_MARGIN));
            canvasContainer.setPickOnBounds(false);
            StackPane.setAlignment(interactiveCanvas, Pos.CENTER);

            // 绑定：宽高减去边距，确保展示区域完美契合
            interactiveCanvas.widthProperty().bind(canvasContainer.widthProperty().subtract(RESIZE_MARGIN * 2));
            interactiveCanvas.heightProperty().bind(canvasContainer.heightProperty().subtract(RESIZE_MARGIN * 2));

            Sidebar sidebar = new Sidebar();
            sidebar.setTranslateX(-220);
            AnchorPane sidebarContainer = new AnchorPane(sidebar);
            sidebarContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(sidebar, 45.0);
            AnchorPane.setBottomAnchor(sidebar, 0.0);

            ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();
            AnchorPane panelAnchor = new AnchorPane(resourcePanel);
            panelAnchor.setPickOnBounds(false);
            AnchorPane.setTopAnchor(resourcePanel, 80.0);
            AnchorPane.setRightAnchor(resourcePanel, 20.0);

            FloatToolbox floatToolbox = new FloatToolbox(resourcePanel, UNIFIED_BLUE);
            AnchorPane floatContainer = new AnchorPane(floatToolbox);
            floatContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(floatToolbox, 80.0);
            AnchorPane.setLeftAnchor(floatToolbox, 15.0);

            Button menuBtn = createMenuButton();
            TitleBar titleBar = TitleBar.getInstance(
                    primaryStage, menuBtn, UNIFIED_BLUE,
                    canvasContainer, sidebarContainer, panelAnchor, floatContainer
            );
            VBox uiOverlay = new VBox(titleBar);
            uiOverlay.setPickOnBounds(false);

            rootStack.getChildren().addAll(
                    canvasContainer,
                    sidebarContainer,
                    panelAnchor,
                    floatContainer,
                    uiOverlay
            );

            uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);

            // 点击地图关闭侧边栏逻辑
            interactiveCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY && uiAnimator.isSidebarVisible()) {
                    menuBtn.fire();
                }
            });

            // 开启绘图循环
            renderLoop = new RenderLoop(interactiveCanvas.getGraphicsContext2D());
            renderLoop.start();

            // 异步载入匹配引擎（它不影响地图渲染）
            preloadMatcherAsync();

        } catch (Exception e) {
            log.error("UI 构建逻辑失败: ", e);
            showErrorAndExit("界面挂载异常。");
        }
    }

    private void makeResizable(Stage stage, Scene scene) {
        AtomicReference<Cursor> dragCursor = new AtomicReference<>(Cursor.DEFAULT);

        scene.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            if (isGhostMode || e.isPrimaryButtonDown()) return;
            double x = e.getSceneX(), y = e.getSceneY();
            double w = stage.getWidth(), h = stage.getHeight();
            Cursor cursor = Cursor.DEFAULT;

            if (x < RESIZE_MARGIN) {
                if (y < RESIZE_MARGIN) cursor = Cursor.NW_RESIZE;
                else if (y > h - RESIZE_MARGIN) cursor = Cursor.SW_RESIZE;
                else cursor = Cursor.W_RESIZE;
            } else if (x > w - RESIZE_MARGIN) {
                if (y < RESIZE_MARGIN) cursor = Cursor.NE_RESIZE;
                else if (y > h - RESIZE_MARGIN) cursor = Cursor.SE_RESIZE;
                else cursor = Cursor.E_RESIZE;
            } else if (y < RESIZE_MARGIN) {
                cursor = Cursor.N_RESIZE;
            } else if (y > h - RESIZE_MARGIN) {
                cursor = Cursor.S_RESIZE;
            }
            if (scene.getCursor() != cursor) scene.setCursor(cursor);
        });

        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            dragCursor.set(scene.getCursor());
            xOffset = stage.getX() - e.getScreenX();
            yOffset = stage.getY() - e.getScreenY();
        });

        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            Cursor cursor = dragCursor.get();
            if (isGhostMode || cursor == Cursor.DEFAULT) return;
            scene.setCursor(cursor);

            if (cursor == Cursor.E_RESIZE || cursor == Cursor.SE_RESIZE || cursor == Cursor.NE_RESIZE) {
                stage.setWidth(e.getSceneX());
            }
            if (cursor == Cursor.S_RESIZE || cursor == Cursor.SE_RESIZE || cursor == Cursor.SW_RESIZE) {
                stage.setHeight(e.getSceneY());
            }
            if (cursor == Cursor.W_RESIZE || cursor == Cursor.NW_RESIZE || cursor == Cursor.SW_RESIZE) {
                double oldW = stage.getWidth();
                stage.setX(e.getScreenX() + xOffset);
                stage.setWidth(oldW - (stage.getX() - (e.getScreenX() + xOffset)));
            }
            if (cursor == Cursor.N_RESIZE || cursor == Cursor.NW_RESIZE || cursor == Cursor.NE_RESIZE) {
                double oldH = stage.getHeight();
                stage.setY(e.getScreenY() + yOffset);
                stage.setHeight(oldH - (stage.getY() - (e.getScreenY() + yOffset)));
            }
        });

        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> dragCursor.set(Cursor.DEFAULT));
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
        HookRegistry.INSTANCE.registers(new ResourceGrayHook(), new RealOcrHook());
    }

    private void initBigMapResource() throws Exception {
        log.info("开始解析地图原始二进制文件...");
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            MapContext.getInstance().initWithKey(rawImage, rawImage.getWidth(), rawImage.getHeight(), "G");
        }
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.PLAYER_ICON_PATH)) {
            PlayerRenderer.getInstance().initIcon(is);
        }
    }

    // ====================== 【生命周期：安全关闭流程 - 绝对完整】 ======================
    @Override
    public void stop() {
        log.info(">>> 准备清理资源并安全退出...");

        // 看门狗：防止优雅退出挂死
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(500);
                log.warn(">>> 关闭超时，强制 halt...");
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignored) {}
        });
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            // 释放所有已注册的上下文
            HookMulticaster.getInstance().shutdown();
            if (windowsMonitor != null) {
                windowsMonitor.stopMonitor();
            }
            if (renderLoop != null) {
                renderLoop.stop();
            }
            if (mapMatcher != null) {
                mapMatcher.destroy();
            }

            OcrAsyncManager ocrManager = OcrAsyncManager.getInstance();
            if (ocrManager != null) {
                ocrManager.close();
            }

            log.info(">>> 各模块注销成功，正常关闭进程。");
            Platform.exit();
            System.exit(0);
        } catch (Exception e) {
            log.error("安全退出过程中发生非预期异常: ", e);
            Runtime.getRuntime().halt(1);
        }
    }
}