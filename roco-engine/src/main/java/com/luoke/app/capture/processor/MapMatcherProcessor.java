package com.luoke.app.capture.processor;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.StatsContext;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.PlayerStateTracker;
import lombok.extern.slf4j.Slf4j;

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
    private final ROIData cachedRoi = new ROIData(8900, 300, 1000, 0);

    // 超时配置
    private static final long MATCH_TIMEOUT_MS = 500;
    // 独立进程匹配客户端
    private final SiftMatchHandler matchClient;
    // 状态追踪 (纯 Java, 无 native 依赖)
    private final PlayerStateTracker stateTracker = new PlayerStateTracker();

    // 频率限制
    private final long delay = 1000L / AppConfig.TARGET_CAPTURE_FPS;
    private long prevTime = 0L;

    private boolean arrowInit = false;
    private volatile Double lastDetectedAngle = null;

    // 统计
    private final StatsContext stats = StatsContext.getInstance();

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
        long now = System.currentTimeMillis();
        if (now - prevTime < delay) return;
        prevTime = now;
        if (arrowInit) Thread.startVirtualThread(() -> executeArrowDect(data, width, height));
        Thread.startVirtualThread(() -> executeMatching(data, width, height));
    }

    private void executeArrowDect(byte[] data, int width, int height) {
        // 截取中心64*64区域
        byte[] dest = new byte[64 * 64];
        int offsetX = width / 2 - 32;
        int offsetY = height / 2 - 32;
        for (int i = 0; i < 64; i++) {
            System.arraycopy(data, (offsetY + i) * width + offsetX, dest, i * 64, 64);
        }
        lastDetectedAngle = ArrowDetector.getInstance().detectPlayer(dest, 64, 64);
    }

    private void executeMatching(byte[] data, int width, int height) {
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
                    MATCH_TIMEOUT_MS);

            long elapsed = System.currentTimeMillis() - tStart;
            stats.recordMatch(elapsed);

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
        stateTracker.reset();
        log.info("MapMatcherProcessor closed");
    }
}
