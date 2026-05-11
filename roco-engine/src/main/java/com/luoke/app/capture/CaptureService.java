package com.luoke.app.capture;

import com.luoke.app.capture.processor.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.CaptureStateEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@Slf4j
public class CaptureService {
    private final String windowTitle;
    private final AtomicInteger continuousBlackFrames = new AtomicInteger(0);

    private final CopyOnWriteArrayList<RoiProcessor> processors = new CopyOnWriteArrayList<>();
    private volatile int id = -1;
    private ROIData[] cachedRois;

    // 复用 byte[] 缓冲区, 避免每帧 new byte[] 产生的 GC 压力
    private final byte[][] roiBuffers = new byte[2][];

    private final WgcCaptureLib.JniCallback captureCallback;

    public CaptureService(String windowTitle) {
        this.windowTitle = windowTitle;
        captureCallback = (id, index, data, len, w, h, stride) -> {
            // stride == -1 表示 Rust 侧断开连接
            if (stride == -1 || index < 0 || data == null) {
                log.warn("Rust 侧捕获流已断开: id={}", id);
                this.id = -1;
                HookRegistry.INSTANCE.publish(HookEventType.CAPTURE_STATE,
                        new CaptureStateEvent(-1, false, windowTitle));
                return;
            }

            int dataLen = (int) len;
            byte[] rawBuffer = roiBuffers[index];
            if (rawBuffer == null || rawBuffer.length < dataLen) {
                rawBuffer = new byte[dataLen];
                roiBuffers[index] = rawBuffer;
            }
            data.read(0, rawBuffer, 0, dataLen);

            // Rust 侧不再做 BGRA→Gray，传原始 BGRA + stride（GPU row_pitch），Java 侧负责转换
            byte[] gray = bgraToGray(rawBuffer, w, h, stride);

            if (index == 0) {
                if (isAllBlack(gray, 100)) {
                    if (continuousBlackFrames.incrementAndGet() > AppConfig.MAX_BLACK_FRAMES) {
                        log.error("检测到持续黑帧，强制重置采集会话...");
                        this.stop();
                        return;
                    }
                } else {
                    continuousBlackFrames.set(0);
                }
            }

            for (RoiProcessor processor : processors) {
                try {
                    if (processor.targetRoiIndex() == -1 || processor.targetRoiIndex() == index) {
                        processor.onProcess(gray, w, h);
                    }
                } catch (Exception ignore) {
                }
            }
        };

    }

    public boolean tryConnect() {
        long hwnd = WindowFinder.findWindowByKeyword(windowTitle);
        if (hwnd <= 0) return false;

        this.id = WgcCaptureLib.INSTANCE.create(hwnd, AppConfig.TARGET_CAPTURE_FPS, captureCallback);

        if (this.id > 0) {
            log.info("✅ 成功连接窗口 [{}], HWND: {}, ID: {}", windowTitle, hwnd, this.id);
            // 发送连接成功事件
            HookRegistry.INSTANCE.publish(HookEventType.CAPTURE_STATE,
                    new CaptureStateEvent(this.id, true, windowTitle));

            if (cachedRois != null) {
                WgcCaptureLib.INSTANCE.set_rois(this.id, cachedRois, cachedRois.length);
            }
            return true;
        }
        return false;
    }

    private boolean isAllBlack(byte[] data, int sampleSize) {
        int checkLen = Math.min(data.length, sampleSize);
        int result = 0;
        for (int i = 0; i < checkLen; i++) {
            result |= (data[i] & 0xFF);
        }
        return result == 0;
    }

    /**
     * BGRA (带 GPU row_pitch stride 对齐) → 灰度字节数组。
     * ITU-R BT.601 luma: Y = 0.299R + 0.587G + 0.114B
     */
    private static byte[] bgraToGray(byte[] bgra, int w, int h, int stride) {
        byte[] gray = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int rowStart = y * stride;
            int grayRow = y * w;
            for (int x = 0; x < w; x++) {
                int pos = rowStart + x * 4;
                int b = bgra[pos] & 0xFF;
                int g = bgra[pos + 1] & 0xFF;
                int r = bgra[pos + 2] & 0xFF;
                gray[grayRow + x] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
            }
        }
        return gray;
    }

    public void setRois(ROIData[] rois) {
        this.cachedRois = rois;
        if (this.id > 0) {
            WgcCaptureLib.INSTANCE.set_rois(this.id, rois, rois.length);
        }
    }

    public void addProcessors(RoiProcessor... processors) {
        this.processors.addAll(List.of(processors));
    }

    public void stop() {
        if (this.id > 0) {
            WgcCaptureLib.INSTANCE.stop(this.id);
            this.id = -1;
            // 发送停止事件
            HookRegistry.INSTANCE.publish(HookEventType.CAPTURE_STATE,
                    new CaptureStateEvent(-1, false, windowTitle));
        }
    }
}