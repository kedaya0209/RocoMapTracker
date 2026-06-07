package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.FollowModeEvent;
import io.github.kedaya0209.roco.app.hook.event.NavModeEvent;
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

    /**
     * 跟随模式死区阈值（逻辑坐标像素）。
     * 玩家位移小于此值时跳过视口更新，避免小抖动导致地图晃动。
     */
    private static final double FOLLOW_DEAD_ZONE = 1.0;

    /** 上次跟随模式更新时的玩家位置（用于死区判断） */
    private volatile double lastFollowX = -1;
    private volatile double lastFollowY = -1;

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
        AppEvents.publish(NavModeEvent.class, new NavModeEvent(navMode));
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
            AppEvents.publish(FollowModeEvent.class, new FollowModeEvent(followMode));
        }
    }

    /**
     * 启用跟随模式时，立即缩放至 followScale 并居中玩家。
     * offsetY 以拼接坐标存储，MapRenderer 中转换为子图局部坐标。
     */
    private void applyFollowViewport() {
        MapContext mm = MapContext.getInstance();
        if (!mm.isInitialized() || !hasValidPlayerPosition()) return;

        double newScale = Math.clamp(followScale,
                Math.max(mm.getViewWidth() / mm.getMapWidth(), mm.getViewHeight() / mm.getMapHeight()),
                UiConfig.MAP_VIEW_MAX_SCALE);
        mm.setScale(newScale);

        double px = mm.getPlayerX();
        double py = mm.getPlayerY();
        double cx = mm.getViewWidth() / 2;
        double cy = mm.getViewHeight() / 2;
        mm.setOffsetX(cx - px * newScale);
        mm.setOffsetY(cy - py * newScale);
        mm.ensureBounds();
        lastFollowX = px;
        lastFollowY = py;
    }

    /**
     * 更新摄像机视口
     * 启用跟随模式时，自动计算视口偏移量使玩家位于中心。
     * 不覆盖 scale，缩放由 {@link MapContext#zoom} 统一管理。
     * <p>
     * 包含死区过滤：玩家位移小于 {@link #FOLLOW_DEAD_ZONE} 时跳过更新，
     * 避免小抖动导致地图晃动。
     * </p>
     * 注意：offsetY 以拼接坐标存储，MapRenderer 中会转换为子图局部坐标。
     */
    public void updateViewport() {
        MapContext mm = MapContext.getInstance();

        if (!mm.isInitialized() || !isFollowMode() || !hasValidPlayerPosition()) {
            return;
        }

        double px = mm.getPlayerX();
        double py = mm.getPlayerY();

        // 死区过滤：位移太小则跳过
        double dx = px - lastFollowX;
        double dy = py - lastFollowY;
        if (dx * dx + dy * dy < FOLLOW_DEAD_ZONE * FOLLOW_DEAD_ZONE) {
            return;
        }

        double cx = mm.getViewWidth() / 2;
        double cy = mm.getViewHeight() / 2;

        mm.setOffsetX(cx - px * mm.getScale());
        mm.setOffsetY(cy - py * mm.getScale());

        mm.ensureBounds();
        lastFollowX = px;
        lastFollowY = py;
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
