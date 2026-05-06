package com.luoke.app.hook.impl;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.PlayerPositionEvent;
import com.luoke.app.hook.multicast.HookRegistry;
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
        double px = data.x();
        double py = data.y();

        boolean changed = false;

        for (ResourcePoint res : ResourcePointContext.getInstance().getNearbyResources(px, py)) {
            if (res.isGrayed()) continue;

            if (!ResourcePointContext.getInstance().isCollect(res.getConfig().getMarkTypeName())) {
                continue;
            }

            Point2D resPos = res.getScreenPosition();

            double dx = resPos.getX() - px;
            double dy = resPos.getY() - py;
            double dist = Math.sqrt(dx * dx + dy * dy);

            if (dist < GRAY_DISTANCE) {
                res.setGrayed(true);
                changed = true;
            }
        }

        if (changed) {
            HookRegistry.INSTANCE.publish(HookEventType.RESOURCE_POINT_CHANGED, null);
        }
    }
}
