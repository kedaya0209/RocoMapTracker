package com.luoke.app.capture.processor;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MaterialCollectionContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
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
    private final ROIData cachedRoi = new ROIData(8750, 2070, 1100, 2100);

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

        // 既然 data 已经是堆内存副本，直接提交，无需 clone
        if (!parallel.tryAcquire()) return;

        lastScanTime = now;

        // 直接传入 data，由 OcrAsyncManager 在异步线程池处理
        OcrAsyncManager.getInstance().submitTask(data, width, height, lines -> {
            try {
                processOcrResult(lines);
            } catch (Exception e) {
                log.error("OCR 异步回调处理异常", e);
            } finally {
                parallel.release();
            }
        });
    }

    @Override
    public ROIData getRoi() {
        return cachedRoi;
    }

    private void processOcrResult(List<String> lines) {
        // 如果没有识别到有效文字，重置所有判定状态
        if (lines == null || lines.isEmpty()) {
            synchronized (this) {
                stabilityCount = 0;
                pendingList.clear();
                lastConfirmedList.clear();
            }
            return;
        }

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
                return;
            }

            // 稳定性判定阈值，根据 SCAN_INTERVAL 调整，2次约等于 400ms-600ms 的稳定期
            if (stabilityCount == 2) {
                handleIncrementalLogic(currentList);
            }
        }
    }

    private void handleIncrementalLogic(List<ItemResult> stableList) {
        // 增量判定：如果当前稳定列表长度超过上次确认的列表，说明有新物资入账
        if (stableList.size() > lastConfirmedList.size()) {
            for (int i = lastConfirmedList.size(); i < stableList.size(); i++) {
                ItemResult res = stableList.get(i);

                // 1. 核心逻辑：存入上下文
                MaterialCollectionContext.getInstance().addMaterial(res.name(), res.count());

                // 2. UI逻辑：发送解耦通知
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent(String.format("获得 %s x%d", res.name(), res.count()),
                                NotificationType.SUCCESS));

                log.info("🎯 物资已记录: {} x{}", res.name(), res.count());
            }
        }
        // 更新“已确认”镜像
        lastConfirmedList = new ArrayList<>(stableList);
    }
}