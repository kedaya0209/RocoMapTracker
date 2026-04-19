package com.luoke.capture;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WindowsMonitor {
    private final WindowCaptureContext context;
    private volatile boolean isMonitoring = false;

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
            context.close();
            log.info("监视器停止，释放资源成功");
        } catch (Exception e) {
            log.error("监视器停止失败 ", e);
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
                            log.error("拉取回调发生异常: ", e);
                        }
                    }
                    long cost = System.currentTimeMillis() - start;
                    long sleepTime = Math.max(1, delayMs - cost); // 至少休息 1ms
                    Thread.sleep(sleepTime);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                log.error("遇到未知错误,e", ignored);
            } finally {
                isMonitoring = false;
            }
        });
    }

    public boolean isRunning() {
        return isMonitoring;
    }
}