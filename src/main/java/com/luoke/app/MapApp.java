package com.luoke.app;

import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
import com.luoke.capture.CaptureFrameRecord;
import com.luoke.capture.WGCCapture;
import com.luoke.capture.WindowsMonitor;
import com.luoke.macher.map.MapMatcher;
import com.luoke.macher.map.MapMatcherFactory;
import com.luoke.macher.minimap.MapTracker;
import com.luoke.processor.MiniMapProcessor;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Rect;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MapApp extends Application {

    private static final String MAP_RESOURCE_PATH = "/source/big_map.png";

    private final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private final MapTracker tracker = MapTracker.getInstance();
    private WindowsMonitor windowsMonitor;
    private MapMatcher matcher;

    // UI 组件
    private Label combinedStatusLabel; // 合并后的信息显示
    private CheckBox followCb;
    private CheckBox showInfoCb;
    private HBox toolBar;

    private long lastUiUpdateTime = 0;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        try (InputStream is = ImageUtil.readImageAsStream(MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            Rectangle2D trimRect = ImageUtil.calculateTrimRect(rawImage);
            Image trimmed = ImageUtil.trimEmptyPixels(rawImage);
            double ratio = trimmed.getWidth() / trimmed.getHeight();

            MapManager.getInstance().init(trimmed, 800, 800 / ratio, trimRect.getMinX(), trimRect.getMinY());

            Pane root = initUI();

            primaryStage.setScene(new Scene(root, 800, 800 / ratio));
            primaryStage.setTitle("实时辅助地图工具 - 同步稳定版");
            primaryStage.setOnCloseRequest(e -> stop());
            primaryStage.show();

            preloadMatcherAsync();

            Platform.runLater(() -> CameraManager.getInstance().resetToFullView());

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

        // 1. 工具栏
        toolBar = new HBox(12);
        toolBar.setAlignment(Pos.CENTER_LEFT);
        toolBar.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-padding: 8 12; -fx-background-radius: 4;");

        followCb = new CheckBox("正在加载...");
        followCb.setDisable(true);
        followCb.setSelected(CameraManager.getInstance().isFollowMode());
        followCb.setTextFill(Color.WHITE);
        followCb.setOnAction(e -> CameraManager.getInstance().setFollowMode(followCb.isSelected()));

        showInfoCb = new CheckBox("显示详细信息");
        showInfoCb.setSelected(true);
        showInfoCb.setTextFill(Color.WHITE);

        toolBar.getChildren().addAll(followCb, showInfoCb);

        // 2. 合并后的信息 Label
        combinedStatusLabel = new Label("状态: ⏳ 初始化中...");
        combinedStatusLabel.setTextFill(Color.WHITE);
        combinedStatusLabel.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-padding: 8 12; -fx-background-radius: 4;");
        combinedStatusLabel.visibleProperty().bind(showInfoCb.selectedProperty());

        controlPanel.getChildren().addAll(toolBar, combinedStatusLabel);
        root.getChildren().add(controlPanel);

        return root;
    }

    private void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                matcher = MapMatcherFactory.createMatcher(0, false);
                URL resource = this.getClass().getResource(MAP_RESOURCE_PATH);
                if (resource != null) {
                    matcher.init(new File(resource.toURI()).getAbsolutePath());
                }
                isMatcherReady.set(true);
                Platform.runLater(() -> {
                    followCb.setDisable(false);
                    followCb.setText("跟随玩家");
                    updateStatusText("✅ 已就绪", Color.LIGHTGREEN, "等待进入游戏...");
                    startLiveMonitor();
                });
            } catch (Exception e) {
                log.error("特征库加载失败", e);
                Platform.runLater(() -> updateStatusText("❌ 加载失败", Color.RED, e.getMessage()));
            }
        });
    }

    private void startLiveMonitor() {
        if (!isMatcherReady.get()) return;
        windowsMonitor = new WindowsMonitor("洛克王国：世界");
        windowsMonitor.startMonitorPoll(10, this::processImage);
    }

    private void processImage(WGCCapture.Frame frame) {
        if (frame == null) {
            updateStatusText("⚠️ 找不到窗口", Color.ORANGE, "请确认游戏已运行");
            return;
        }

        try {
            if (tracker.ensureInitialized(frame)) {
                Rect roi = tracker.getActiveROI();
                long startMatch = System.currentTimeMillis();

                CaptureFrameRecord miniMapFrame = MiniMapProcessor.extractCircleMaskMiniMapBytes(
                        frame.getPixels(), frame.getWidth(), frame.getHeight(),
                        roi.x(), roi.y(), roi.width(), roi.height());

                if (miniMapFrame != null && miniMapFrame.bytes() != null) {
                    double[][] corners = matcher.run(miniMapFrame.bytes(), roi.width(), roi.height());
                    long cost = System.currentTimeMillis() - startMatch;

                    if (corners != null && corners.length >= 3) {
                        double[] center = MapMathUtil.getCentroid(corners);
                        CoordinateTransformer.updatePositionSmoothly(center[0], center[1], .8);

                        String debug = String.format("%dx%d | 延迟: %dms", frame.getWidth(), frame.getHeight(), cost);
                        updateStatusText("🛰️ 正在同步", Color.LIGHTGREEN, debug);
                    } else {
                        updateStatusText("🔍 匹配丢失", Color.RED, "特征点不足");
                    }
                }
            } else {
                updateStatusText("🔎 定位UI...", Color.WHITE, "正在寻找小地图边界");
            }
        } catch (Exception e) {
            log.error("处理异常", e);
        } finally {
            frame = null;
        }
    }

    /**
     * 合并显示的更新方法
     */
    private void updateStatusText(String status, Color color, String debug) {
        if (!combinedStatusLabel.isVisible()) return;

        long now = System.currentTimeMillis();
        // 频率限制在 500ms 左右即可，兼顾实时性与性能
        if (now - lastUiUpdateTime < 500) return;

        lastUiUpdateTime = now;
        Platform.runLater(() -> {
            StringBuilder sb = new StringBuilder("状态: ").append(status);
            if (debug != null && !debug.isEmpty()) {
                sb.append("  |  ").append(debug);
            }
            combinedStatusLabel.setText(sb.toString());
            combinedStatusLabel.setTextFill(color);
        });
    }

    @Override
    public void stop() {
        if (windowsMonitor != null) {
            windowsMonitor.stopMonitor();
        }
        log.info("应用退出");
    }
}