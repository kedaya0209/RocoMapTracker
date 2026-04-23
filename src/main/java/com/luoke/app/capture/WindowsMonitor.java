package com.luoke.app.capture;

import com.luoke.app.capture.callback.WindowCaptureEventCallBack;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.capture.jna.WindowFinder;
import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class WindowsMonitor {
    private static final long FRAME_DELAY = 1000 / AppConfig.TARGET_CAPTURE_FPS;
    private static final long RETRY_INTERVAL = 30 * 1000;

    private volatile boolean isMonitoring = false;
    private final String windowKeyword;
    private WgcCapture runningCapture;

    public WindowsMonitor(String windowKeyword) {
        this.windowKeyword = windowKeyword;
    }

    public synchronized void startMonitor(WindowCaptureEventCallBack<Frame> callBack) {
        if (isMonitoring) return;
        isMonitoring = true;

        Thread vThread = Thread.ofVirtual().start(() -> {
            while (isMonitoring) {
                try {
                    long hwnd = WindowFinder.findWindowByKeyword(windowKeyword);
                    if (hwnd == 0) {
                        log.warn("等待窗口 [{}]...", windowKeyword);
                        Thread.sleep(RETRY_INTERVAL);
                        continue;
                    }

                    WgcCapture capture = new WgcCapture(hwnd);
                    runningCapture = capture;
                    log.info("已连接窗口: {}", hwnd);

                    final long[] lastFrameTime = {0L};

                    capture.startLoop(frame -> {
                        long now = System.currentTimeMillis();
                        if (now - lastFrameTime[0] >= FRAME_DELAY) {
                            callBack.call(frame);
                            lastFrameTime[0] = now;
                        }
                    }, AppConfig.SHOW_MONITOR_BORDER);

                    while (isMonitoring) {
                        long check = WindowFinder.findWindowByKeyword(windowKeyword);
                        if (check == 0) break;
                        Thread.sleep(500);
                    }

                } catch (Exception e) {
                    log.error("采集异常", e);
                } finally {
                    if (runningCapture != null) {
                        runningCapture.close();
                        runningCapture = null;
                    }
                }

                try {
                    Thread.sleep(RETRY_INTERVAL);
                } catch (Exception ignored) {
                }
            }
        });
    }

    public synchronized void stopMonitor() {
        isMonitoring = false;
        if (runningCapture != null) {
            runningCapture.close();
        }
    }
}