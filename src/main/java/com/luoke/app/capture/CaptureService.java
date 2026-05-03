package com.luoke.app.capture;

import com.luoke.app.capture.processor.RoiProcessor;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.CaptureStateEvent;
import com.luoke.app.hook.multicast.HookMulticaster;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@Slf4j
public class CaptureService {
    private final String windowTitle;
    private static final int MAX_BLACK_FRAMES = 30;
    private final AtomicInteger continuousBlackFrames = new AtomicInteger(0);

    private final CopyOnWriteArrayList<RoiProcessor> processors = new CopyOnWriteArrayList<>();
    private volatile int id = -1;
    private ROIData[] cachedRois;

    private final WgcCaptureLib.JniCallback captureCallback;

    public CaptureService(String windowTitle) {
        this.windowTitle = windowTitle;
        captureCallback = (id, index, data, len, w, h, code) -> {
            if (code == -1 || index < 0 || data == null) {
                log.warn("Rust 侧捕获流已断开: id={}", id);
                this.id = -1;
                // 发送断开事件
                HookMulticaster.getInstance().enqueue(HookEventType.CAPTURE_STATE,
                        new CaptureStateEvent(-1, false, windowTitle));
                return;
            }

            byte[] grayData = data.getByteArray(0, (int) len);

            if (index == 0) {
                if (isAllBlack(grayData, 100)) {
                    if (continuousBlackFrames.incrementAndGet() > MAX_BLACK_FRAMES) {
                        log.error("检测到持续黑帧，强制重置采集会话...");
                        this.stop();
                        return;
                    }
                } else {
                    continuousBlackFrames.set(0);
                }
            }

            for (RoiProcessor processor : processors) {
                if (processor.targetRoiIndex() == -1 || processor.targetRoiIndex() == index) {
                    processor.onProcess(grayData, w, h);
                }
            }
        };

    }

    public boolean tryConnect() {
        long hwnd = WindowFinder.findWindowByKeyword(windowTitle);
        if (hwnd <= 0) return false;

        this.id = WgcCaptureLib.INSTANCE.create(hwnd, captureCallback);

        if (this.id > 0) {
            log.info("✅ 成功连接窗口 [{}], HWND: {}, ID: {}", windowTitle, hwnd, this.id);
            // 发送连接成功事件
            HookMulticaster.getInstance().enqueue(HookEventType.CAPTURE_STATE,
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
            HookMulticaster.getInstance().enqueue(HookEventType.CAPTURE_STATE,
                    new CaptureStateEvent(-1, false, windowTitle));
        }
    }
}