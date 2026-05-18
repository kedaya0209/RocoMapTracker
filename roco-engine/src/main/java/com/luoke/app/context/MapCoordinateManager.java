package com.luoke.app.context;

import com.luoke.app.map.model.Point;

import java.util.HashMap;
import java.util.Map;

/**
 * 地图坐标管理器（单例模式）
 * 负责管理地图配置信息和坐标转换
 */
public class MapCoordinateManager {
    private static final MapCoordinateManager INSTANCE = new MapCoordinateManager();
    private final Map<String, MapConfig> mapConfigMap = new HashMap<>();

    private MapCoordinateManager() {
    }

    public static MapCoordinateManager getInstance() {
        return INSTANCE;
    }

    public void registerMap(String key, int w, int h, int jsonZoom, int img) {
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("地图宽度和高度必须为正数");
        }
        if (jsonZoom < 0 || img < 0) {
            throw new IllegalArgumentException("缩放级别不能为负数");
        }
        mapConfigMap.put(key, new MapConfig(jsonZoom, img, w, h));
    }

    /**
     * 地图坐标 → 屏幕坐标
     */
    public Point toScreen(double x, double y) {
        MapContext mm = MapContext.getInstance();
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());
        if (cfg == null) {
            throw new NullPointerException("当前地图未注册配置: " + mm.getCurrentMapKey());
        }
        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);
        double mx = cfg.width / 2 + x * scale;
        double my = cfg.height / 2 + y * scale;
        return new Point(mx, my);
    }

    /**
     * 屏幕坐标 → 地图坐标
     */
    public Point fromScreen(double screenX, double screenY) {
        MapContext mm = MapContext.getInstance();
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());
        if (cfg == null) {
            throw new NullPointerException("当前地图未注册配置: " + mm.getCurrentMapKey());
        }
        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);
        double x = (screenX - cfg.width / 2.0) / scale;
        double y = (screenY - cfg.height / 2.0) / scale;
        return new Point(x, y);
    }

    /**
     * 地图配置信息记录类
     */
    public record MapConfig(int jsonZoom, int imageZoom, double width, double height) {
    }
}
