package com.luoke.app.capture.pipeline;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 匹配看门狗 — CAS 门控 + 超时强制复位。
 *
 * <p>职责：
 * <ul>
 *   <li>{@link #tryStart()} — CAS 门控，上一帧匹配未完成时返回 false（跳过当前帧）</li>
 *   <li>{@link #finish()} — 匹配完成时复位</li>
 *   <li>{@link #checkTimeout(long)} — 看门狗检查，超过阈值强制复位</li>
 * </ul>
 *
 * <p>从 {@link MapMatcherProcessor} 中抽取，使并发控制 + 超时保护逻辑独立可测试。
 */
@ThreadSafe
@Slf4j
public class MatchingWatchdog {

    private final AtomicBoolean matching = new AtomicBoolean(false);
    private volatile long matchingSince = 0L;
    private final long timeoutMs;

    public MatchingWatchdog(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * 尝试开始匹配（CAS 门控）。
     *
     * @return true 表示成功获取锁，false 表示上一帧仍在匹配中
     */
    public boolean tryStart() {
        if (!matching.compareAndSet(false, true)) {
            return false;
        }
        matchingSince = System.currentTimeMillis();
        return true;
    }

    /**
     * 匹配完成，复位状态。
     */
    public void finish() {
        matching.set(false);
        matchingSince = 0L;
    }

    /**
     * 看门狗检查 — 若匹配卡住超过阈值则强制复位。
     *
     * @param frameSeq 当前帧序列号（用于日志）
     */
    public void checkTimeout(long frameSeq) {
        if (matching.get()) {
            long since = matchingSince;
            if (since != 0L && System.currentTimeMillis() - since > timeoutMs) {
                log.warn("matching 卡住超过 {}ms (seq={})，强制复位", timeoutMs, frameSeq);
                matching.set(false);
            }
        }
    }

    /**
     * 当前是否正在匹配中。
     */
    public boolean isMatching() {
        return matching.get();
    }
}
