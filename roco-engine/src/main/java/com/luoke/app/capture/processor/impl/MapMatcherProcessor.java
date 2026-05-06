package com.luoke.app.capture.processor.impl;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.context.StatsContext;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.macher.miniMap.CircleMaskApplier;
import com.luoke.app.macher.miniMap.MiniMapDetector;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.PlayerStateTracker;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.*;

@Slf4j
public class MapMatcherProcessor implements RoiProcessor, AutoCloseable {

    private final int targetRoiIndex;
    private final ROIData cachedRoi = new ROIData(8900, 700, 1000, 1800);

    // 核心依赖
    private final MapMatcher mapMatcher;
    private final ArrowDetector arrowDetector;
    private final MiniMapDetector miniMapDetector;
    private final PlayerStateTracker stateTracker = new PlayerStateTracker();

    // 并发控制：同一时刻只有一个匹配任务执行
    private final Semaphore matchSemaphore = new Semaphore(1);

    // 计算密集型任务专用线程池：单线程 + SynchronousQueue，拒绝策略为 AbortPolicy（默认）
    private final ExecutorService matchExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("match-worker");
                return t;
            });

    private final ExecutorService detectExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("detect-worker");
                return t;
            });

    // 频率限制
    private final long delay = 1000L / AppConfig.TARGET_CAPTURE_FPS;
    private long prevTime = 0L;

    // 统计
    private final StatsContext stats = StatsContext.getInstance();

    // 小地图检测结果（当前帧有效）
    private volatile double detectCenterX, detectCenterY;
    private volatile int detectRadius;
    private volatile boolean hasValidCircle = false;

    public MapMatcherProcessor(int targetRoiIndex) {
        this.targetRoiIndex = targetRoiIndex;

        this.mapMatcher = SwitchMapMatcher.getInstance();
        this.mapMatcher.init(ResourceConfigContext.getSiftMap());
        this.arrowDetector = ArrowDetector.getInstance();
        this.miniMapDetector = new MiniMapDetector();
        try {
            this.arrowDetector.init();
        } catch (Exception e) {
            log.error("方向检测引擎初始化失败", e);
        }
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        long now = System.currentTimeMillis();
        if (now - prevTime < delay) return;
        prevTime = now;

        if (matchSemaphore.tryAcquire()) {
            Thread.startVirtualThread(() -> {
                try {
                    executeMatching(data, width, height);
                } finally {
                    matchSemaphore.release();
                }
            });
        }
    }

    private void executeMatching(byte[] data, int width, int height) {
        long tStart = System.currentTimeMillis();
        stats.onFrameProcessed();

        // 1. 小地图检测
        MiniMapDetector.DetectionResult detection = miniMapDetector.detect(data, width, height);
        if (!detection.success) {
            handleMatchFailure("小地图锁定丢失");
            stats.recordMapDetect(System.currentTimeMillis() - tStart);
            return;
        }
        stats.recordMapDetect(System.currentTimeMillis() - tStart);

        // 保存圆心半径
        this.detectCenterX = detection.centerX;
        this.detectCenterY = detection.centerY;
        this.detectRadius = detection.radius;
        this.hasValidCircle = true;

        // 2. 应用圆形遮罩
        long maskStart = System.currentTimeMillis();
        CircleMaskApplier.applyMask(data, width, height, detectCenterX, detectCenterY, detectRadius);
        stats.recordCircleMask(System.currentTimeMillis() - maskStart);

        // 3. 并发执行地图匹配和方向检测
        //    从状态追踪器获取预测位置（首帧/地图丢失时为 null）
        Double hintX = stateTracker.getPredictedX();
        Double hintY = stateTracker.getPredictedY();

        CompletableFuture<double[]> matchFuture = null;
        CompletableFuture<Double> angleFuture = null;
        try {
            matchFuture = CompletableFuture.supplyAsync(() -> {
                long matchStart = System.currentTimeMillis();
                double[][] worldCoords = mapMatcher.match(data, width, height, hintX, hintY);
                // ROI 匹配失败且 hint 不为空 → 降级全图重试
                if (worldCoords == null && hintX != null) {
                    worldCoords = mapMatcher.match(data, width, height, null, null);
                }
                stats.recordMatch(System.currentTimeMillis() - matchStart);
                return (worldCoords != null && worldCoords.length > 0) ? worldCoords[0] : null;
            }, matchExecutor);

            angleFuture = CompletableFuture.supplyAsync(() -> {
                long t0 = System.currentTimeMillis();
                try {
                    Double angle = arrowDetector.detectPlayer(data, width, height);
                    if (angle != null) stats.recordDirection(System.currentTimeMillis() - t0);
                    return angle;
                } catch (Exception e) {
                    log.warn("方向检测失败", e);
                    return null;
                }
            }, detectExecutor);

            // 等待两个任务完成（最多 500ms）
            CompletableFuture.allOf(matchFuture, angleFuture).get(500, TimeUnit.MILLISECONDS);

            // 获取结果
            double[] matchPos = matchFuture.getNow(null);
            if (matchPos == null) {
                handleMatchFailure("特征匹配不足");
                return;
            }
            Double angle = angleFuture.get();

            // 4. 更新状态
            stateTracker.onMatchSuccess(matchPos[0], matchPos[1], angle);

        } catch (TimeoutException e) {
            log.warn("匹配或方向检测超时，取消残留任务");
            if (matchFuture != null) matchFuture.cancel(true);
            if (angleFuture != null) angleFuture.cancel(true);
            handleMatchFailure("处理超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handleMatchFailure("任务被中断");
        } catch (ExecutionException e) {
            log.error("并发任务执行异常", e);
            handleMatchFailure("任务异常: " + e.getCause().getMessage());
        } catch (RejectedExecutionException e) {
            // 当 worker 线程忙碌时，SynchronousQueue 会拒绝新任务
            log.warn("任务被拒绝，worker 线程繁忙", e);
            handleMatchFailure("任务被拒绝");
        } catch (Exception e) {
            log.error("匹配处理流异常", e);
            handleMatchFailure("异常: " + e.getMessage());
        }
    }

    private void handleMatchFailure(String reason) {
        stateTracker.onMatchFailure(reason);
        if (stateTracker.isMapLost()) {
            hasValidCircle = false;
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

    @Override
    public void close() {
        matchExecutor.shutdownNow();
        detectExecutor.shutdownNow();
        if (miniMapDetector != null) miniMapDetector.close();
        if (arrowDetector != null) arrowDetector.release();
        if (mapMatcher != null) mapMatcher.destroy();
    }
}