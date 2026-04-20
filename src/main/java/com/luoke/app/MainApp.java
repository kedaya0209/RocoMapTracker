package com.luoke.app;

import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.context.StatsManager;
import com.luoke.app.render.PlayerRenderer;
import com.luoke.app.render.RenderLoop;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
import com.luoke.capture.CaptureFrameRecord;
import com.luoke.capture.WGCCapture;
import com.luoke.capture.WindowsMonitor;
import com.luoke.macher.map.MapMatcher;
import com.luoke.macher.map.MapMatcherFactory;
import com.luoke.macher.minimap.MapTracker;
import com.luoke.macher.player.ArrowDetector;
import com.luoke.macher.player.Player;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MainApp extends Application {

    private static final String MAP_RESOURCE_PATH = "/source/big_map.png";
    private static final String PLAYER_SOURCE_PATH = "/source/player.png";
    private static final String TARGET_WINDOW = "洛克王国：世界";

    private final MapTracker mapTracker = MapTracker.getInstance();
    private final StatsManager stats = StatsManager.getInstance();
    private final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private MapMatcher mapMatcher;
    private WindowsMonitor windowsMonitor;
    private InteractiveCanvas canvas;
    private RenderLoop renderLoop;

    private Label statusLabel;
    private CheckBox followPlayerCb;

    static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            initBigMapResource();

            StackPane root = new StackPane();

            // 画布
            canvas = new InteractiveCanvas();
            canvas.widthProperty().bind(root.widthProperty());
            canvas.heightProperty().bind(root.heightProperty());

            // 顶层悬浮栏（HBox 水平排列，不重叠）
            HBox topBar = new HBox(12);
            topBar.setPadding(new Insets(10, 15, 10, 15));
            topBar.setMouseTransparent(false);
            topBar.setPickOnBounds(false);
            StackPane.setAlignment(topBar, javafx.geometry.Pos.TOP_LEFT);

            // 锁定玩家（黑色文字）
            followPlayerCb = new CheckBox("锁定玩家");
            followPlayerCb.setStyle("-fx-text-fill: black; -fx-font-size: 14px;");
            followPlayerCb.selectedProperty().addListener((o, ov, nv) ->
                    CameraManager.getInstance().setFollowMode(nv)
            );

            // 状态文字（白色）
            statusLabel = new Label("启动中...");
            statusLabel.setTextFill(Color.BLACK);
            statusLabel.setStyle("-fx-font-size: 14px;");

            topBar.getChildren().addAll(followPlayerCb, statusLabel);
            root.getChildren().addAll(canvas, topBar);

            // 渲染循环
            renderLoop = new RenderLoop(canvas.getGraphicsContext2D());
            renderLoop.start();

            Scene scene = new Scene(root, 1000, 700);
            primaryStage.setTitle("洛克导航");
            primaryStage.setScene(scene);
            primaryStage.setOnCloseRequest(e -> stop());
            primaryStage.show();

            preloadMatcherAsync();
        } catch (Exception e) {
            log.error("启动失败", e);
        }
    }

    private void initBigMapResource() throws Exception {
        try (InputStream is = ImageUtil.readImageAsStream(MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            MapManager.getInstance().init(rawImage, rawImage.getWidth(), rawImage.getHeight());
            PlayerRenderer.getInstance().initIcon(PLAYER_SOURCE_PATH);
        }
    }

    private void processFrame(WGCCapture.Frame frame) {
        if (frame == null || !isMatcherReady.get()) return;
        stats.onFrameProcessed();

        try {
            long t0 = System.currentTimeMillis();
            CaptureFrameRecord miniMap = mapTracker.getMiniMapImage(frame);
            stats.recordMapDetect(System.currentTimeMillis() - t0);
            if (miniMap == null) {
                updateStatus("❌ 小地图未找到", Color.RED);
                return;
            }

            long t1 = System.currentTimeMillis();
            double[][] corners = mapMatcher.run(miniMap.bytes(), miniMap.width(), miniMap.height());
            stats.recordMatch(System.currentTimeMillis() - t1);
            if (corners == null || corners.length < 3) {
                updateStatus("❌ 匹配失败", Color.RED);
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
                updateStatus("⚠️ 未找到玩家", Color.ORANGE);
                return;
            }

            MapManager.getInstance().updatePlayerState(center[0], center[1], player.getAngle());
            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], 0.8);
            updateStatus("✅ 同步中", Color.LIGHTGREEN);

        } catch (Exception e) {
            log.error("帧异常", e);
        }
    }

    private void updateStatus(String msg, Color color) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            statusLabel.setTextFill(color);
        });
    }

    private void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                mapMatcher = MapMatcherFactory.createMatcher(0, false);
                mapMatcher.init(MAP_RESOURCE_PATH);
                isMatcherReady.set(true);
                Platform.runLater(this::startCapture);
            } catch (Exception e) {
                log.error("匹配器加载失败", e);
            }
        });
    }

    private void startCapture() {
        windowsMonitor = new WindowsMonitor(TARGET_WINDOW);
        windowsMonitor.startMonitorPoll(10, this::processFrame);
    }

    @Override
    public void stop() {
        if (renderLoop != null) renderLoop.stop();
        if (windowsMonitor != null) windowsMonitor.stopMonitor();
        Platform.exit();
    }
}