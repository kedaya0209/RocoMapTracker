package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import lombok.Getter;
import lombok.Setter;


/**
 * 地图上下文管理：负责视口状态（缩放/偏移）及玩家位置的维护与转换。
 * 瓦片金字塔模式下不再持有全图，仅管理元数据与运行时状态。
 */
@ThreadSafe
@Getter
@Setter
public class MapContext {

    private volatile double mapWidth, mapHeight; // 原始地图尺寸
    private volatile boolean initialized = false;

    /**
     * 视口状态：scale(缩放), offsetX/Y(相对于地图左上角的屏幕偏移)
     * 计算公式：CanvasX = offsetX + WorldX * scale
     */
    private volatile double scale = 1.0, offsetX = 0, offsetY = 0;

    private volatile double viewWidth, viewHeight; // 窗口/视口可视尺寸

    private volatile double playerX = -1, playerY = -1; // 玩家世界坐标
    private volatile double playerAngle = 0;             // 玩家朝向
    private volatile boolean hasAngle = false;           // 是否有有效朝向数据
    private volatile boolean playerInitialized = false;  // 是否已定位

    private String currentMapKey; // 当前地图唯一标识

    private MapContext() {
    }

    public static MapContext getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 初始化地图元数据并注册到 MapCoordinateManager（不再需要全图）
     */
    public void init(String mapKey, int mapW, int mapH) {
        this.currentMapKey = mapKey;
        this.mapWidth = mapW;
        this.mapHeight = mapH;
        this.initialized = true;
        MapCoordinateManager.getInstance().registerMap(
                mapKey, mapW, mapH, ViewConfig.JSON_ZOOM, ViewConfig.MAP_ZOOM
        );
    }

    /**
     * 更新玩家状态
     */
    public void updatePlayerState(double x, double y, Double visualAngle) {
        this.playerX = x;
        this.playerY = y;
        if (visualAngle != null) {
            this.playerAngle = visualAngle;
            this.hasAngle = true;
        }
        this.playerInitialized = true;
    }

    /**
     * 世界坐标转屏幕 X：offsetX + playerX * scale
     */
    public double getPlayerCanvasX() {
        return offsetX + playerX * scale;
    }

    /**
     * 世界坐标转屏幕 Y：offsetY + playerY * scale
     */
    public double getPlayerCanvasY() {
        return offsetY + playerY * scale;
    }

    /**
     * 以 (mx, my) 为中心进行缩放。
     * 算法：newOffset = mousePos - (mousePos - oldOffset) * (newScale / oldScale)
     */
    public void zoom(double factor, double mx, double my) {
        double minScale = Math.max(viewWidth / mapWidth, viewHeight / mapHeight);
        double newScale = Math.clamp(scale * factor, minScale, UiConfig.MAP_VIEW_MAX_SCALE);
        double f = newScale / scale;

        offsetX = mx - (mx - offsetX) * f;
        offsetY = my - (my - offsetY) * f;
        scale = newScale;

        ensureBounds();
    }

    /**
     * 边界限制：地图大于视口时防止越界，小于视口时自动居中
     */
    public void ensureBounds() {
        if (!initialized) return;
        double w = mapWidth * scale;
        double h = mapHeight * scale;

        offsetX = (w >= viewWidth) ? Math.clamp(offsetX, viewWidth - w, 0) : (viewWidth - w) / 2;
        offsetY = (h >= viewHeight) ? Math.clamp(offsetY, viewHeight - h, 0) : (viewHeight - h) / 2;
    }

    /**
     * 线程安全的单例持有类
     */
    @ThreadSafe
    private static class Holder {
        private static final MapContext INSTANCE = new MapContext();
    }
}