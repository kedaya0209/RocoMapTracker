package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.FollowModeEvent;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 摄像机上下文管理类
 * 负责管理摄像机视角和跟随模式，实现地图视口的自动跟随玩家功能
 */
@ThreadSafe
public class CameraContext {
    /**
     * 跟随模式开关
     */
    private final AtomicBoolean followMode = new AtomicBoolean(ViewConfig.DEFAULT_FOLLOW_MODE);

    @Getter
    @Setter
    private volatile double followScale = ViewConfig.DEFAULT_FOLLOW_SCALE;

    /** 导航模式是否启用 */
    @Getter
    private volatile boolean navMode;
    /** 导航模式下的地图旋转角度（度） */
    @Getter
    @Setter
    private volatile double navAngle;

    private CameraContext() {
    }

    public void setNavMode(boolean navMode) {
        this.navMode = navMode;
        if (!navMode) {
            this.navAngle = 0;
        }
    }

    public static CameraContext getInstance() {
        return Holder.INSTANCE;
    }

    public boolean isFollowMode() {
        return followMode.get();
    }

    public void setFollowMode(boolean followMode) {
        if (this.followMode.compareAndSet(!followMode, followMode)) {
            if (followMode) {
                applyFollowViewport();
            }
            HookRegistry.INSTANCE.publish(HookEventType.FOLLOW_MODE_CHANGED,
                    new FollowModeEvent(followMode));
        }
    }

    /**
     * 启用跟随模式时，立即缩放至 followScale 并居中玩家。
     */
    private void applyFollowViewport() {
        MapContext mm = MapContext.getInstance();
        if (!mm.isInitialized() || !hasValidPlayerPosition()) return;

        double newScale = Math.clamp(followScale,
                Math.max(mm.getViewWidth() / mm.getMapWidth(), mm.getViewHeight() / mm.getMapHeight()),
                UiConfig.MAP_VIEW_MAX_SCALE);
        mm.setScale(newScale);

        double cx = mm.getViewWidth() / 2;
        double cy = mm.getViewHeight() / 2;
        mm.setOffsetX(cx - mm.getPlayerX() * newScale);
        mm.setOffsetY(cy - mm.getPlayerY() * newScale);
        mm.ensureBounds();
    }

    /**
     * 更新摄像机视口
     * 启用跟随模式时，自动计算视口偏移量使玩家位于中心。
     * 不覆盖 scale，缩放由 {@link MapContext#zoom} 统一管理。
     */
    public void updateViewport() {
        MapContext mm = MapContext.getInstance();

        if (!mm.isInitialized() || !isFollowMode() || !hasValidPlayerPosition()) {
            return;
        }

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
    @ThreadSafe
    private static class Holder {
        private static final CameraContext INSTANCE = new CameraContext();
    }
}
