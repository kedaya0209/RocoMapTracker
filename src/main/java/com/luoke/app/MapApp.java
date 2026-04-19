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
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private final MapTracker tracker = MapTracker.getInstance();
    private WindowsMonitor windowsMonitor;
    private MapMatcher matcher;
    private Label statusLabel;
    private Label debugLabel;
    private VBox infoContainer; // 用于控制状态栏整体显示/隐藏

    private long lastUiUpdateTime = 0;

    //配置项,主动拉去或者消费推流  枚举项 HIGH_PERFORMANCE , POWER_SAVING
    private String performanceMode = "HIGH_PERFORMANCE";

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

            InteractiveCanvas canvas = new InteractiveCanvas();
            Pane root = new Pane(canvas);
            canvas.widthProperty().bind(root.widthProperty());
            canvas.heightProperty().bind(root.heightProperty());

            // --- 布局调整：控制面板 ---
            VBox controlPanel = new VBox(10);
            controlPanel.setPadding(new Insets(15));
            controlPanel.setPickOnBounds(false); // 允许鼠标穿透空白处点击地图

            // 1. 水平排列的控制按钮
            HBox toolBar = new HBox(12);
            toolBar.setAlignment(Pos.CENTER_LEFT);
            CheckBox followCb = new CheckBox("正在加载...");
            followCb.setDisable(true);
            followCb.setPrefWidth(120);
            followCb.setText("跟随玩家");

            CheckBox showInfoCb = new CheckBox("显示状态信息");
            showInfoCb.setSelected(true);
            showInfoCb.setTextFill(Color.WHITE);

            toolBar.getChildren().addAll(followCb, showInfoCb);

            // 2. 状态信息容器
            infoContainer = new VBox(5);
            statusLabel = new Label("状态: ⏳ 初始化中");
            statusLabel.setTextFill(Color.WHITE);
            statusLabel.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-padding: 5 10; -fx-background-radius: 4;");

            debugLabel = new Label("窗口信息: --");
            debugLabel.setTextFill(Color.GOLD);
            debugLabel.setStyle("-fx-background-color: rgba(0,0,0,0.6); -fx-padding: 5 10; -fx-background-radius: 4;");

            infoContainer.getChildren().addAll(statusLabel, debugLabel);
            // 绑定显示开关
            infoContainer.visibleProperty().bind(showInfoCb.selectedProperty());

            //现在是透明背景，需要填充一个颜色，要与infoContainer颜色一致
            toolBar.setBackground(infoContainer.getBackground());

            controlPanel.getChildren().addAll(toolBar, infoContainer);
            root.getChildren().add(controlPanel);

            // 事件监听
            followCb.setOnAction(e -> {
                boolean active = followCb.isSelected();
                CameraManager.getInstance().setFollowMode(active);
            });

            primaryStage.setScene(new Scene(root, 800, 800 / ratio));
            primaryStage.setTitle("实时辅助地图");
            primaryStage.setOnCloseRequest(e -> stop());
            primaryStage.show();

            preloadMatcherAsync(followCb);
            Platform.runLater(() -> CameraManager.getInstance().resetToFullView());

        } catch (Exception e) {
            log.error("启动失败", e);
        }
    }

    private void preloadMatcherAsync(CheckBox btn) {
        Thread.ofVirtual().start(() -> {
            try {
                matcher = MapMatcherFactory.createMatcher(0, false);
                URL resource = this.getClass().getResource(MAP_RESOURCE_PATH);
                if (resource != null) {
                    matcher.init(new File(resource.toURI()).getAbsolutePath());
                }
                isMatcherReady.set(true);
                Platform.runLater(() -> {
                    btn.setDisable(false);
                    statusLabel.setText("状态: ✅ 已就绪");
                    startLiveMonitor();
                });
            } catch (Exception e) {
                log.error("特征库加载失败", e);
                Platform.runLater(() -> statusLabel.setText("状态: ❌ 加载失败"));
            }
        });
    }

    private void startLiveMonitor() {
        if (!isMatcherReady.get()) return;

        windowsMonitor = new WindowsMonitor("洛克王国：世界");
        if ("HIGH_PERFORMANCE".equals(performanceMode)) {
            windowsMonitor.startMonitorPush(10, this::processImage);
            return;
        }
        windowsMonitor.startMonitorPoll(10, this::processImage);

    }

    private void processImage(WGCCapture.Frame frame) {
        if (frame == null) {
            updateStatusText("状态: ⚠️ 找不到窗口", Color.ORANGE, null);
            return;
        }
        if (!isProcessing.compareAndSet(false, true)) return;

        try {
            if (tracker.ensureInitialized(frame)) {
                Rect roi = tracker.getActiveROI();
                long startMatch = System.currentTimeMillis();

                CaptureFrameRecord miniMapFrame = MiniMapProcessor.extractCircleMaskMiniMapBytes(
                        frame.getPixels(), frame.getWidth(), frame.getHeight(),
                        roi.x(), roi.y(), roi.width(), roi.height());

                double[][] corners = matcher.run(miniMapFrame.bytes(), roi.width(), roi.height());
                long cost = System.currentTimeMillis() - startMatch;

                if (corners != null && corners.length >= 3) {
                    double[] center = MapMathUtil.getCentroid(corners);
                    CoordinateTransformer.updatePositionSmoothly(center[0], center[1], .8);

                    String debugInfo = String.format("窗口: %dx%d | 耗时: %dms", frame.getWidth(), frame.getHeight(), cost);
                    updateStatusText("状态: 🛰️ 正在同步", Color.LIGHTGREEN, debugInfo);
                } else {
                    updateStatusText("状态: 🔍 匹配丢失", Color.RED, null);
                }
            } else {
                String debugInfo = String.format("窗口: %dx%d | 正在寻找 UI...", frame.getWidth(), frame.getHeight());
                updateStatusText("状态: 🔎 正在定位 UI...", Color.WHITE, debugInfo);
            }
        } catch (Exception e) {
            log.error("处理异常", e);
        } finally {
            isProcessing.set(false);
        }
    }

    private void updateStatusText(String status, Color color, String debug) {
        // 性能优化：如果用户关闭了显示，直接跳过 UI 更新逻辑
        if (!infoContainer.isVisible()) return;

        long now = System.currentTimeMillis();
        if (now - lastUiUpdateTime < 1000) return;

        lastUiUpdateTime = now;
        Platform.runLater(() -> {
            statusLabel.setText(status);
            statusLabel.setTextFill(color);
            if (debug != null) debugLabel.setText(debug);
        });
    }

    @Override
    public void stop() {
        if (windowsMonitor != null) windowsMonitor.stopMonitor();
        log.info("应用退出");
    }
}