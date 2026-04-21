package com.luoke.capture;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WindowsMonitor {
    private final WindowCaptureContext context;
    private volatile boolean isMonitoring = false;

    // 每帧间隔毫秒数 = 1000 / 目标FPS（从全局配置读取）
    private static final long FRAME_INTERVAL_MS = 1000 / AppConfig.TARGET_CAPTURE_FPS;

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
     * 基于 FPS 限流器的轮询模式
     * 已删除 delayMs 参数，自动使用配置文件中的目标帧率
     */
    public synchronized void startMonitorPoll(WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
        if (isMonitoring || !context.init()) return;
        isMonitoring = true;
        Thread.ofVirtual().name("monitor-poll").start(() -> {
            try {
                while (isMonitoring && !Thread.currentThread().isInterrupted()) {
                    long start = System.currentTimeMillis();

                    // 捕获帧
                    WGCCapture.Frame frame = context.captureFrameBytes();
                    if (frame != null) {
                        try {
                            callBack.call(frame);
                        } catch (Exception e) {
                            log.error("拉取回调发生异常: ", e);
                        }
                    }

                    // ====================== FPS 限流器 ======================
                    long cost = System.currentTimeMillis() - start;
                    long sleepTime = Math.max(1, FRAME_INTERVAL_MS - cost);
                    Thread.sleep(sleepTime);

                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                log.error("监控线程被中断", ignored);
            } finally {
                isMonitoring = false;
            }
        });
    }

    public boolean isRunning() {
        return isMonitoring;
    }
}