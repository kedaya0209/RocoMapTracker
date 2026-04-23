package com.luoke.app;

import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.context.ResourcePointContext;
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
import com.luoke.app.utils.ResourceUtils;
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

    private static final MapTracker mapTracker = MapTracker.getInstance();
    private static final StatsManager stats = StatsManager.getInstance();
    private static final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private static MapMatcher mapMatcher;
    private static WindowsMonitor windowsMonitor;
    private RenderLoop renderLoop;

    private static Label statusLabel;
    private static CheckBox followPlayerCb;

    static void main(String[] args) {
        // ====================== 【关键】启动先释放所有资源 ======================
        preloadMatcherAsync();
        ResourceUtils.extractAll();
        launch(args);
    }

    private static void processFrame(Frame frame) {
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

    private static void updateStatus(String msg, Color color) {
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            statusLabel.setTextFill(color);
        });
    }

    private static void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                mapMatcher = new SiftMapMatcher();
                // ====================== 自动优先读外部资源 ======================
                mapMatcher.init(AppConfig.MAP_RESOURCE_PATH);
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

    private static void startCapture() {
        windowsMonitor = new WindowsMonitor(AppConfig.TARGET_WINDOW_NAME);
        windowsMonitor.startMonitor(MainApp::processFrame);
    }

    @Override
    public void start(Stage primaryStage) {
        try {
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
                    CameraManager.getInstance().setFollowMode(nv)
            );
            followPlayerCb.setVisible(false);

            statusLabel = new Label(AppConfig.STATUS_STARTING);
            statusLabel.setTextFill(Color.BLACK);
            statusLabel.setStyle("-fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");

            topBar.getChildren().addAll(followPlayerCb, statusLabel);
            root.getChildren().addAll(canvas, topBar);

            renderLoop = new RenderLoop(canvas.getGraphicsContext2D());
            renderLoop.start();

            primaryStage.setTitle(AppConfig.APP_MAIN_TITLE);
            Scene scene = new Scene(root, AppConfig.MAIN_WINDOW_DEFAULT_WIDTH, AppConfig.MAIN_WINDOW_DEFAULT_HEIGHT);
            primaryStage.setScene(scene);
            primaryStage.setOnCloseRequest(e -> stop());
            primaryStage.show();


        } catch (Exception e) {
            log.error("启动失败", e);
        }
    }

    private void initBigMapResource() throws Exception {
        // ====================== 自动优先读外部资源 ======================
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.MAP_RESOURCE_PATH)) {

            Image rawImage = new Image(is);
            MapManager.getInstance().initWithKey(rawImage, rawImage.getWidth(), rawImage.getHeight(), "G");
        }

        // ====================== 自动优先读外部资源 ======================
        try (InputStream iconStream = ResourceUtils.getResourceStream(AppConfig.PLAYER_ICON_PATH)) {
            PlayerRenderer.getInstance().initIcon(iconStream);
        }
    }

    @Override
    public void stop() {
        if (renderLoop != null) renderLoop.stop();
        if (windowsMonitor != null) windowsMonitor.stopMonitor();
        Platform.exit();
    }
}