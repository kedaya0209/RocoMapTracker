package com.luoke.app.context;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MaterialCollectionContext
 * 职责：单例存储识别结果，记录带时间戳的流水，并生成汇总报表
 */
@Slf4j
public class MaterialCollectionContext {

    // ====================== 【单例实现】 ======================
    private static final MaterialCollectionContext INSTANCE = new MaterialCollectionContext();
    // 汇总：物资名称 -> 总数量
    private final Map<String, Integer> summaryMap = new ConcurrentHashMap<>();
    // 流水：按顺序存储每一次识别到的记录
    private final List<LootRecord> historyLog = Collections.synchronizedList(new ArrayList<>());

    // ====================== 【核心存储】 ======================
    // 计时器：0 表示尚未开始，存储第一次拾取的时间戳
    private final AtomicLong firstLootTimestamp = new AtomicLong(0);

    private MaterialCollectionContext() {
    }

    public static MaterialCollectionContext getInstance() {
        return INSTANCE;
    }

    /**
     * 核心方法：由 RealOcrHook 调用
     * @param name 物资名称
     * @param amount 数量
     */
    public void addMaterial(String name, int amount) {
        long now = System.currentTimeMillis();

        // 1. 设置第一次拾取的启动时间（仅执行一次）
        firstLootTimestamp.compareAndSet(0, now);

        // 2. 存入流水
        historyLog.add(new LootRecord(now, name, amount));

        // 3. 更新汇总
        summaryMap.merge(name, amount, Integer::sum);

        log.info("📦 [采集记录] {} +{}, 当前累计: {}", name, amount, summaryMap.get(name));
    }

    /**
     * 生成最终报告文本
     */
    public String generateFullReport() {
        if (firstLootTimestamp.get() == 0) {
            return "--- 暂无采集数据 ---";
        }

        StringBuilder report = new StringBuilder();
        long durationMs = System.currentTimeMillis() - firstLootTimestamp.get();
        long minutes = (durationMs / 1000) / 60;
        long seconds = (durationMs / 1000) % 60;

        report.append("========= 采集报告 =========\n");
        report.append(String.format("持续时间: %d分%d秒\n", minutes, seconds));
        report.append("---------------------------\n");

        // 汇总部分
        report.append("[ 汇总数据 ]\n");
        summaryMap.forEach((name, total) ->
                report.append(String.format(" - %s: 总计 %d\n", name, total))
        );

        report.append("\n[ 详细流水 ]\n");
        // 建议只输出最后 50 条流水，如果太多可能会刷屏
        synchronized (historyLog) {
            for (LootRecord record : historyLog) {
                report.append(record.format()).append("\n");
            }
        }
        report.append("===========================");

        return report.toString();
    }

    /**
     * 重置所有数据（用于开始新的一轮）
     */
    public void reset() {
        summaryMap.clear();
        historyLog.clear();
        firstLootTimestamp.set(0);
        log.info("♻️ 采集上下文已重置");
    }

    /**
     * 内部记录类：存储单次拾取详情
     */
    public record LootRecord(long timestamp, String name, int amount) {
        public String format() {
            String timeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            return String.format("[%s] 拾取: %s x%d", timeStr, name, amount);
        }
    }
}