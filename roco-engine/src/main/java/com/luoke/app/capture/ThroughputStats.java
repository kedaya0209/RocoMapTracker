package com.luoke.app.capture;

import net.jcip.annotations.NotThreadSafe;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 帧吞吐量统计 — 帧计数 + 字节累计 + 每 10s 诊断输出。
 *
 * <p>从 {@link CaptureHandler#handleFrameData} 中抽取，职责单一。
 */
@NotThreadSafe
@Slf4j
public class ThroughputStats {

    @Getter
    private long frameCount;
    private long totalBytes;
    private long lastStatsTime;

    /**
     * 记录一帧的字节数，每 10s 输出一次诊断日志。
     */
    public void recordFrame(int bytes) {
        frameCount++;
        totalBytes += bytes;

        long now = System.currentTimeMillis();
        if (lastStatsTime == 0) lastStatsTime = now;
        if (now - lastStatsTime > 10000) {
            double mbps = totalBytes / (1024.0 * 1024.0) / ((now - lastStatsTime) / 1000.0);
            log.debug("帧数: {}, 速率: {} MB/s", frameCount, mbps);
            totalBytes = 0;
            lastStatsTime = now;
        }
    }
}
