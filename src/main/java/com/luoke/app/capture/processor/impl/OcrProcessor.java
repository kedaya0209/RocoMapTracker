package com.luoke.app.capture.processor.impl;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MaterialCollectionContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.model.ItemResult;
import com.luoke.app.utils.OcrResultValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;

@Slf4j
public class OcrProcessor implements RoiProcessor {

    private static final long SCAN_INTERVAL = 200;
    private final int targetRoiIndex;
    private final Semaphore parallel = new Semaphore(AppConfig.OCR_CORE_SIZE);
    private long lastScanTime = 0;
    private List<ItemResult> lastConfirmedList = new ArrayList<>();
    private List<ItemResult> pendingList = new ArrayList<>();
    private int stabilityCount = 0;

    public OcrProcessor(int targetRoiIndex) {
        this.targetRoiIndex = targetRoiIndex;
    }

    @Override
    public int targetRoiIndex() {
        return this.targetRoiIndex;
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        if (!AppConfig.MATERIAL_COLLECTION) return;

        long now = System.currentTimeMillis();
        if ((now - lastScanTime) < SCAN_INTERVAL) return;

        // 流量控制：如果 OCR 引擎忙不过来，直接跳过这一帧
        if (!parallel.tryAcquire()) return;

        lastScanTime = now;

        // 注意：Rust 传过来的是原始灰度数据 (Gray8)
        // 如果你的 OcrAsyncManager 接收的是图片格式(PNG/JPG)，需要在这里简单封装
        // 如果 OCR 引擎支持直接传入原始内存，性能会更高
        byte[] imageBytes = data;

        // 提交到你现有的异步管理器
        OcrAsyncManager.getInstance().submitTask(imageBytes, width, height, lines -> {
            try {
                processOcrResult(lines);
            } finally {
                parallel.release();
            }
        });
    }

    @Override
    public ROIData getRoi() {
        return new ROIData(8750, 2870, 1100, 1700);
    }

    // --- 保持原有的业务逻辑逻辑 ---
    private void processOcrResult(List<String> lines) {
        if (lines == null) return;
        List<ItemResult> currentList = lines.stream()
                .map(OcrResultValidator::parse)
                .filter(Objects::nonNull)
                .toList();

        synchronized (this) {
            if (!currentList.isEmpty() && currentList.equals(pendingList)) {
                stabilityCount++;
            } else {
                pendingList = new ArrayList<>(currentList);
                stabilityCount = 1;
                if (currentList.isEmpty()) lastConfirmedList.clear();
                return;
            }
            if (stabilityCount == 2) handleIncrementalLogic(currentList);
        }
    }

    private void handleIncrementalLogic(List<ItemResult> stableList) {
        if (stableList.size() > lastConfirmedList.size()) {
            for (int i = lastConfirmedList.size(); i < stableList.size(); i++) {
                ItemResult res = stableList.get(i);
                log.info("🎯 发现物资: {} x{}", res.name(), res.count());
                MaterialCollectionContext.getInstance().addMaterial(res.name(), res.count());
            }
        }
        lastConfirmedList = new ArrayList<>(stableList);
    }
}