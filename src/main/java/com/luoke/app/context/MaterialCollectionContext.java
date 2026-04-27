package com.luoke.app.context;

import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.map.model.ResourcePoint;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Data
public class MaterialCollectionContext {
    private static final MaterialCollectionContext INSTANCE = new MaterialCollectionContext();
    private final Map<String, Integer> summaryMap = new ConcurrentHashMap<>();
    private final List<LootRecord> historyLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong firstLootTimestamp = new AtomicLong(0);

    // 💡 新增：版本号，用于 UI 判定是否需要刷新
    private final AtomicInteger dataVersion = new AtomicInteger(0);
    private final Set<String> filters;

    private MaterialCollectionContext() {
        List<ResourcePoint> allPoints = ResourcePointContext.getInstance().getAllPoints();
        filters = allPoints.stream()
                .map(ResourcePoint::getConfig)
                .map(ResourceConfig::getMarkTypeName)
                .collect(Collectors.toSet());
    }

    public static MaterialCollectionContext getInstance() {
        return INSTANCE;
    }

    public void addMaterial(String name, int amount) {
        if (amount <= 0) return;
        if (!filters.contains(name)) return;
        long now = System.currentTimeMillis();
        firstLootTimestamp.compareAndSet(0, now);

        historyLog.add(new LootRecord(now, name, amount));
        summaryMap.merge(name, amount, Integer::sum);

        // 💡 关键点：数据变动，提升版本号
        dataVersion.incrementAndGet();

        log.info("📦 [采集记录] {} +{}, 当前累计: {}", name, amount, summaryMap.get(name));
    }

    public int getDataVersion() {
        return dataVersion.get();
    }

    public void reset() {
        summaryMap.clear();
        historyLog.clear();
        firstLootTimestamp.set(0);
        dataVersion.set(0); // 重置版本
        log.info("♻️ 采集上下文已重置");
    }

    public record LootRecord(long timestamp, String name, int amount) {
        public String format() {
            return String.format("[%d] 拾取: %s x%d", timestamp, name, amount);
        }
    }
}