package com.luoke.app.context;

import javafx.geometry.Point2D;

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

    /**
     * 获取坐标管理器的单例实例
     */
    public static MapCoordinateManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册新地图的配置信息
     * @param key 地图的唯一标识符
     * @param w 地图的原始宽度（像素）
     * @param h 地图的原始高度（像素）
     * @param jsonZoom JSON配置文件中的缩放级别
     * @param img 实际地图图片的缩放级别
     */
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
     * 将地图坐标转换为屏幕坐标
     * @param x 原始地图坐标X值
     * @param y 原始地图坐标Y值
     * @return 转换后的屏幕坐标Point2D对象
     */
    public Point2D toScreen(double x, double y) {
        MapContext mm = MapContext.getInstance();
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());
        if (cfg == null) {
            throw new NullPointerException("当前地图未注册配置: " + mm.getCurrentMapKey());
        }

        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);
        double mx = cfg.width / 2 + x * scale;
        double my = cfg.height / 2 + y * scale;

        return new Point2D(mx, my);
    }

    /**
     * 将屏幕坐标转换为地图坐标
     *
     * @param screenX 屏幕坐标X值
     * @param screenY 屏幕坐标Y值
     * @return 转换后的地图坐标Point2D对象
     */
    public Point2D fromScreen(double screenX, double screenY) {
        MapContext mm = MapContext.getInstance();
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());
        if (cfg == null) {
            throw new NullPointerException("当前地图未注册配置: " + mm.getCurrentMapKey());
        }

        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);
        double x = (screenX - cfg.width / 2.0) / scale;
        double y = (screenY - cfg.height / 2.0) / scale;

        return new Point2D(x, y);
    }

    /**
     * 地图配置信息记录类
     */
    public record MapConfig(int jsonZoom, int imageZoom, double width, double height) {
    }
}
