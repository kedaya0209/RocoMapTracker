package com.luoke.app.capture;

import com.luoke.app.capture.processor.RoiProcessor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

@Data
@Slf4j
public class CaptureService {
    private final String windowTitle;
    private static final int MAX_BLACK_FRAMES = 30; // 连续 30 帧全黑则认为失效
    // 🔥 新增：连续黑帧计数器
    private final AtomicInteger continuousBlackFrames = new AtomicInteger(0);

    private final CopyOnWriteArrayList<RoiProcessor> processors = new CopyOnWriteArrayList<>();
    private volatile int id = -1;
    /**
     * JNI 回调：增加黑帧检测
     */
    private final WgcCaptureLib.JniCallback captureCallback = (id, index, data, len, w, h, code) -> {
        // 1. Rust 侧通知销毁
        if (code == -1 || index < 0 || data == null) {
            log.warn("Rust 侧捕获流已断开: id={}", id);
            this.id = -1;
            return;
        }

        byte[] grayData = data.getByteArray(0, (int) len);

        // 2. 🔥 黑帧检测逻辑 (仅对 index 0 主图进行采样)
        if (index == 0) {
            if (isAllBlack(grayData, 100)) {
                if (continuousBlackFrames.incrementAndGet() > MAX_BLACK_FRAMES) {
                    log.error("检测到持续黑帧，强制重置采集会话...");
                    this.stop(); // 停止 Rust 侧资源
                    this.id = -1; // 触发 Java 守护进程重连
                    return;
                }
            } else {
                continuousBlackFrames.set(0); // 只要有一帧不是黑的，计数重置
            }
        }

        // 3. 分发给处理器
        for (RoiProcessor processor : processors) {
            if (processor.targetRoiIndex() == -1 || processor.targetRoiIndex() == index) {
                processor.onProcess(grayData, w, h);
            }
        }
    };
    private long lastHwnd = 0;
    // 🔥 新增：保存当前 ROI，用于重启后自动下发
    private ROIData[] cachedRois;

    public CaptureService(String windowTitle) {
        this.windowTitle = windowTitle;
    }

    /**
     * 🔥 核心改动：尝试连接窗口逻辑
     */
    public boolean tryConnect() {
        long hwnd = WindowFinder.findWindowByKeyword(windowTitle);
        if (hwnd <= 0) return false;

        this.lastHwnd = hwnd;
        this.id = WgcCaptureLib.INSTANCE.create(hwnd, captureCallback);

        if (this.id > 0) {
            log.info("✅ 成功连接窗口 [{}], HWND: {}, ID: {}", windowTitle, hwnd, this.id);
            // 如果有缓存的 ROI，连接成功后立即自动同步给 Rust
            if (cachedRois != null) {
                WgcCaptureLib.INSTANCE.set_rois(this.id, cachedRois, cachedRois.length);
            }
            return true;
        }
        return false;
    }

    /**
     * 简单的全黑采样检测
     */
    private boolean isAllBlack(byte[] data, int sampleSize) {
        int checkLen = Math.min(data.length, sampleSize);
        int result = 0;
        for (int i = 0; i < checkLen; i++) {
            result |= (data[i] & 0xFF);
        }
        return result == 0;
    }

    public void setRois(ROIData[] rois) {
        this.cachedRois = rois; // 备份到内存
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
        }
    }
}