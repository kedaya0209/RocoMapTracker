package com.luoke.app.hook.impl;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.event.PlayerPositionEvent;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.map.model.ResourcePoint;
import javafx.geometry.Point2D;

import java.util.Set;


public class ResourceGrayHook extends AbstractGenericHook<PlayerPositionEvent> {


    private static final double GRAY_DISTANCE = AppConfig.GRAY_DISTANCE;

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.PLAYER_UPDATE);
    }

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
