package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.MaterialCollectionEvent;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.map.model.ResourceConfig;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
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

    // 存储累计结果（TreeMap 按名称字典序排列，面板固定顺序）
    private final Map<String, Integer> summaryMap = Collections.synchronizedMap(new TreeMap<>());
    // 存储背包最新总数
    private final Map<String, Integer> backpackTotals = new ConcurrentHashMap<>();
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
                new MaterialCollectionEvent(new TreeMap<>(summaryMap), new HashMap<>(backpackTotals)));
    }

    /**
     * 从网络拾取事件更新（由 rmt_bridge.py 推送 MSG_ITEM_PICKUP）。
     * <p>
     * 数据已在 Python 侧解析完成，包含物品名称、本次拾取数量和背包总数。
     *
     * @param itemName      物品名称（已在 Python 侧完成 ID→名称 解析）
     * @param pickupNum     本次拾取数量
     * @param backpackTotal 背包最新总数
     */
    public void updateFromNetwork(String itemName, int pickupNum, int backpackTotal) {
        if (itemName == null || itemName.isEmpty()) return;

        long now = System.currentTimeMillis();
        firstLootTimestamp.compareAndSet(0, now);

        summaryMap.merge(itemName, pickupNum, Integer::sum);
        backpackTotals.put(itemName, backpackTotal);
        historyLog.add(new LootRecord(now, itemName, pickupNum));

        log.info("📦 [网络拾取] {} +{}, 背包:{}, 累计:{}", itemName, pickupNum, backpackTotal, summaryMap.get(itemName));

        HookRegistry.INSTANCE.publish(HookEventType.MATERIAL_COLLECTION_UPDATED,
                new MaterialCollectionEvent(new TreeMap<>(summaryMap), new HashMap<>(backpackTotals)));
    }

    /**
     * 重置数据并关闭面板
     */
    public void reset() {
        summaryMap.clear();
        backpackTotals.clear();
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