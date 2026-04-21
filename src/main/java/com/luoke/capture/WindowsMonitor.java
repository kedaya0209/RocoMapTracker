package com.luoke.capture;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WindowsMonitor {
    private final WindowCaptureContext context;
    private volatile boolean isMonitoring = false;

    // 从配置读取
    private static final String MONITOR_PATTERN = AppConfig.CAPTURE_MODE;
    private static final long FRAME_INTERVAL_MS = 1000 / AppConfig.TARGET_CAPTURE_FPS;

    private Thread listenerThread;

    public WindowsMonitor(String windowKeyword) {
        this.context = new WindowCaptureContext(windowKeyword);
    }

    /**
     * 停止监控
     */
    public synchronized void stopMonitor() {
        if (!isMonitoring) return;
        isMonitoring = false;
        try {
            if (listenerThread != null && !listenerThread.isInterrupted()) {
                //监听线程强制关闭
                listenerThread.interrupt();
            }
            context.close();
            log.info("监视器停止，资源释放成功");
        } catch (Exception e) {
            log.error("监视器停止失败", e);
        }
    }

    /**
     * 【模式1】主动轮询（Java 定时拉取）
     */
    public synchronized void startMonitorPoll(WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
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
                            log.error("帧回调异常", e);
                        }
                    }

                    long cost = System.currentTimeMillis() - start;
                    long sleepTime = Math.max(1, FRAME_INTERVAL_MS - cost);
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException e) {
                log.error("截图失败,e:", e);
                Thread.currentThread().interrupt();
            } finally {
                isMonitoring = false;
            }
        });
        log.info("已启动 → 【Java 主动轮询模式】");
    }

    /**
     * 【模式2】Rust 推送（等 Java 处理完再下一帧）
     */
    public synchronized void startMonitorPush(WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
        listenerThread = Thread.ofVirtual().start(() -> {
            if (isMonitoring || !context.init()) return;
            isMonitoring = true;

            context.startCaptureListener(frame -> {
                try {
                    if (frame != null) {
                        long start = System.currentTimeMillis();
                        callBack.call(frame);
                        long cost = System.currentTimeMillis() - start;
                        long sleepTime = Math.max(1, FRAME_INTERVAL_MS - cost);
                        Thread.sleep(sleepTime);
                    }
                } catch (Exception e) {
                    if (!(e instanceof InterruptedException)) {
                        log.error("帧处理异常", e);
                    }
                }
            });
            log.info("已启动 → 【Rust 高性能推送模式】");
        });
    }

    // ====================== 【核心：配置决定模式】 ======================

    /**
     * 根据配置文件自动选择监听模式
     * push  = Rust 推送（低延迟、高性能、等Java处理完）
     * poll  = Java 轮询（定时拉取）
     */
    public synchronized void startMonitor(WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
        if (MONITOR_PATTERN == null) {
            startMonitorPoll(callBack);
            return;
        }

        String mode = MONITOR_PATTERN.trim().toLowerCase();
        switch (mode) {
            case "push":
                startMonitorPush(callBack);
                break;
            case "poll":
                startMonitorPoll(callBack);
                break;
            default:
                log.warn("未知监控模式: {}, 默认使用拉取模式", mode);
                startMonitorPoll(callBack);
        }
    }

    public boolean isRunning() {
        return isMonitoring;
    }
}