package com.luoke.app.capture.processor;

import com.luoke.app.capture.CaptureFrameBuffer;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.context.StatsContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.StatusCarouselEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.PlayerStateTracker;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 地图匹配处理器 — 通过独立 C++ 进程 (sift_match.exe) 执行 SIFT 匹配。
 *
 * <p>架构变更:
 * <pre>
 *   旧: JavaCPP (SIFT/FLANN/HoughCircles 内存泄漏) → Java 进程内
 *   新: 独立 C++ sift_match.exe → Socket → 零 JavaCPP 依赖
 * </pre>
 *
 * <p>每帧流程: 接收灰度图 → 发送给 C++ → C++ 完成小地图检测+遮罩+箭头+SIFT匹配 → 返回坐标+角度
 */
@Slf4j
public class MapMatcherProcessor implements RoiProcessor, AutoCloseable {

    private final int targetRoiIndex;
    //roi h为0, 自动截取正方形
    private final ROIData cachedRoi = new ROIData(SiftConfig.ROI_MAP_X, SiftConfig.ROI_MAP_Y, SiftConfig.ROI_MAP_W, SiftConfig.ROI_MAP_H);
    // 独立进程匹配客户端
    private final SiftMatchHandler matchClient;
    // 状态追踪 (纯 Java, 无 native 依赖)
    private final PlayerStateTracker stateTracker = new PlayerStateTracker();

    // 频率限制
    private final long delay = 1000L / CaptureConfig.TARGET_CAPTURE_FPS;
    // 统计
    private final StatsContext stats = StatsContext.getInstance();
    // 门控：上一帧匹配未完成时跳过当前帧，防止并发调用超时
    private final AtomicBoolean matching = new AtomicBoolean(false);
    private long prevTime = 0L;
    private boolean arrowInit = false;
    private volatile Double lastDetectedAngle = null;
    /** 上一帧小地图丢失状态，用于检测状态变化时发布轮播事件 */
    private boolean wasMapLost = true;

    public MapMatcherProcessor(int targetRoiIndex, SiftMatchHandler matchClient) {
        this.targetRoiIndex = targetRoiIndex;
        this.matchClient = matchClient;
        try {
            ArrowDetector.getInstance().init();
            arrowInit = true;
        } catch (Exception ignore) {
        }
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        CaptureFrameBuffer.getInstance().putFrame(targetRoiIndex, data, width, height);
        long now = System.currentTimeMillis();
        if (now - prevTime < delay) return;
        prevTime = now;
        if (arrowInit) Thread.startVirtualThread(() -> executeArrowDect(data, width, height));
        Thread.startVirtualThread(() -> executeMatching(data, width, height));
    }

    private void executeArrowDect(byte[] data, int width, int height) {
        // 截取中心区域用于箭头 CNN 检测
        long start = System.currentTimeMillis();
        int cs = SiftConfig.ARROW_CROP_SIZE;
        byte[] dest = new byte[cs * cs];
        int half = cs / 2;
        int offsetX = width / 2 - half;
        int offsetY = height / 2 - half;
        for (int i = 0; i < cs; i++) {
            System.arraycopy(data, (offsetY + i) * width + offsetX, dest, i * cs, cs);
        }
        lastDetectedAngle = ArrowDetector.getInstance().detectPlayer(dest, cs, cs);
        stats.recordDirection(System.currentTimeMillis() - start);
    }

    private void executeMatching(byte[] data, int width, int height) {
        // 门控：上一帧匹配未完成时跳过，防止多帧并发导致超时
        if (!matching.compareAndSet(false, true)) {
            return;
        }
        try {
            long tStart = System.currentTimeMillis();
            stats.onFrameProcessed();

            // 获取预测位置 (首帧/地图丢失时为 NaN)
            Double hintX = stateTracker.getPredictedX();
            Double hintY = stateTracker.getPredictedY();

            try {
                SiftMatchHandler.MatchResult result = matchClient.sendFrameAndWait(
                        data, width, height,
                        hintX != null ? hintX : Double.NaN,
                        hintY != null ? hintY : Double.NaN,
                        SiftConfig.MATCH_TIMEOUT_MS);

                long elapsed = System.currentTimeMillis() - tStart;
                stats.recordMatch(elapsed);
                // 记录 C++ 端分段耗时（小地图检测/特征提取/FLANN匹配）
                stats.recordSiftTimings(result.tMinimapMs(), result.tExtractMs(), result.tFlannMs());

                if (result.success()) {
                    stateTracker.onMatchSuccess(result.x(), result.y(), lastDetectedAngle);
                } else {
                    stateTracker.onMatchFailure("C++ match failed");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateTracker.onMatchFailure("interrupted");
            } catch (Exception e) {
                log.error("匹配异常", e);
                stateTracker.onMatchFailure("exception: " + e.getMessage());
            }

            // 检测小地图丢失/恢复状态变化，发布轮播事件
            boolean mapLost = stateTracker.isMapLost();
            if (mapLost && !wasMapLost) {
                HookRegistry.INSTANCE.publish(HookEventType.STATUS_CAROUSEL,
                        StatusCarouselEvent.minimapLost());
            } else if (!mapLost && wasMapLost) {
                HookRegistry.INSTANCE.publish(HookEventType.STATUS_CAROUSEL,
                        StatusCarouselEvent.minimapFound());
            }
            wasMapLost = mapLost;
        } finally {
            matching.set(false);
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
        log.info("MapMatcherProcessor closed");
    }
}
