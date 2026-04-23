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
 * 玩家靠近 → 资源图标自动置灰 钩子
 */
public class ResourceGrayHook extends AbstractGenericHook<PlayerPositionEvent> {

    // 真正触发置灰的距离：30px 超近距离
    private static final double GRAY_DISTANCE = AppConfig.GRAY_DISTANCE;

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.PLAYER_UPDATE);
    }

    /**
     * 玩家位置更新 → 自动置灰附近资源
     */
    @Override
    public void onEvent(HookEventType eventType, PlayerPositionEvent data) {
        double px = data.x();
        double py = data.y();

        // 只遍历 GEO 索引附近的资源（超快）
        for (ResourcePoint res : ResourcePointContext.getInstance().getNearbyResources(px, py)) {
            // 已经置灰 → 跳过
            if (res.isGrayed()) continue;

            // 你的过滤条件：只对【采集】类 & markType <=704 生效
            if (!("采集".equals(res.getConfig().getType()) && res.getConfig().getMarkType() > 704)) {
                continue;
            }

            // 计算【直线距离】
            Point2D resPos = res.getScreenPosition();
            double dx = resPos.getX() - px;
            double dy = resPos.getY() - py;
            double dist = Math.sqrt(dx * dx + dy * dy);

            // 只有 < 30px 才置灰
            if (dist < GRAY_DISTANCE) {
                res.setGrayed(true);
            }
        }
    }
}