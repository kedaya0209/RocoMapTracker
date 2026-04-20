package com.luoke.app.context;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CameraManager {
    private boolean followMode = false;
    private double followScale = 1.5;

    private CameraManager() {
    }

    public static CameraManager getInstance() {
        return Holder.INSTANCE;
    }

    public void updateViewport() {
        MapManager mm = MapManager.getInstance();
        if (mm.getMapImage() == null || !followMode) return;

        mm.setScale(followScale);
        double cx = mm.getViewWidth() / 2;
        double cy = mm.getViewHeight() / 2;

        // ✅ 正确跟随：玩家已经包含 trim，直接用
        mm.setOffsetX(cx - mm.getPlayerX() * mm.getScale());
        mm.setOffsetY(cy - mm.getPlayerY() * mm.getScale());
        mm.ensureBounds();
    }

    private static class Holder {
        private static final CameraManager INSTANCE = new CameraManager();
    }
}