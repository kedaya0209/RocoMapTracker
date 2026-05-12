package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.PlayerPositionEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import lombok.Getter;
import lombok.Setter;

import java.awt.image.BufferedImage;
import java.nio.MappedByteBuffer;

/**
 * 地图上下文管理：负责地图图像、视口状态（缩放/偏移）及玩家位置的维护与转换。
 * 采用单例模式（Holder）及观察者模式（HookRegistry）分发位置更新。
 */
@Getter
@Setter
public class MapContext {

    private BufferedImage mapImage;
    private MappedByteBuffer mapImageBuffer; // mmap 缓冲区强引用，防止 GC 回收映射内存
    private double mapWidth, mapHeight; // 原始地图尺寸

    /** * 视口状态：scale(缩放), offsetX/Y(相对于地图左上角的屏幕偏移)
     * 计算公式：CanvasX = offsetX + WorldX * scale
     */
    private double scale = 1.0, offsetX = 0, offsetY = 0;

    private double viewWidth, viewHeight; // 窗口/视口可视尺寸

    private double playerX = -1, playerY = -1; // 玩家世界坐标
    private double playerAngle = 0;             // 玩家朝向
    private boolean hasAngle = false;           // 是否有有效朝向数据
    private boolean playerInitialized = false;  // 是否已定位

    private String currentMapKey; // 当前地图唯一标识

    private MapContext() {}

    public static MapContext getInstance() {
        return Holder.INSTANCE;
    }

    /** 基础初始化：设置地图图源及视口尺寸 */
    public void init(BufferedImage image, double w, double h) {
        this.mapImage = image;
        this.mapWidth = image.getWidth();
        this.mapHeight = image.getHeight();
        this.viewWidth = w;
        this.viewHeight = h;
    }

    /** 完整初始化：初始化参数并注册到 MapCoordinateManager */
    public void initWithKey(BufferedImage image, double w, double h, String mapKey) {
        init(image, w, h);
        this.currentMapKey = mapKey;
        MapCoordinateManager.getInstance().registerMap(
                mapKey, (int) w, (int) h, AppConfig.JSON_ZOOM, AppConfig.MAP_ZOOM
        );
    }

    /**
     * mmap 版本：额外持有 MappedByteBuffer 引用防止 GC 回收
     */
    public void initWithKey(BufferedImage image, double w, double h, String mapKey, MappedByteBuffer buffer) {
        this.mapImageBuffer = buffer;
        initWithKey(image, w, h, mapKey);
    }

    /** 更新玩家状态并发布 PLAYER_UPDATE 事件 */
    public void updatePlayerState(double x, double y, Double visualAngle) {
        this.playerX = x;
        this.playerY = y;
        if (visualAngle != null) { this.playerAngle = visualAngle; this.hasAngle = true; }
        this.playerInitialized = true;
        HookRegistry.INSTANCE.publish(
                HookEventType.PLAYER_UPDATE,
                new PlayerPositionEvent(x, y)
        );
    }

    /** 世界坐标转屏幕 X：offsetX + playerX * scale */
    public double getPlayerCanvasX() {
        return offsetX + playerX * scale;
    }

    /** 世界坐标转屏幕 Y：offsetY + playerY * scale */
    public double getPlayerCanvasY() {
        return offsetY + playerY * scale;
    }

    /** * 以 (mx, my) 为中心进行缩放。
     * 算法：newOffset = mousePos - (mousePos - oldOffset) * (newScale / oldScale)
     */
    public void zoom(double factor, double mx, double my) {
        double minScale = Math.max(viewWidth / mapWidth, viewHeight / mapHeight);
        double newScale = Math.max(minScale, Math.min(scale * factor, 15));
        double f = newScale / scale;

        offsetX = mx - (mx - offsetX) * f;
        offsetY = my - (my - offsetY) * f;
        scale = newScale;

        ensureBounds();
    }

    /** 边界限制：地图大于视口时防止越界，小于视口时自动居中 */
    public void ensureBounds() {
        if (mapImage == null) return;
        double w = mapWidth * scale;
        double h = mapHeight * scale;

        offsetX = (w >= viewWidth) ? Math.min(0, Math.max(offsetX, viewWidth - w)) : (viewWidth - w) / 2;
        offsetY = (h >= viewHeight) ? Math.min(0, Math.max(offsetY, viewHeight - h)) : (viewHeight - h) / 2;
    }

    /** 线程安全的单例持有类 */
    private static class Holder {
        private static final MapContext INSTANCE = new MapContext();
    }
}