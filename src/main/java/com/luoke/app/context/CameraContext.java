package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 摄像机上下文管理类
 * 负责管理摄像机视角和跟随模式，实现地图视口的自动跟随玩家功能
 */
public class CameraContext {
    /**
     * 跟随模式开关
     * true=自动跟随玩家，false=固定位置
     */
    private final BooleanProperty followMode = new SimpleBooleanProperty(AppConfig.DEFAULT_FOLLOW_MODE);

    /**
     * 跟随模式下的缩放比例
     */
    @Getter
    @Setter
    private double followScale = AppConfig.DEFAULT_FOLLOW_SCALE;

    private CameraContext() {
    }

    /**
     * 获取单例实例
     */
    public static CameraContext getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 获取跟随模式属性对象
     */
    public BooleanProperty followModeProperty() {
        return followMode;
    }

    /**
     * 获取跟随模式开关状态
     */
    public boolean isFollowMode() {
        return followMode.get();
    }

    /**
     * 设置跟随模式开关状态
     */
    public void setFollowMode(boolean followMode) {
        this.followMode.set(followMode);
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
