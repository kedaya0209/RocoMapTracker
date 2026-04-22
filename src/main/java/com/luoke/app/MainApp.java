package com.luoke.app;

import com.luoke.app.capture.CaptureFrameRecord;
import com.luoke.app.capture.WGCCapture;
import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.context.StatsManager;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.SiftMapMatcher;
import com.luoke.app.macher.minimap.MapTracker;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import com.luoke.app.render.PlayerRenderer;
import com.luoke.app.render.RenderLoop;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
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

    private static final String MAP_RESOURCE_PATH = AppConfig.MAP_RESOURCE_PATH;
    private static final String PLAYER_SOURCE_PATH = AppConfig.PLAYER_ICON_PATH;
    private static final String TARGET_WINDOW = AppConfig.TARGET_WINDOW_NAME;

    private final MapTracker mapTracker = MapTracker.getInstance();
    private final StatsManager stats = StatsManager.getInstance();
    private final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private MapMatcher mapMatcher;
    private WindowsMonitor windowsMonitor;
    private RenderLoop renderLoop;

    private Label statusLabel;
    private CheckBox followPlayerCb; // 提升为成员变量

    static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            initBigMapResource();

            StackPane root = new StackPane();

            // 画布
            InteractiveCanvas canvas = new InteractiveCanvas();
            canvas.widthProperty().bind(root.widthProperty());
            canvas.heightProperty().bind(root.heightProperty());

            // 顶层悬浮栏
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

            // ====================== 锁定玩家（默认隐藏） ======================
            followPlayerCb = new CheckBox(AppConfig.FOLLOW_PLAYER);
            followPlayerCb.setStyle("-fx-text-fill: black; -fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");
            followPlayerCb.selectedProperty().addListener((o, ov, nv) ->
                    CameraManager.getInstance().setFollowMode(nv)
            );
            followPlayerCb.setVisible(false); // 默认隐藏

            // 状态文字
            statusLabel = new Label(AppConfig.STATUS_STARTING);
            statusLabel.setTextFill(Color.BLACK);
            statusLabel.setStyle("-fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");

            topBar.getChildren().addAll(followPlayerCb, statusLabel);
            root.getChildren().addAll(canvas, topBar);

            // 渲染循环
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
                updateStatus(AppConfig.STATUS_MINIMAP_NOT_FOUND, Color.RED);
                return;
            }

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

            // ====================== 核心：找到玩家 → 显示锁定复选框 ======================
            if (player.isFound()) {
                Platform.runLater(() -> followPlayerCb.setVisible(true));
            }

            if (!player.isFound()) {
                updateStatus(AppConfig.STATUS_PLAYER_NOT_FOUND, Color.ORANGE);
                return;
            }

            MapManager.getInstance().updatePlayerState(center[0], center[1], player.getAngle());
            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], AppConfig.COORDINATE_SMOOTH_FACTOR);
            updateStatus(AppConfig.STATUS_RUNNING, Color.LIGHTGREEN);

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
                mapMatcher = new SiftMapMatcher();
                mapMatcher.init(MAP_RESOURCE_PATH);
                startCapture();
                isMatcherReady.set(true);
            } catch (Exception e) {
                log.error("匹配器加载失败", e);
            }
        });
    }

    private void startCapture() {
        windowsMonitor = new WindowsMonitor(TARGET_WINDOW);
        windowsMonitor.startMonitor(this::processFrame);
    }

    @Override
    public void stop() {
        if (renderLoop != null) renderLoop.stop();
        if (windowsMonitor != null) windowsMonitor.stopMonitor();
        Platform.exit();
    }
}