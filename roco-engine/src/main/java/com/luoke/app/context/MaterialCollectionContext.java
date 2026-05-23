package com.luoke.app.context;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.MaterialCollectionEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.map.model.ResourcePoint;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@ThreadSafe
@Slf4j
@Data
public class MaterialCollectionContext {
    private static final MaterialCollectionContext INSTANCE = new MaterialCollectionContext();

    // 存储累计结果
    private final Map<String, Integer> summaryMap = new ConcurrentHashMap<>();
    // 存储历史流水
    private final List<LootRecord> historyLog = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong firstLootTimestamp = new AtomicLong(0);

    private final Set<String> filters;

    private MaterialCollectionContext() {
        // 初始加载过滤词（仅统计地图上存在的资源点名）
        List<ResourcePoint> allPoints = ResourcePointContext.getInstance().getAllPoints();
        filters = allPoints.stream()
                .map(ResourcePoint::getConfig)
                .map(ResourceConfig::getMarkTypeName)
                .collect(Collectors.toSet());
    }

    public static MaterialCollectionContext getInstance() {
        return INSTANCE;
    }

    /**
     * 添加物资并触发 UI 刷新
     */
    public void addMaterial(String name, int amount) {
        if (amount <= 0) return;
        if (!filters.contains(name)) return;

        long now = System.currentTimeMillis();
        firstLootTimestamp.compareAndSet(0, now);

        // 1. 更新数据模型
        historyLog.add(new LootRecord(now, name, amount));
        summaryMap.merge(name, amount, Integer::sum);

        log.info("📦 [采集记录] {} +{}, 当前累计: {}", name, amount, summaryMap.get(name));

        // 2. 通过事件总线通知 UI 层刷新
        HookRegistry.INSTANCE.publish(HookEventType.MATERIAL_COLLECTION_UPDATED,
                new MaterialCollectionEvent(new HashMap<>(summaryMap)));
    }

    /**
     * 重置数据并关闭面板
     */
    public void reset() {
        summaryMap.clear();
        historyLog.clear();
        firstLootTimestamp.set(0);

        // 通知 UI 数据已清空（面板内部会处理 hide 逻辑）
        HookRegistry.INSTANCE.publish(HookEventType.MATERIAL_COLLECTION_UPDATED,
                new MaterialCollectionEvent(Collections.emptyMap()));

        log.info("♻️ 采集上下文已重置");
    }

    @ThreadSafe
    public record LootRecord(long timestamp, String name, int amount) {
    }
}