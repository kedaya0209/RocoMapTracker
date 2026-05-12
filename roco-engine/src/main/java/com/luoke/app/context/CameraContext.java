package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 摄像机上下文管理类
 * 负责管理摄像机视角和跟随模式，实现地图视口的自动跟随玩家功能
 */
public class CameraContext {
    /** 跟随模式开关 */
    private final AtomicBoolean followMode = new AtomicBoolean(AppConfig.DEFAULT_FOLLOW_MODE);

    /** 跟随模式变化监听器 */
    private final CopyOnWriteArrayList<Runnable> followModeListeners = new CopyOnWriteArrayList<>();

    @Getter
    @Setter
    private double followScale = AppConfig.DEFAULT_FOLLOW_SCALE;

    private CameraContext() {
    }

    public static CameraContext getInstance() {
        return Holder.INSTANCE;
    }

    /** 注册跟随模式变化回调 */
    public void onFollowModeChange(Runnable listener) {
        followModeListeners.add(listener);
    }

    public boolean isFollowMode() {
        return followMode.get();
    }

    public void setFollowMode(boolean followMode) {
        if (this.followMode.compareAndSet(!followMode, followMode)) {
            for (Runnable r : followModeListeners) {
                r.run();
            }
        }
    }

    /**
     * 更新摄像机视口
     * 启用跟随模式时，自动计算视口偏移量使玩家位于中心
     */
    public void updateViewport() {
        MapContext mm = MapContext.getInstance();

        if (mm.getMapImage() == null || !isFollowMode() || !hasValidPlayerPosition()) {
            return;
        }

        mm.setScale(followScale);

        double cx = mm.getViewWidth() / 2;
        double cy = mm.getViewHeight() / 2;

        mm.setOffsetX(cx - mm.getPlayerX() * mm.getScale());
        mm.setOffsetY(cy - mm.getPlayerY() * mm.getScale());

        mm.ensureBounds();
    }

    /**
     * 检查玩家位置是否有效
     */
    public boolean hasValidPlayerPosition() {
        MapContext mm = MapContext.getInstance();
        return mm.getPlayerX() > 0 && mm.getPlayerY() > 0;
    }

    /**
     * 内部Holder类，实现线程安全的懒加载
     */
    private static class Holder {
        private static final CameraContext INSTANCE = new CameraContext();
    }
}
