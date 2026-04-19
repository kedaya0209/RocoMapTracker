package com.luoke.capture;

import com.luoke.processor.MiniMapProcessor;
import lombok.extern.slf4j.Slf4j;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.Files;

@Slf4j
public class WindowCaptureContext implements AutoCloseable {
    private final long hwnd;
    private WGCCapture capture;
    private boolean isStarted = false;

    public WindowCaptureContext(String windowKeyword) {
        this.hwnd = WindowFinder.findWindowByKeyword(windowKeyword);
        if (this.hwnd == 0) throw new RuntimeException("未找到窗口: " + windowKeyword);
    }

    /**
     * 仅初始化对象，不开启采集流
     */
    public boolean init() {
        if (capture != null) return true;
        try {
            capture = new WGCCapture(hwnd);
            return true;
        } catch (Exception e) {
            log.error("Init Error: ", e);
            return false;
        }
    }

    /**
     * 同步模式：获取原始字节
     */
    public WGCCapture.Frame captureFrameBytes() {
        if (capture == null && !init()) return null;

        int[] wh = new int[2];
        // 这里的 1000ms 是等待下一帧的超时时间
        WGCCapture.Frame frame = capture.captureSingleFrame();

        if (frame == null) {
            log.warn("Grab timeout or no data.");
            return null;
        }
        return frame;
    }

    /**
     * 异步模式：开启监听
     * 注意：一旦调用此方法，采集流就会一直运行并触发 listener
     */
    public void startAsyncCapture(WGCCapture.FrameListener listener) {
        if (capture == null && !init()) return;
        if (isStarted) {
            log.warn("采集已经开启，无法重新设置监听器。如需切换请重新创建 Context。");
            return;
        }
        capture.startPushThread(33, listener);
        isStarted = true;
    }

    /**
     * 同步模式：直接获取 BufferedImage
     */
    public BufferedImage captureFrame() {
        WGCCapture.Frame record = captureFrameBytes();
        if (record == null) return null;
        return MiniMapProcessor.toImage(record);
    }

    /**
     * 同步模式：截图并保存
     */
    public boolean captureAndSave(String savePath) {
        BufferedImage image = captureFrame();
        if (image == null) return false;
        try {
            File file = new File(savePath);
            if (file.getParentFile() != null) {
                Files.createDirectories(file.getParentFile().toPath());
            }
            return ImageIO.write(image, "png", file);
        } catch (IOException e) {
            log.error("Save Error: ", e);
            return false;
        }
    }

    @Override
    public void close() {
        if (capture != null) {
            capture.release();
            capture = null;
            isStarted = false;
            log.info("WindowCaptureContext closed.");
        }
    }
}