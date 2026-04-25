package com.luoke.app.hook.impl;

import com.luoke.app.capture.jna.Frame;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MaterialCollectionContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.model.ItemResult;
import com.luoke.app.utils.OcrResultValidator;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class RealOcrHook extends AbstractGenericHook<Frame> {
    private static final double SCALE_X = 0.875, SCALE_Y = 0.287, SCALE_W = 0.11, SCALE_H = 0.17;
    private static final long SCAN_INTERVAL = 200; // 300ms 采样

    private long lastScanTime = 0;

    // --- 状态追踪 ---
    private List<ItemResult> lastConfirmedList = new ArrayList<>(); // 已确认计入的列表
    private List<ItemResult> pendingList = new ArrayList<>();       // 待校验的列表
    private final AtomicInteger parallel = new AtomicInteger(AppConfig.OCR_CORE_SIZE);// 稳定计数
    private int stabilityCount = 0;

    @Override
    public void onEvent(HookEventType eventType, Frame frame) {
        long now = System.currentTimeMillis();
        if ((now - lastScanTime) < SCAN_INTERVAL) return;
        if (parallel.get() <= 0) return;
        parallel.decrementAndGet();
        boolean taskSubmitted = false;
        lastScanTime = now;


        try {
            byte[] pixels = frame.getPixels();
            int w = frame.width(), h = frame.height();

            try (BytePointer ptr = new BytePointer(pixels);
                 Mat fullMat = new Mat(h, w, opencv_core.CV_8UC4, ptr);
                 Rect roi = new Rect((int) (w * SCALE_X), (int) (h * SCALE_Y), (int) (w * SCALE_W), (int) (h * SCALE_H));
                 Mat cropped = fullMat.apply(roi);
                 BytePointer buf = new BytePointer()) {

                opencv_imgcodecs.imencode(".png", cropped, buf);
                byte[] croppedBytes = new byte[(int) buf.limit()];
                buf.get(croppedBytes);

                OcrAsyncManager.getInstance().submitTask(croppedBytes, lines -> {
                    try {
                        // 1. 解析为结构化列表
                        List<ItemResult> currentList = lines.stream()
                                .map(OcrResultValidator::parse)
                                .filter(Objects::nonNull)
                                .toList();

                        synchronized (this) {
                            // 2. 稳定器逻辑：当前帧需与待定帧完全一致
                            if (!currentList.isEmpty() && currentList.equals(pendingList)) {
                                stabilityCount++;
                            } else {
                                pendingList = new ArrayList<>(currentList);
                                stabilityCount = 1;
                                // 关键：如果区域空了，重置所有快照（应对翻页和下一次拾取）
                                if (currentList.isEmpty()) {
                                    lastConfirmedList.clear();
                                }
                                return;
                            }

                            // 3. 连续 2 帧稳定，开始增量比对
                            if (stabilityCount == 2) {
                                handleIncrementalLogic(currentList);
                            }
                        }
                    } finally {
                        parallel.incrementAndGet();
                    }
                });
                taskSubmitted = true; // 提交成功
            }
        } catch (Exception e) {
            log.error("Hook 异常", e);
        } finally {
            if (!taskSubmitted) {
                parallel.incrementAndGet();
            }
        }
    }

    private void handleIncrementalLogic(List<ItemResult> stableList) {
        // 场景 A: 列表行数增加了（新物资跳出来，包括一次出5个的情况）
        if (stableList.size() > lastConfirmedList.size()) {
            for (int i = lastConfirmedList.size(); i < stableList.size(); i++) {
                ItemResult res = stableList.get(i);
                log.info("🎯 确认为新增拾取: {} x{}", res.name(), res.count());
                MaterialCollectionContext.getInstance().addMaterial(res.name(), res.count());
            }
        }
        // 场景 B: 行数没变但内容全变了（极速翻页中，刚好两页行数相同但文字不同）
        else if (stableList.size() == lastConfirmedList.size() && !stableList.equals(lastConfirmedList)) {
            for (ItemResult res : stableList) {
                log.info("🎯 翻页增量确认: {} x{}", res.name(), res.count());
                MaterialCollectionContext.getInstance().addMaterial(res.name(), res.count());
            }
        }

        // 更新最后确认快照
        lastConfirmedList = new ArrayList<>(stableList);
    }

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.FRAME_CAPTURED);
    }
}