package com.luoke.capture;

import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

@Slf4j
public class WindowsMonitor {
    private final WindowCaptureContext context;
    private volatile boolean isMonitoring = false;

    // 使用原子引用代替阻塞队列，彻底实现“无锁覆盖”
    // 内存中永远只保留一个待处理的 Frame 对象，其余旧帧会被 GC 迅速回收
    private final AtomicReference<WGCCapture.Frame> latestFrame = new AtomicReference<>();

    public WindowsMonitor(String windowKeyword) {
        this.context = new WindowCaptureContext(windowKeyword);
    }

    /**
     * 高性能推送模式 (修复 OOM 版)
     */
    public synchronized void startMonitorPush(int minIntervalMs, WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
        if (isMonitoring) {
            log.warn("Monitor is already running.");
            return;
        }
        if (!context.init()) return;

        isMonitoring = true;
        latestFrame.set(null);

        // 1. 启动常驻消费者线程 (虚拟线程)
        Thread.ofVirtual().name("monitor-consumer").start(() -> {
            log.info("Capture consumer thread started.");
            long lastProcessedTime = 0;

            try {
                while (isMonitoring) {
                    // 获取并清空原子引用中的最新帧
                    WGCCapture.Frame frame = latestFrame.getAndSet(null);

                    if (frame == null) {
                        // 如果没有新数据，轻微休眠避免 CPU 狂飙，同时给虚拟线程调度留出空间
                        LockSupport.parkNanos(1_000_000L); // 1ms
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    // 频率控制
                    if (now - lastProcessedTime >= minIntervalMs) {
                        try {
                            callBack.call(frame);
                            lastProcessedTime = now;
                        } catch (Exception e) {
                            log.error("Business Callback Error: ", e);
                        }
                    }
                }
            } finally {
                log.info("Capture consumer thread exited.");
            }
        });

        // 2. 注册生产者逻辑 (Native 回调)
        // 建议在 startAsyncCapture 内部将 delayMs 设置为 minIntervalMs，从源头降频
        context.startAsyncCapture(frame -> {
            if (!isMonitoring) return;

            // 无视旧数据，直接覆盖。旧的 Frame 对象会被 GC 自动标记回收。
            latestFrame.set(frame);
        });

        log.info("Monitor started (Atomic Swap Mode).");
    }

    /**
     * 停止监控
     */
    public synchronized void stopMonitor() {
        if (!isMonitoring) return;
        isMonitoring = false;
        try {
            latestFrame.set(null); // 清空最后一帧引用
            context.close();
            log.info("Monitor stopped and resources released.");
        } catch (Exception e) {
            log.error("Error during stopMonitor: ", e);
        }
    }

    /**
     * 传统的轮询模式
     */
    public synchronized void startMonitorPoll(int delayMs, WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
        if (isMonitoring || !context.init()) return;

        isMonitoring = true;
        Thread.ofVirtual().name("monitor-poll").start(() -> {
            try {
                while (isMonitoring && !Thread.currentThread().isInterrupted()) {
                    long start = System.currentTimeMillis();
                    WGCCapture.Frame frame = context.captureFrameBytes();
                    if (frame != null) {
                        try {
                            callBack.call(frame);
                        } catch (Exception e) {
                            log.error("Poll Callback Error: ", e);
                        }
                    }
                    long cost = System.currentTimeMillis() - start;
                    long sleepTime = Math.max(1, delayMs - cost); // 至少休息 1ms
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                isMonitoring = false;
            }
        });
    }

    public boolean isRunning() {
        return isMonitoring;
    }
}