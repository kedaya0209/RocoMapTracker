package com.luoke.app.processor;

import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.context.StatsContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.multicast.HookMulticaster;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.SiftMapMatcher;
import com.luoke.app.macher.minimap.MapTracker;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import com.luoke.app.ui.render.CutterPlayerRenderer;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
import javafx.scene.paint.Color;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

@Slf4j
public class CoreProcessor {
    @Getter
    private static final CoreProcessor instance = new CoreProcessor();
    private final MapTracker mapTracker = MapTracker.getInstance();
    private final StatsContext stats = StatsContext.getInstance();
    private final AtomicBoolean isMatcherReady = new AtomicBoolean(false);
    private MapMatcher mapMatcher;
    private WindowsMonitor windowsMonitor;
    private BiConsumer<String, Color> statusUpdateHandler;

    public void setStatusUpdateHandler(BiConsumer<String, Color> handler) {
        this.statusUpdateHandler = handler;
    }

    public void processFrame(Frame frame) {
        if (frame == null || !isMatcherReady.get()) return;
        stats.onFrameProcessed();
        try {
            HookMulticaster.getInstance().enqueue(HookEventType.FRAME_CAPTURED, frame);
            long t0 = System.currentTimeMillis();
            CaptureFrameRecord miniMap = mapTracker.getMiniMapImage(frame);
            stats.recordMapDetect(System.currentTimeMillis() - t0);

            if (miniMap == null) {
                notifyStatus(AppConfig.STATUS_MINIMAP_NOT_FOUND, Color.RED);
                return;
            }

            CutterPlayerRenderer.getInstance().updateArrow(miniMap);
            long t1 = System.currentTimeMillis();
            double[][] corners = mapMatcher.match(miniMap.bytes(), miniMap.width(), miniMap.height());
            stats.recordMatch(System.currentTimeMillis() - t1);

            if (corners == null || corners.length < 3) {
                notifyStatus(AppConfig.STATUS_MATCH_FAILED, Color.RED);
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
                notifyStatus(AppConfig.STATUS_PLAYER_NOT_FOUND, Color.ORANGE);
                return;
            }

            MapContext.getInstance().updatePlayerState(center[0], center[1], player.getAngle());
            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], AppConfig.COORDINATE_SMOOTH_FACTOR);
            notifyStatus(AppConfig.STATUS_RUNNING, Color.LIGHTGREEN);

        } catch (Exception e) {
            log.error("帧处理过程发生严重异常: ", e);
        }
    }

    public void preloadMatcherAsync() {
        Thread.ofVirtual().start(() -> {
            try {
                log.info("开始后台载入 SIFT 匹配引擎...");
                mapMatcher = new SiftMapMatcher();
                mapMatcher.init(AppConfig.MAP_RESOURCE_PATH);
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);
                isMatcherReady.set(true);
                log.info("匹配引擎就绪，监听窗口状态...");
                startCapture();
            } catch (Exception e) {
                log.error("SIFT 匹配引擎初始化失败", e);
            }
        });
    }

    private void startCapture() {
        windowsMonitor = new WindowsMonitor(AppConfig.TARGET_WINDOW_NAME);
        windowsMonitor.startMonitor(this::processFrame);
    }

    private void notifyStatus(String msg, Color color) {
        if (statusUpdateHandler != null) statusUpdateHandler.accept(msg, color);
    }

    public void shutdown() {
        if (windowsMonitor != null) windowsMonitor.stopMonitor();
        if (mapMatcher != null) mapMatcher.destroy();
    }
}