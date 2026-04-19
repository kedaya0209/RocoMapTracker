package com.luoke.app;

import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.component.PlayerRenderer;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.utils.*;
import com.luoke.capture.CaptureFrameRecord;
import com.luoke.capture.WGCCapture;
import com.luoke.capture.WindowsMonitor;
import com.luoke.macher.map.MapMatcher;
import com.luoke.macher.map.MapMatcherFactory;
import com.luoke.macher.player.Player;
import com.luoke.macher.player.RocoTrackerUtils;
import com.luoke.processor.MiniMapProcessor;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MapApp extends Application {

    private static final String MAP_RESOURCE_PATH = "/source/big_map.png";
    private static final String PLAYER_SOURCE_PATH = "/source/player.png";
    private static final String SAVE_FOLDER = "C:\\Users\\tangh\\Desktop\\test";

    private final com.luoke.macher.minimap.MapTracker tracker = com.luoke.macher.minimap.MapTracker.getInstance();
    private WindowsMonitor windowsMonitor;
    private MapMatcher matcher;
    private final AtomicBoolean isMatcherReady = new AtomicBoolean(false);

    private Label combinedStatusLabel;
    private CheckBox followCb, showInfoCb;
    private long lastUiUpdateTime = 0;

    private Stage debugStage;
    private Canvas debugCanvas;
    private Button debugSaveBtn;
    private final double DEBUG_SIZE = 250;

    private volatile CaptureFrameRecord lastMiniMapFrame;

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage primaryStage) {
        try (InputStream is = ImageUtil.readImageAsStream(MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            Rectangle2D trimRect = ImageUtil.calculateTrimRect(rawImage);
            Image trimmed = ImageUtil.trimEmptyPixels(rawImage);
            double ratio = trimmed.getWidth() / trimmed.getHeight();

            MapManager.getInstance().init(trimmed, 800, 800 / ratio, trimRect.getMinX(), trimRect.getMinY());
            PlayerRenderer.getInstance().initIcon(PLAYER_SOURCE_PATH);

            Pane root = initUI();
            primaryStage.setScene(new Scene(root, 800, 800 / ratio));
            primaryStage.setTitle("洛克实时导航");
            primaryStage.setOnCloseRequest(e -> stop());
            primaryStage.show();

            preloadMatcherAsync();
        } catch (Exception e) {
            log.error("启动失败", e);
        }
    }

    private Pane initUI() {
        InteractiveCanvas canvas = new InteractiveCanvas();
        Pane root = new Pane(canvas);
        canvas.widthProperty().bind(root.widthProperty());
        canvas.heightProperty().bind(root.heightProperty());

        VBox controlPanel = new VBox(10);
        controlPanel.setPadding(new Insets(15));
        controlPanel.setPickOnBounds(false);

        HBox toolBar = new HBox(12);
        toolBar.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-padding: 8;");
        followCb = new CheckBox("跟随"); followCb.setTextFill(Color.WHITE);
        followCb.setOnAction(e -> CameraManager.getInstance().setFollowMode(followCb.isSelected()));
        showInfoCb = new CheckBox("详细信息"); showInfoCb.setSelected(true); showInfoCb.setTextFill(Color.WHITE);
        toolBar.getChildren().addAll(followCb, showInfoCb);

        combinedStatusLabel = new Label("系统就绪中...");
        combinedStatusLabel.setTextFill(Color.WHITE);
        combinedStatusLabel.setStyle("-fx-background-color: rgba(0,0,0,0.7); -fx-padding: 10;");

        controlPanel.getChildren().addAll(toolBar, combinedStatusLabel);
        root.getChildren().add(controlPanel);
        return root;
    }

    private void processImage(WGCCapture.Frame frame) {
        if (frame == null || !isMatcherReady.get()) return;
        long totalStart = System.nanoTime(); // 总耗时起点

        try {
            if (tracker.ensureInitialized(frame)) {
                Rect roi = tracker.getActiveROI();
                CaptureFrameRecord miniMapFrame = MiniMapProcessor.extractCircleMaskMiniMapBytes(
                        frame.getPixels(), frame.getWidth(), frame.getHeight(),
                        roi.x(), roi.y(), roi.width(), roi.height());

                if (miniMapFrame != null && miniMapFrame.bytes() != null) {
                    this.lastMiniMapFrame = miniMapFrame;

                    // 1. 地图匹配耗时测量
                    long matchStart = System.nanoTime();
                    double[][] corners = matcher.run(miniMapFrame.bytes(), roi.width(), roi.height());
                    double matchMs = (System.nanoTime() - matchStart) / 1_000_000.0;

                    if (corners != null && corners.length >= 3) {
                        double[] center = MapMathUtil.getCentroid(corners);

                        try (Mat m = ImageUtil.convertToMat(miniMapFrame)) {
                            // 2. 角色方向识别耗时测量
                            long playerStart = System.nanoTime();
                            Player p = RocoTrackerUtils.updatePlayerInfo(m);
                            double playerMs = (System.nanoTime() - playerStart) / 1_000_000.0;

                            double totalMs = (System.nanoTime() - totalStart) / 1_000_000.0;

                            if (!p.isFound()) {
                                updateStatusText("未识别到玩家", Color.BROWN,
                                        String.format("匹配: %.1fms | 方向: %.1fms | 总计: %.1fms", matchMs, playerMs, totalMs));
                                return;
                            }

                            // 正常更新状态
                            double angle = p.getAngle();
                            MapManager.getInstance().updatePlayerState(center[0], center[1], angle);
                            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], 0.8);

                            updateStatusText("🛰️ 同步中", Color.LIGHTGREEN,
                                    String.format("角度: %.1f°\n匹配: %.1fms | 方向: %.1fms | 总计: %.1fms",
                                            angle, matchMs, playerMs, totalMs));
                        }
                    } else {
                        updateStatusText("❌ 匹配失败", Color.RED, "无法定位坐标");
                    }
                }
            }
        } catch (Exception e) { log.error("处理异常", e); }
    }


    private void updateStatusText(String status, Color color, String debugInfo) {
        if (System.currentTimeMillis() - lastUiUpdateTime < 150) return;
        lastUiUpdateTime = System.currentTimeMillis();
        Platform.runLater(() -> {
            combinedStatusLabel.setText(status + "\n" + debugInfo);
            combinedStatusLabel.setTextFill(color);
        });
    }

    private void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                matcher = MapMatcherFactory.createMatcher(0, false);
                URL res = getClass().getResource(MAP_RESOURCE_PATH);
                if (res != null) matcher.init(new File(res.toURI()).getAbsolutePath());
                isMatcherReady.set(true);
                Platform.runLater(this::startLiveMonitor);
            } catch (Exception e) { log.error("加载失败", e); }
        });
    }

    private void startLiveMonitor() {
        windowsMonitor = new WindowsMonitor("洛克王国：世界");
        windowsMonitor.startMonitorPoll(10, this::processImage);
    }

    private void saveToLocal(Image img, String name) throws IOException {
        int w = (int) img.getWidth(), h = (int) img.getHeight();
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] buf = new int[w * h];
        img.getPixelReader().getPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), buf, 0, w);
        bi.setRGB(0, 0, w, h, buf, 0, w);
        ImageIO.write(bi, "png", new File(SAVE_FOLDER, name));
    }

    @Override
    public void stop() {
        if (windowsMonitor != null) windowsMonitor.stopMonitor();
        if (debugStage != null) debugStage.close();
    }
}