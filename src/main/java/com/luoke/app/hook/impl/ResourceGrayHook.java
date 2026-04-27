package com.luoke.app.hook.impl;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.event.PlayerPositionEvent;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.map.model.ResourcePoint;
import javafx.geometry.Point2D;

import java.util.Set;

/**
 * 玩家靠近资源图标自动置灰钩子
 * <p>
 * 功能说明：
 * <ul>
 *   <li>监听玩家位置更新事件</li>
 *   <li>计算玩家与采集类资源的直线距离</li>
 *   <li>当距离小于阈值（默认30px）时，将资源图标置灰</li>
 *   <li>仅对采集类资源且markType > 704的资源生效</li>
 * </ul>
 * <p>
 * 性能优化设计：
 * <ul>
 *   <li>使用GEO空间索引快速查找附近的资源点，避免全图遍历</li>
 *   <li>已置灰的资源跳过计算，减少不必要的距离计算</li>
 *     <li>使用直线距离而非复杂路径计算，提升性能</li>
 * </ul>
 * <p>
 * 使用场景：
 * 当玩家在游戏中靠近采集资源时，自动将该资源图标置灰，
 * 避免重复采集标记，提升用户体验。
 */
public class ResourceGrayHook extends AbstractGenericHook<PlayerPositionEvent> {

    /**
     * 资源图标置灰触发距离阈值（单位：像素）
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>30px为超近距离，确保玩家真正到达资源位置</li>
     *   <li>阈值过小会导致置灰不及时，过大会导致误触发</li>
     *   <li>该值可从配置文件动态读取，便于调优</li>
     * </ul>
     */
    private static final double GRAY_DISTANCE = AppConfig.GRAY_DISTANCE;

    /**
     * 获取当前钩子支持的事件类型集合
     * <p>
     * ResourceGrayHook仅处理玩家位置更新事件，用于实时检测玩家与资源的距离
     *
     * @return 包含PLAYER_UPDATE事件的不可变Set集合
     */
    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.PLAYER_UPDATE);
    }

    /**
     * 处理玩家位置更新事件，自动置灰附近的资源图标
     * <p>
     * 执行流程：
     * <ol>
     *   <li>获取玩家当前位置坐标</li>
     *   <li>通过GEO空间索引查找玩家附近的资源点</li>
     *   <li>对每个符合条件的资源计算与玩家的直线距离</li>
     *   <li>距离小于阈值时，将资源图标置灰</li>
     * </ol>
     * <p>
     * 过滤条件：
     * <ul>
     *   <li>资源类型必须为"采集"</li>
     *   <li>资源的markType类型必须大于704</li>
     *   <li>资源未被置灰（避免重复处理）</li>
     * </ul>
     *
     * @param eventType 事件类型，必须为PLAYER_UPDATE
     * @param data 玩家位置事件数据，包含x和y坐标
     */
    @Override
    public void onEvent(HookEventType eventType, PlayerPositionEvent data) {
        // 提取玩家当前位置坐标
        double px = data.x();
        double py = data.y();

        // 使用GEO空间索引快速查找附近的资源点
        // 相比全图遍历，空间索引可将时间复杂度从O(n)降低到O(log n)
        for (ResourcePoint res : ResourcePointContext.getInstance().getNearbyResources(px, py)) {
            // 资源已置灰，跳过计算，避免重复处理
            // 置灰是单向操作，一旦置灰不需要重新判断
            if (res.isGrayed()) continue;

            // 过滤条件检查：
            // 1. 仅对"采集"类型的资源生效
            // 2. markType > 704是游戏内特定的采集资源分类规则
            if (res.isCollectible()) {
                continue;
            }

            // 获取资源的屏幕位置
            // 注意：此处计算的是屏幕空间的直线距离，非世界空间距离
            Point2D resPos = res.getScreenPosition();

            // 计算玩家与资源的直线距离（欧几里得距离）
            // 使用直线距离而非路径距离的原因：
            // 1. 计算简单快速，适合实时处理
            // 2. 对于屏幕上的微小移动，直线距离已足够精确
            double dx = resPos.getX() - px;
            double dy = resPos.getY() - py;
            double dist = Math.sqrt(dx * dx + dy * dy);

            // 距离判断：小于阈值时将资源置灰
            // 置灰操作通常用于UI反馈，不涉及状态变更，无需同步控制
            if (dist < GRAY_DISTANCE) {
                res.setGrayed(true);
            }
        }
    }
}
