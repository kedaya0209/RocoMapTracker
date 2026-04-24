package com.luoke.app.context;

import javafx.geometry.Point2D;

import java.util.HashMap;
import java.util.Map;

public class MapCoordinateManager {
    private static final MapCoordinateManager INSTANCE = new MapCoordinateManager();
    private final Map<String, MapConfig> mapConfigMap = new HashMap<>();

    private MapCoordinateManager() {
    }

    public static MapCoordinateManager getInstance() {
        return INSTANCE;
    }

    public void registerMap(String key, int w, int h, int jsonZoom, int imgZoom) {
        mapConfigMap.put(key, new MapConfig(jsonZoom, imgZoom, w, h));
    }

    // 只做坐标计算！！！
    public Point2D toScreen(double x, double y) {
        MapContext mm = MapContext.getInstance();
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());

        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);
        double mx = cfg.width / 2 + x * scale;
        double my = cfg.height / 2 + y * scale;

        return new Point2D(
                mm.getOffsetX() + mx * mm.getScale(),
                mm.getOffsetY() + my * mm.getScale()
        );
    }

    public record MapConfig(int jsonZoom, int imageZoom, double width, double height) {
    }
}