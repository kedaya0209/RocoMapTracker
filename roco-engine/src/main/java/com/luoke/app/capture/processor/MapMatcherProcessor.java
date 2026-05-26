package com.luoke.app.capture.processor;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.capture.CaptureFrameBuffer;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.context.StatsContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.StatusCarouselEvent;
import com.luoke.app.hook.event.StatusStateMachine;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.macher.SiftMatchProtocol;
import com.luoke.app.macher.player.PlayerStateTracker;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

/**
 * 地图匹配处理器 — 通过独立 C++ 进程 (sift_match.exe) 执行 SIFT 匹配。
 *
 * <p>架构变更:
 * <pre>
 *   旧: JavaCPP (SIFT/FLANN/HoughCircles 内存泄漏) → Java 进程内
 *   新: 独立 C++ sift_match.exe → Socket → 零 JavaCPP 依赖
 * </pre>
 *
 * <p>每帧流程: 接收 BGRA 全彩图 → 发送给 C++ → C++ 完成灰度转换+小地图检测+遮罩+SIFT匹配+HSV箭头检测 → 返回坐标+角度
 */
@NotThreadSafe
@Slf4j
public class MapMatcherProcessor implements RoiProcessor, AutoCloseable {

    private final int targetRoiIndex;
    private final ROIData cachedRoi = new ROIData(SiftConfig.ROI_MAP_X, SiftConfig.ROI_MAP_Y, SiftConfig.ROI_MAP_W, SiftConfig.ROI_MAP_H);
    private final SiftMatchHandler matchClient;
    private final PlayerStateTracker stateTracker;
    private final CaptureFrameBuffer frameBuffer;
    private final StatsContext stats;
    private final BiConsumer<HookEventType, Object> hookPublisher;
    private final MatchingWatchdog watchdog;

    // 专用单线程池，避免 Substrate VM 虚拟线程 Continuation bug (RIP=0 DEP)
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "matcher-worker");
        t.setDaemon(true);
        return t;
    });
    // 频率限制
    private final long delay = 1000L / CaptureConfig.TARGET_CAPTURE_FPS;
    private long prevTime = 0L;
    /** 帧序列号，用于诊断追踪 */
    private long frameSeq = 0L;
    /** 上次匹配开关状态，用于侦测切换时发布事件 */
    private boolean wasMatchingEnabled = true;

    public MapMatcherProcessor(int targetRoiIndex, SiftMatchHandler matchClient,
                                CaptureFrameBuffer frameBuffer,
                                StatsContext stats,
                                BiConsumer<HookEventType, Object> hookPublisher,
                                PlayerStateTracker stateTracker) {
        this.targetRoiIndex = targetRoiIndex;
        this.matchClient = matchClient;
        this.frameBuffer = frameBuffer;
        this.stats = stats;
        this.hookPublisher = hookPublisher;
        this.stateTracker = stateTracker;
        this.wasMatchingEnabled = SiftConfig.SIFT_MATCHING_ENABLED;
        long watchdogTimeout = Math.max(3000L, SiftConfig.MATCH_TIMEOUT_MS * 3);
        this.watchdog = new MatchingWatchdog(watchdogTimeout);
    }

    @Override
    public ImageType requiredImageType() {
        return ImageType.BGRA; // C++ 侧需要全彩图进行 HSV 箭头方向检测
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        frameBuffer.putFrame(targetRoiIndex, data, width, height);
        long now = System.currentTimeMillis();
        if (now - prevTime < delay) return;
        prevTime = now;
        frameSeq++;

        // 匹配开关变化时发布轮播事件（仅在合法源状态下转换）
        boolean enabled = SiftConfig.SIFT_MATCHING_ENABLED;
        if (enabled != wasMatchingEnabled) {
            wasMatchingEnabled = enabled;
            StatusStateMachine.State matchState = StatusStateMachine.getInstance()
                    .currentState(StatusStateMachine.StatusKey.MATCH);
            StatusCarouselEvent event = enabled
                    ? StatusCarouselEvent.matchingResumed()
                    : (matchState == StatusStateMachine.State.ACTIVE
                            ? StatusCarouselEvent.matchingPaused() : null);
            if (event != null) {
                hookPublisher.accept(HookEventType.STATUS_CAROUSEL, event);
            }
        }
        if (!enabled) return;

        // 看门狗：matching 卡住超时时强制复位
        watchdog.checkTimeout(frameSeq);

        executor.submit(() -> executeMatching(data, width, height));
    }

    private void executeMatching(byte[] data, int width, int height) {
        if (!watchdog.tryStart()) {
            return;
        }
        final long seq = frameSeq;
        if (log.isTraceEnabled()) {
            log.trace("[seq={}] executeMatching enter, size={}x{}", seq, width, height);
        }
        try {
            long tStart = System.currentTimeMillis();
            stats.onFrameProcessed();

            // 获取预测位置 (首帧/重置后为 null)
            Double hintX = stateTracker.getPredictedX();
            Double hintY = stateTracker.getPredictedY();

            try {
                SiftMatchProtocol.MatchResult result = matchClient.sendFrameAndWait(
                        data, width, height,
                        hintX != null ? hintX : Double.NaN,
                        hintY != null ? hintY : Double.NaN,
                        SiftConfig.MATCH_TIMEOUT_MS);

                long elapsed = System.currentTimeMillis() - tStart;
                stats.recordMatch(elapsed);
                // 记录 C++ 端分段耗时（小地图检测/特征提取/FLANN匹配/箭头方向）
                stats.recordSiftTimings(result.tMinimapMs(), result.tExtractMs(), result.tFlannMs());
                stats.recordDirection((long) result.tArrowMs());

                if (result.success()) {
                    double angle = AngleConverter.toJavaFX(result.angle());
                    stateTracker.onMatchSuccess(result.x(), result.y(),
                        Double.isNaN(angle) ? null : angle);
                    if (log.isTraceEnabled()) {
                        log.trace("[seq={}] match OK: ({},{}), angle={}, elapsed={}ms",
                                seq, String.format("%.1f", result.x()),
                                String.format("%.1f", result.y()),
                                String.format("%.1f", result.angle()), elapsed);
                    }
                } else {
                    stateTracker.onMatchFailure("C++ match failed");
                    if (log.isTraceEnabled()) {
                        log.trace("[seq={}] match FAILED, elapsed={}ms", seq, elapsed);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateTracker.onMatchFailure("interrupted");
                log.warn("[seq={}] matching interrupted", seq);
            } catch (Exception e) {
                // 匹配管道中含多种未检查异常，保留通用捕获
                log.error("[seq={}] 匹配异常", seq, e);
                stateTracker.onMatchFailure("exception: " + e.getMessage());
            }

        } finally {
            watchdog.finish();
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
        executor.shutdownNow();
        log.info("MapMatcherProcessor 已关闭");
    }
}
