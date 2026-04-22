package com.luoke.app.capture;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WindowsMonitor {
    private static final long FRAME_DELAY = 1000 / AppConfig.TARGET_CAPTURE_FPS;
    private volatile boolean isMonitoring = false;
    private static final long RETRY_INTERVAL = 2000;
    private final String windowKeyword;
    private WGCCapture currentCapture;

    public WindowsMonitor(String windowKeyword) {
        this.windowKeyword = windowKeyword;
    }

    public synchronized void startMonitor(WindowCaptureEventCallBack<WGCCapture.Frame> callBack) {
        if (isMonitoring) return;
        isMonitoring = true;

        Thread.ofVirtual().start(() -> {
            while (isMonitoring) {
                try {
                    long hwnd = WindowFinder.findWindowByKeyword(windowKeyword);
                    if (hwnd == 0) {
                        log.warn("等待窗口 [{}]...", windowKeyword);
                        Thread.sleep(RETRY_INTERVAL);
                        continue;
                    }

                    WGCCapture capture = new WGCCapture(hwnd);
                    currentCapture = capture;
                    log.info("已连接窗口: {}", hwnd);

                    final long[] lastFrameTime = {0L};

                    // ✅ 启动 PUSH
                    capture.startLoop(data -> {
                        long now = System.currentTimeMillis();
                        if (now - lastFrameTime[0] >= FRAME_DELAY) {
                            callBack.call(new WGCCapture.Frame(data));
                            lastFrameTime[0] = now;
                        }
                        return 0L;
                    });

                    // ✅ 保持 alive，不让它退出
                    while (isMonitoring) {
                        Thread.sleep(100);
                    }

                } catch (Exception e) {
                    log.error("采集异常", e);
                } finally {
                    if (currentCapture != null) {
                        currentCapture.close();
                        currentCapture = null;
                    }
                }

                try {
                    Thread.sleep(RETRY_INTERVAL);
                } catch (InterruptedException ignored) {
                }
            }
        });
    }

    public synchronized void stopMonitor() {
        isMonitoring = false;
        if (currentCapture != null) {
            currentCapture.close();
        }
    }
}