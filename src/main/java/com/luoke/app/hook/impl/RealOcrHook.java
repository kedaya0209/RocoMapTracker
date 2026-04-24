package com.luoke.app.hook.impl;

import com.luoke.app.capture.jna.Frame;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class RealOcrHook extends AbstractGenericHook<Frame> {
    // 裁剪比例配置
    private static final double SCALE_X = 0.875;
    private static final double SCALE_Y = 0.287;
    private static final double SCALE_W = 0.11;
    private static final double SCALE_H = 0.17;

    private long lastTime = 0;

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.FRAME_CAPTURED);
    }

    @Override
    public void onEvent(HookEventType eventType, Frame frame) {
        long now = System.currentTimeMillis();
        if ((now - lastTime) < 1000) return;

        // ====================== 【修复点1】无论成功失败，先锁1秒 ======================
        lastTime = now;

        try {
            byte[] pixels = frame.getPixels();
            int w = frame.width();
            int h = frame.height();

            try (BytePointer ptr = new BytePointer(pixels);
                 Mat fullMat = new Mat(h, w, opencv_core.CV_8UC4, ptr)) {

                int tx = (int) (w * SCALE_X);
                int ty = (int) (h * SCALE_Y);
                int tw = (int) (w * SCALE_W);
                int th = (int) (h * SCALE_H);

                tx = Math.max(0, Math.min(tx, w - 1));
                ty = Math.max(0, Math.min(ty, h - 1));
                tw = Math.min(tw, w - tx);
                th = Math.min(th, h - ty);

                Rect roi = new Rect(tx, ty, tw, th);

                try (Mat cropped = fullMat.apply(roi)) {
                    BytePointer buf = new BytePointer();
                    try {
                        opencv_imgcodecs.imencode(".png", cropped, buf);
                        byte[] croppedBytes = new byte[(int) buf.limit()];
                        buf.get(croppedBytes);

                        CompletableFuture<List<String>> future = OcrAsyncManager.getInstance().submitTask(croppedBytes);
                        List<String> lines = future.get();

                        if (lines.isEmpty()) {
                            return;
                        }

                        long end = System.currentTimeMillis();
                        log.info("图像识别耗时：{}，结果：{}", end - now, lines);

                        // ====================== 【修复点2】这里删掉，不要在这里更新 ======================
                        // lastTime = now;
                    } finally {
                        buf.deallocate();
                    }
                }
            }
        } catch (Exception e) {
            log.error("RealTimeOCRHook 裁剪/提交异常", e);
        }
    }
}