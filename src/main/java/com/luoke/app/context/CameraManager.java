package com.luoke.app.context;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CameraManager {
    private boolean followMode = false;
    // 调低默认跟随缩放倍率（例如 1.0 甚至 0.8），数值越小视野越广
    private double followScale = 1.0;

    private CameraManager() {
    }

    public static CameraManager getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 更新视口偏移：在渲染前由 AnimationTimer 调用
     */
    public void updateViewport() {
        MapManager mm = MapManager.getInstance();
        if (mm.getMapImage() == null || mm.getViewWidth() <= 0) return;

        if (followMode) {
            mm.setScale(followScale);
            // 计算偏移：让玩家坐标在视口中心
            double targetX = (mm.getViewWidth() / 2.0) - (mm.getPlayerX() * mm.getScale());
            double targetY = (mm.getViewHeight() / 2.0) - (mm.getPlayerY() * mm.getScale());

            mm.setOffsetX(targetX);
            mm.setOffsetY(targetY);

            // 建议：跟随模式下开启边缘约束，防止露白
            mm.ensureBounds();
        }
    }

    /**
     * 强力重置：恢复到手动模式下的全图铺满状态
     */
    public void resetToFullView() {
        MapManager mm = MapManager.getInstance();
        if (mm.getMapImage() == null || mm.getViewWidth() <= 0) return;

        double scale = Math.max(mm.getViewWidth() / mm.getMapImage().getWidth(),
                mm.getViewHeight() / mm.getMapImage().getHeight());
        mm.setScale(scale);
        mm.setOffsetX((mm.getViewWidth() - mm.getMapImage().getWidth() * scale) / 2.0);
        mm.setOffsetY((mm.getViewHeight() - mm.getMapImage().getHeight() * scale) / 2.0);

        mm.ensureBounds();
    }

    private static class Holder {
        private static final CameraManager INSTANCE = new CameraManager();
    }
}