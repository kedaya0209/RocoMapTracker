package com.luoke.app;

import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.*;
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
import com.luoke.app.render.CutterPlayerRenderer;
import com.luoke.app.render.PlayerRenderer;
import com.luoke.app.render.RenderLoop;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MainApp extends Application {

    private static final MapTracker mapTracker = MapTracker.getInstance();
    private static final StatsContext stats = StatsContext.getInstance();
    private static final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private static MapMatcher mapMatcher;
    private static WindowsMonitor windowsMonitor;
    private RenderLoop renderLoop;

    private static Label statusLabel;
    private static CheckBox followPlayerCb;

    // ====================== 【关键】用来阻塞 start() ======================
    private final CountDownLatch initLatch = new CountDownLatch(1);
    private volatile boolean initSuccess = false;

    public static void main(String[] args) {
        launch(args);
    }

    // ==================== 下面的代码你不用动 ====================
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

            if (player.isFound()) {
                Platform.runLater(() -> followPlayerCb.setVisible(true));
            }

            if (!player.isFound()) {
                updateStatus(AppConfig.STATUS_PLAYER_NOT_FOUND, Color.ORANGE);
                return;
            }

            MapContext.getInstance().updatePlayerState(center[0], center[1], player.getAngle());
            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], AppConfig.COORDINATE_SMOOTH_FACTOR);
            updateStatus(AppConfig.STATUS_RUNNING, Color.LIGHTGREEN);
        } catch (Exception e) {
            log.error("帧异常", e);
        }
    }

    // ====================== 【阻塞式初始化】 ======================
    @Override
    public void init() throws Exception {
        super.init();
        log.info("init() 开始初始化（子线程）");

        try {
            // 1. 释放基础资源
            ResourceUtils.extractAll();

            File rootDir = ResourceUtils.getExternalFile(AppConfig.SOURCE_ROOT_DIR);
            if (!rootDir.exists()) {
                rootDir.mkdirs();
            }

            File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
            if (initFile.exists()) {
                log.info("资源已初始化，直接启动");
                initSuccess = true;
                return;
            }

            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("首次启动");
                alert.setHeaderText("首次运行需要下载资源文件");
                alert.setContentText("点击确定后开始下载，请不要关闭程序...");
                alert.showAndWait(); // 这里会阻塞 JavaFX 线程
            });

            // ======================
            // 开始下载（后台执行）
            // ======================
            log.info("开始下载资源...");
            MapResourceUpdater.updateAllResources();

            // 标记已初始化
            initFile.createNewFile();
            initSuccess = true;
            log.info("初始化完成！");

        } catch (Exception e) {
            log.error("初始化失败", e);
            initSuccess = false;

            // 失败弹窗
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("初始化失败");
                alert.setHeaderText("资源下载失败，程序无法启动");
                alert.showAndWait();
                Platform.exit();
            });
        } finally {
            // 放开 start() 的阻塞
            initLatch.countDown();
        }
    }

    private static void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                mapMatcher = new SiftMapMatcher();
                mapMatcher.init(AppConfig.MAP_RESOURCE_PATH);
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);
                isMatcherReady.set(true);
                try {
                    startCapture();
                } catch (Exception ignore) {
                }
            } catch (Exception e) {
                log.error("匹配器加载失败", e);
            }
        });
    }

    private static void updateStatus(String msg, Color color) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            statusLabel.setTextFill(color);
        });
    }

    // ====================== 【被阻塞，直到 init() 完成】 ======================
    @Override
    public void start(Stage primaryStage) {
        registerHook();
        try {
            // ======================
            // 【阻塞】等 init() 完成
            // ======================
            log.info("等待初始化完成...");
            initLatch.await();

            if (!initSuccess) {
                Platform.exit();
                return;
            }

            log.info("初始化完成，启动主窗口");

            // ---------- 下面是你原来的逻辑 ----------
            initBigMapResource();
            ResourcePointContext.getInstance().loadAndInit();

            StackPane root = new StackPane();
            InteractiveCanvas canvas = new InteractiveCanvas();
            canvas.widthProperty().bind(root.widthProperty());
            canvas.heightProperty().bind(root.heightProperty());

            HBox topBar = new HBox(AppConfig.TOP_BAR_SPACING);
            topBar.setPadding(new Insets(
                    AppConfig.TOP_BAR_PADDING_VERTICAL,
                    AppConfig.TOP_BAR_PADDING_HORIZONTAL,
                    AppConfig.TOP_BAR_PADDING_VERTICAL,
                    AppConfig.TOP_BAR_PADDING_HORIZONTAL
            ));
            topBar.setMouseTransparent(false);
            topBar.setPickOnBounds(false);
            StackPane.setAlignment(topBar, javafx.geometry.Pos.TOP_LEFT);

            followPlayerCb = new CheckBox(AppConfig.FOLLOW_PLAYER);
            followPlayerCb.setStyle("-fx-text-fill: black; -fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");
            followPlayerCb.selectedProperty().addListener((o, ov, nv) ->
                    CameraContext.getInstance().setFollowMode(nv)
            );
            followPlayerCb.setVisible(false);

            // 拦截默认关闭行为，手动强制走销毁流程
            primaryStage.setOnCloseRequest(event -> {
                event.consume(); // 吃掉默认关闭
                stop();          // 主动调用你的完整释放逻辑
            });

            Button updateBtn = new Button("更新资源文件");
            updateBtn.setStyle("-fx-text-fill: black; -fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");
            updateBtn.setOnAction(e -> updateResource(updateBtn));

            statusLabel = new Label(AppConfig.STATUS_STARTING);
            statusLabel.setTextFill(Color.BLACK);
            statusLabel.setStyle("-fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");

            topBar.getChildren().addAll(updateBtn, followPlayerCb, statusLabel);
            root.getChildren().addAll(canvas, topBar);

            renderLoop = new RenderLoop(canvas.getGraphicsContext2D());
            renderLoop.start();

            primaryStage.setTitle(AppConfig.APP_MAIN_TITLE);
            Scene scene = new Scene(root, AppConfig.MAIN_WINDOW_DEFAULT_WIDTH, AppConfig.MAIN_WINDOW_DEFAULT_HEIGHT);
            primaryStage.setScene(scene);
            primaryStage.setOnCloseRequest(e -> stop());
            primaryStage.show();

            preloadMatcherAsync();

        } catch (Exception e) {
            log.error("启动失败", e);
            Platform.exit();
        }
    }

    private static void updateResource(Button updateBtn) {
        Thread.ofVirtual().start(() -> {
            try {
                Platform.runLater(() -> {
                    updateBtn.setText("正在更新...");
                    updateBtn.setDisable(true);
                });

                MapResourceUpdater.updateAllResources();
                ResourcePointContext.getInstance().loadAndInit();

                Platform.runLater(() -> updateBtn.setText("更新完成"));

            } catch (Exception ex) {
                log.error("更新失败", ex);
                Platform.runLater(() -> updateBtn.setText("更新失败，重试"));
            } finally {
                Platform.runLater(() -> updateBtn.setDisable(false));
            }
        });
    }

    private static void startCapture() {
        windowsMonitor = new WindowsMonitor(AppConfig.TARGET_WINDOW_NAME);
        windowsMonitor.startMonitor(MainApp::processFrame);
    }

    private void initBigMapResource() throws Exception {
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            MapContext.getInstance().initWithKey(rawImage, rawImage.getWidth(), rawImage.getHeight(), "G");
        }
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.PLAYER_ICON_PATH)) {
            PlayerRenderer.getInstance().initIcon(is);
        }
    }

    private void registerHook() {
        ResourceGrayHook resourceGrayHook = new ResourceGrayHook();
        RealOcrHook realTimeOCRHook = new RealOcrHook();
        HookRegistry.INSTANCE.registers(resourceGrayHook, realTimeOCRHook);
    }

    @Override
    public void stop() {
        System.out.println(">>> 正在启动紧急退出程序...");

        // 1. 立即开启一个“自杀计数器”线程（防止 stop 方法本身卡死）
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(300); // 给 3 秒时间优雅退出
                System.err.println(">>> 优雅退出超时，执行强制毁灭 (halt)...");
                Runtime.getRuntime().halt(0); // halt 不会等待 ShutdownHook，直接杀掉 JVM
            } catch (InterruptedException ignored) {}
        });
        watchdog.setDaemon(true);
        watchdog.start();

        try {
            HookMulticaster multicaster = HookMulticaster.getInstance();
            // 2. 先切断信号源：让所有新产生的事件直接丢弃
            if (multicaster != null) {
                multicaster.shutdown();
            }

            // 3. 释放资源（带中断）
            if (windowsMonitor != null) windowsMonitor.stopMonitor();
            if (renderLoop != null) renderLoop.stop();

            // 4. 注意：如果这些 destroy 耗时极长，考虑放进子线程异步关，或者直接跳过
            if (mapMatcher != null) mapMatcher.destroy();
            OcrAsyncManager ocrAsyncManager = OcrAsyncManager.getInstance();
            if (ocrAsyncManager != null) {
                // 在 close 内部，务必调用 executorService.shutdownNow() 强制中断虚拟线程
                ocrAsyncManager.close();
            }

            Platform.exit();

            // 5. 最后的挣扎
            Runtime.getRuntime().exit(0);

        } catch (Exception e) {
            System.err.println("退出过程中发生异常: " + e.getMessage());
            Runtime.getRuntime().halt(1);
        }
    }
}