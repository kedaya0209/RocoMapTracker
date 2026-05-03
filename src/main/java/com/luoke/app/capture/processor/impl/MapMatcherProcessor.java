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
import com.luoke.app.utils.CoordinateTransformer;
import javafx.scene.paint.Color;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.BiConsumer;

@Slf4j
public class MapMatcherProcessor implements RoiProcessor {

    private final int targetRoiIndex;
    private final MapMatcher mapMatcher;
    private final ArrowDetector arrowDetector;
    private final StatsContext stats = StatsContext.getInstance();
    private final BiConsumer<String, Color> statusUpdateHandler;

    private final ROIData cachedRoi = new ROIData(8900, 800, 1000, 1500);
    private final Semaphore parallel = new Semaphore(1);

    private final long delay = 1000 / AppConfig.TARGET_CAPTURE_FPS;
    private final ExecutorService siftExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "Sift-Async-Worker");
        t.setDaemon(true);
        return t;
    });
    private long prevTime = 0L;

    public MapMatcherProcessor(int targetRoiIndex, BiConsumer<String, Color> statusHandler) {
        this.targetRoiIndex = targetRoiIndex;
        this.statusUpdateHandler = statusHandler;
        this.mapMatcher = SwitchMapMatcher.getInstance();
        this.mapMatcher.init(ResourceContext.getSiftMap());
        this.arrowDetector = ArrowDetector.getInstance();
        try {
            arrowDetector.init();
        } catch (Exception e) {
            log.error("玩家朝向服务初始化失败,e", e);
        }
    }

    @Override
    public int targetRoiIndex() {
        return this.targetRoiIndex;
    }

    @Override
    public ROIData getRoi() {
        return cachedRoi;
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        long now = System.currentTimeMillis();

        // 1. 粗粒度频率控制：还没到预设间隔，直接丢弃
        if (now - prevTime < delay) return;

        // 2. 线程安全性控制：如果上一个 SIFT 还在跑，直接丢弃（不排队，保证实时性）
        if (!parallel.tryAcquire()) return;

        // 【关键】一旦成功抢占，立即记录时间点，保证频率相对稳定
        prevTime = now;

        siftExecutor.submit(() -> {
            try {
                processAsync(data, width, height);
            } catch (Exception e) {
                log.error("SIFT 异步链路异常", e);
            } finally {
                parallel.release();
            }
        });
    }

    private void processAsync(byte[] data, int width, int height) {
        stats.onFrameProcessed();

        // --- Step 1: 箭头检测 (使用原始数据) ---
        // 箭头通常在中心，不受后续 mask 影响，但先检测可以作为 SIFT 的“开关”
        long t2 = System.currentTimeMillis();
        Player player = arrowDetector.detectPlayer(data, width, height);
        stats.recordDirection(System.currentTimeMillis() - t2);

        if (!player.isFound()) {
            stats.recordMatch(0);
            return;
        }

        // --- Step 2: 掩码处理 ---
        // SIFT 只需要圆形区域内的特征，消除外部 UI 干扰
        maskCircleOptimized(data, width, height);

        // --- Step 3: SIFT 匹配 ---
        long t1 = System.currentTimeMillis();
        double[][] worldCoords = mapMatcher.match(data, width, height);
        stats.recordMatch(System.currentTimeMillis() - t1);

        if (worldCoords == null) {
            notifyStatus(AppConfig.STATUS_MATCH_FAILED, Color.RED);
            return;
        }

        // --- Step 4: 数据更新 ---
        double[] pos = worldCoords[0];
        MapContext.getInstance().updatePlayerState(pos[0], pos[1], player.getAngle());
        CoordinateTransformer.updatePositionSmoothly(pos[0], pos[1], 0.5);

        notifyStatus(AppConfig.STATUS_RUNNING, Color.LIGHTGREEN);
    }

    private void maskCircleOptimized(byte[] data, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        int r = Math.min(cx, cy) - 2;
        int rSq = r * r;
        for (int y = 0; y < h; y++) {
            int dy = y - cy;
            int dy2 = dy * dy;
            int yOff = y * w;
            if (dy2 > rSq) {
                Arrays.fill(data, yOff, yOff + w, (byte) 0);
            } else {
                int dx = (int) Math.sqrt(rSq - dy2);
                int minX = cx - dx;
                int maxX = cx + dx;
                if (minX > 0) Arrays.fill(data, yOff, yOff + minX, (byte) 0);
                if (maxX < w - 1) Arrays.fill(data, yOff + maxX + 1, yOff + w, (byte) 0);
            }
        }
    }

    private void notifyStatus(String msg, Color color) {
        if (statusUpdateHandler != null) statusUpdateHandler.accept(msg, color);
    }
}