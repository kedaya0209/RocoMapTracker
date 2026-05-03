package com.luoke.app.capture.processor.impl;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.ResourceContext;
import com.luoke.app.context.StatsContext;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class MapMatcherProcessor implements RoiProcessor, AutoCloseable {

    private final int targetRoiIndex;
    private final MapMatcher mapMatcher;
    private final ArrowDetector arrowDetector;
    private final StatsContext stats = StatsContext.getInstance();
    private final ROIData cachedRoi = new ROIData(8900, 700, 1000, 1800);

    private final ExecutorService matchExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r, "MapMatch-Worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    private final long delay = 1000 / AppConfig.TARGET_CAPTURE_FPS;
    private long prevTime = 0L;

    private final int sw = 120;
    // --- 缓存 Mats ---
    private Mat grayMat, smallGray, blurMat, circles;
    private double detectCenterX, detectCenterY;
    private int detectRadius;
    private double scale;
    private byte[] smallGrayData;

    private final AtomicBoolean isMapLost = new AtomicBoolean(false);
    private int consecutiveFailureCount = 0;

    public MapMatcherProcessor(int targetRoiIndex) {
        this.targetRoiIndex = targetRoiIndex;
        this.mapMatcher = SwitchMapMatcher.getInstance();
        this.mapMatcher.init(ResourceContext.getSiftMap());
        this.arrowDetector = ArrowDetector.getInstance();
        try {
            this.arrowDetector.init();
        } catch (Exception e) {
            log.error("初始化失败", e);
        }
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        long now = System.currentTimeMillis();
        if (now - prevTime < delay) return;
        prevTime = now;

        // 必须 clone，因为 data 是外部缓冲区复用的，不 clone 进线程会被下一帧覆盖
        byte[] taskData = data.clone();
        matchExecutor.execute(() -> executeMatching(taskData, width, height));
    }

    private void executeMatching(byte[] data, int width, int height) {
        stats.onFrameProcessed();
        long tStart = System.currentTimeMillis();

        // 1. 检测前置校验
        if (!trackOrDetectMiniMap(data, width, height)) {
            handleMatchFailure("锁定丢失");
            stats.reset();
            stats.recordMapDetect(System.currentTimeMillis() - tStart);
            return;
        }
        stats.recordMapDetect(System.currentTimeMillis() - tStart);

        // 3. 执行 Mask
        applyFastCircleMask(data, width, height);

        long detStart = System.currentTimeMillis();
        // 4. 角色与 SIFT 匹配
        Player player = arrowDetector.detectPlayer(data, width, height);
        long matchStart = System.currentTimeMillis();
        stats.recordDirection(matchStart - detStart);
        double[][] worldCoords = mapMatcher.match(data, width, height);
        stats.recordMatch(System.currentTimeMillis() - matchStart);

        if (worldCoords != null && worldCoords.length > 0) {
            handleMatchSuccess(worldCoords[0], player.getAngle());
        } else {
            handleMatchFailure("特征点不足");
        }
    }

    private boolean trackOrDetectMiniMap(byte[] data, int w, int h) {
        initMats(w, h);

        if (circles != null) {
            circles.release();
        }
        circles = new Mat();

        grayMat.put(0, 0, data);
        Imgproc.resize(grayMat, smallGray, smallGray.size());

        // 确保数据刷新到字节数组
        smallGray.get(0, 0, smallGrayData);
        Imgproc.medianBlur(smallGray, blurMat, 5);

        int minSide = Math.min(smallGray.cols(), smallGray.rows());
        Imgproc.HoughCircles(blurMat, circles, Imgproc.HOUGH_GRADIENT,
                1.2, minSide * 0.6, 50, 35,
                (int) (minSide * 0.4), (int) (minSide * 0.55));

        // 检查是否真的探测到了列
        if (circles.empty() || circles.cols() == 0) {
            return false;
        }

        double[] c = circles.get(0, 0);
        double detCx = c[0], detCy = c[1], detR = c[2];

        int blackCount = 0;
        for (int i = 0; i < 120; i++) {
            double theta = Math.toRadians(i * 3.0);
            int sx = (int) (detCx + detR * Math.cos(theta));
            int sy = (int) (detCy + detR * Math.sin(theta));
            if (sx >= 0 && sx < sw && sy >= 0 && sy < smallGray.rows()) {
                if ((smallGrayData[sy * sw + sx] & 0xFF) < 150) blackCount++;
            }
        }

        double distToCenter = Math.sqrt(Math.pow(detCx - sw / 2.0, 2) + Math.pow(detCy - smallGray.rows() / 2.0, 2));

        if ((double) blackCount / 120 > 0.15 && distToCenter < (minSide * 0.2)) {
            detectCenterX = detCx / scale;
            detectCenterY = detCy / scale;
            detectRadius = (int) (detR / scale);
            return true;
        }
        return false;
    }

    private void applyFastCircleMask(byte[] data, int w, int h) {
        double r2 = (double) detectRadius * detectRadius;
        for (int y = 0; y < h; y++) {
            int offset = y * w;
            for (int x = 0; x < w; x++) {
                double dx = x - detectCenterX;
                double dy = y - detectCenterY;
                if (dx * dx + dy * dy > r2) {
                    data[offset + x] = 0;
                }
            }
        }
    }

    private void initMats(int w, int h) {
        if (grayMat == null || grayMat.cols() != w || grayMat.rows() != h) {
            releaseDetectMats();
            scale = (double) sw / w;
            int sh = (int) (h * scale);
            grayMat = new Mat(h, w, CvType.CV_8UC1);
            smallGray = new Mat(sh, sw, CvType.CV_8UC1);
            smallGrayData = new byte[sw * sh];
            blurMat = new Mat(sh, sw, CvType.CV_8UC1);
        }
    }

    private void handleMatchSuccess(double[] pos, double angle) {
        consecutiveFailureCount = 0;
        isMapLost.set(false);
        MapContext.getInstance().updatePlayerState(pos[0], pos[1], angle);
    }

    private void handleMatchFailure(String reason) {
        consecutiveFailureCount++;
        // 只有连续多次失败才标记 Lost，防止偶发波动导致 UI 闪烁
        if (consecutiveFailureCount > 5) {
            isMapLost.set(true);
            MapContext.getInstance().updatePlayerState(-1, -1, 0);
        }
    }

    @Override
    public int targetRoiIndex() {
        return targetRoiIndex;
    }

    @Override
    public ROIData getRoi() {
        return cachedRoi;
    }

    private void releaseDetectMats() {
        if (grayMat != null) grayMat.release();
        if (smallGray != null) smallGray.release();
        if (blurMat != null) blurMat.release();
        if (circles != null) circles.release();
        grayMat = null;
        smallGray = null;
        blurMat = null;
        circles = null;
    }

    @Override
    public void close() {
        matchExecutor.shutdownNow();
        releaseDetectMats();
        if (arrowDetector != null) {
            arrowDetector.release();
        }
        if (mapMatcher != null) {
            mapMatcher.destroy();
        }
    }
}