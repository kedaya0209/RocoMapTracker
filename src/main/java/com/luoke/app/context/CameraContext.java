package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CameraContext {
    private boolean followMode = AppConfig.DEFAULT_FOLLOW_MODE;
    private double followScale = AppConfig.DEFAULT_FOLLOW_SCALE;

    private CameraContext() {
    }

    public static CameraContext getInstance() {
        return Holder.INSTANCE;
    }

    public void updateViewport() {
        MapContext mm = MapContext.getInstance();
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
        private static final CameraContext INSTANCE = new CameraContext();
    }
}