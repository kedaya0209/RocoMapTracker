package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
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

    private volatile boolean caveMode = false;           // 是否在洞穴区域
    private volatile int caveIndex = -1;                 // 洞穴子图索引（-1=不在洞穴）
    private volatile String caveName = null;             // 洞穴名称

    // 渲染用活跃子图（0=大陆，1-5=洞穴）
    private volatile int activeSubImageIndex = 0;
    private volatile double activeSubImageOffsetY = 0;

    /**
     * 手动图层覆盖：
     * -1=自动跟随匹配结果，{@code >=0}=强制显示该层所有洞穴的瓦片叠加
     */
    private volatile int activeLayer = -1;

    /**
     * 单洞穴覆盖（在 activeLayer 之上）：
     * -1=显示该层全部洞穴，{@code >=0}=仅显示指定子图索引的洞穴瓦片
     */
    private volatile int overrideCaveIndex = -1;

    private volatile CompositeMapMetadata multiMapMetadata = null;

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
     * 更新洞穴模式状态。
     * @param inCave  是否在洞穴中
     * @param idx     洞穴子图索引（-1=不在洞穴）
     * @param name    洞穴名称（null=不在洞穴）
     */
    public void updateCaveMode(boolean inCave, int idx, String name) {
        this.caveMode = inCave;
        this.caveIndex = idx;
        this.caveName = name;
    }

    public boolean isCaveMode() { return caveMode; }
    public int getCaveIndex() { return caveIndex; }
    public String getCaveName() { return caveName; }

    public CompositeMapMetadata getMultiMapMetadata() { return multiMapMetadata; }
    public void setMultiMapMetadata(CompositeMapMetadata meta) { this.multiMapMetadata = meta; }

    // ==================== 渲染子图切换 ====================

    public int getActiveSubImageIndex() { return activeSubImageIndex; }
    public double getActiveSubImageOffsetY() { return activeSubImageOffsetY; }

    /**
     * 设置活跃渲染子图。渲染系统只显示此子图区域（8192x8192），
     * 其它子图的瓦片不参与主渲染。
     */
    public void setActiveSubImage(int index, double offsetY) {
        this.activeSubImageIndex = index;
        this.activeSubImageOffsetY = offsetY;
    }

    /**
     * @return 手动图层覆盖，-1=自动
     */
    public int getActiveLayer() { return activeLayer; }

    /**
     * 设置手动图层覆盖。
     * @param layer -1=自动（跟随匹配结果），{@code >=0}=强制显示该层所有洞穴瓦片
     */
    public void setActiveLayer(int layer) { this.activeLayer = layer; }

    /**
     * @return 是否有手动覆盖生效
     */
    public boolean isManualOverride() { return activeLayer >= 0; }

    /**
     * @return 当前单洞穴覆盖索引，-1=无覆盖
     */
    public int getOverrideCaveIndex() { return overrideCaveIndex; }

    /**
     * 设置单洞穴覆盖索引。仅在 activeLayer >= 0 时有效。
     * @param idx 子图列表索引，-1=显示该层全部洞穴
     */
    public void setOverrideCaveIndex(int idx) { this.overrideCaveIndex = idx; }

    /**
     * 重置单洞穴覆盖。
     */
    public void resetOverrideCaveIndex() { this.overrideCaveIndex = -1; }

    /**
     * 根据 activeLayer + overrideCaveIndex 获取当前要渲染的瓦片目录列表。
     * 优先返回单洞穴覆盖指定的瓦片目录。
     */
    public java.util.List<String> getCaveDirsToRender() {
        if (activeLayer < 0 || multiMapMetadata == null) return java.util.List.of();
        if (overrideCaveIndex >= 0) {
            java.util.List<CompositeMapMetadata.SubImageInfo> subs = multiMapMetadata.subImages();
            if (overrideCaveIndex < subs.size()) {
                String dir = subs.get(overrideCaveIndex).tileDir();
                return dir == null || dir.isEmpty() ? java.util.List.of() : java.util.List.of(dir);
            }
        }
        return getCaveDirsForLayer(activeLayer);
    }

    /**
     * 获取指定图层所有洞穴的瓦片目录列表。
     */
    public java.util.List<String> getCaveDirsForLayer(int layer) {
        if (multiMapMetadata == null) return java.util.List.of();
        java.util.List<String> dirs = new java.util.ArrayList<>();
        for (CompositeMapMetadata.SubImageInfo sub : multiMapMetadata.subImages()) {
            if (sub.isCave() && sub.layer() == layer) {
                dirs.add(sub.tileDir());
            }
        }
        return dirs;
    }

    /**
     * 获取指定图层所有洞穴的列表索引（用于 UI 按钮状态判断）。
     */
    public java.util.List<Integer> getCaveIndicesForLayer(int layer) {
        if (multiMapMetadata == null) return java.util.List.of();
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        java.util.List<CompositeMapMetadata.SubImageInfo> subs = multiMapMetadata.subImages();
        for (int i = 0; i < subs.size(); i++) {
            CompositeMapMetadata.SubImageInfo sub = subs.get(i);
            if (sub.isCave() && sub.layer() == layer) {
                indices.add(i);
            }
        }
        return indices;
    }

    /**
     * 获取渲染用玩家 Y 坐标（相对于活跃子图原点）。
     */
    public double getRenderPlayerY() {
        return playerY - activeSubImageOffsetY;
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