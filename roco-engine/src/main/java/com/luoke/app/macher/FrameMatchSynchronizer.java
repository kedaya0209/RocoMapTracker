package com.luoke.app.macher;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

/**
 * 帧匹配同步器 — 生产者-消费者 wait/notify 同步模式。
 *
 * <p>消费者线程调用 {@link #awaitResult(long)} 阻塞等待；
 * 生产者线程（Socket recv）调用 {@link #complete} 唤醒；
 * 断连时调用 {@link #failAndWake} 写入 FAIL 并唤醒。
 *
 * <p>单消费者假设：同一时刻只允许一个线程调用 {@code awaitResult}。
 */
@ThreadSafe
@Slf4j
public class FrameMatchSynchronizer {

    private final Object lock = new Object();
    private volatile SiftMatchProtocol.MatchResult pendingResult;

    /**
     * 生产者端：收到匹配结果时调用，唤醒等待线程。
     */
    public void complete(SiftMatchProtocol.MatchResult result) {
        synchronized (lock) {
            pendingResult = result;
            lock.notify();
        }
    }

    /**
     * 消费者端：阻塞等待匹配结果。
     *
     * @param timeoutMs 超时毫秒数
     * @return 匹配结果，超时返回 {@link SiftMatchProtocol.MatchResult#FAIL}
     * @throws InterruptedException 线程被中断
     */
    public SiftMatchProtocol.MatchResult awaitResult(long timeoutMs) throws InterruptedException {
        synchronized (lock) {
            pendingResult = null;
            long deadline = System.currentTimeMillis() + timeoutMs;
            long remaining;
            while (pendingResult == null && (remaining = deadline - System.currentTimeMillis()) > 0) {
                lock.wait(remaining);
            }
            if (pendingResult != null) {
                return pendingResult;
            }
        }
        log.warn("匹配结果超时 {}ms", timeoutMs);
        return SiftMatchProtocol.MatchResult.FAIL;
    }

    /**
     * 断连时调用：写入 FAIL 结果并唤醒等待线程，防止死等。
     */
    public void failAndWake() {
        synchronized (lock) {
            pendingResult = SiftMatchProtocol.MatchResult.FAIL;
            lock.notify();
        }
    }
}
