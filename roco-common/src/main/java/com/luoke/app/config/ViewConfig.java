package com.luoke.app.config;

import java.util.Properties;

/**
 * 地图与视图配置持久化 
 */
public final class ViewConfig {

    // ============================================================
    // 地图与视图配置
    // ============================================================
    /**
     * 默认是否启用跟随模式
     */
    public static boolean DEFAULT_FOLLOW_MODE = false;
    /**
     * 跟随模式默认缩放值
     */
    public static double DEFAULT_FOLLOW_SCALE = 1.5;
    /**
     * 地图瓦片缩放级别
     */
    public static int MAP_ZOOM = 7;
    /**
     * 瓦片最小缩放级别
     */
    public static int MAP_MIN_ZOOM = 4;
    /**
     * 瓦片最大缩放级别
     */
    public static int MAP_MAX_ZOOM = 8;
    /**
     * JSON 配置中的缩放级别
     */
    public static int JSON_ZOOM = 7;
    /**
     * 坐标平滑系数（EMA alpha）
     */
    public static double COORDINATE_SMOOTH_FACTOR = 0.8;
    /**
     * 资源变灰检测距离（世界像素）
     */
    public static double GRAY_DISTANCE = 25;
    /**
     * 启用物资采集统计
     */
    public static boolean MATERIAL_COLLECTION = false;
    /**
     * 初始窗口宽度
     */
    public static double INITIAL_WINDOW_WIDTH = 1100;
    /**
     * 初始窗口高度
     */
    public static double INITIAL_WINDOW_HEIGHT = 800;
    /**
     * 跟随模式最小缩放
     */
    public static double INTERACTIVE_FOLLOW_MIN_SCALE = 0.3;
    /**
     * 跟随模式最大缩放
     */
    public static double INTERACTIVE_FOLLOW_MAX_SCALE = 5.0;
    /**
     * 路径节点点击/拖拽检测半径（逻辑像素）
     */
    public static double NODE_CLICK_THRESHOLD = 15.0;
    /**
     * 路径节点插入检测距离（逻辑像素）
     */
    public static double NODE_INSERT_THRESHOLD = 12.0;

    private ViewConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        MAP_ZOOM = ConfigHelper.getInt(prop, "map.zoom", MAP_ZOOM);
        COORDINATE_SMOOTH_FACTOR = ConfigHelper.getDouble(prop, "coordinate.smooth.factor", COORDINATE_SMOOTH_FACTOR);
        GRAY_DISTANCE = ConfigHelper.getDouble(prop, "gray.distance", GRAY_DISTANCE);
        MATERIAL_COLLECTION = ConfigHelper.getBool(prop, "material.collection", MATERIAL_COLLECTION);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 地图瓦片缩放级别\n");
        sb.append("map.zoom=").append(MAP_ZOOM).append("\n");
        sb.append("# 坐标平滑系数（EMA alpha）\n");
        sb.append("coordinate.smooth.factor=").append(COORDINATE_SMOOTH_FACTOR).append("\n");
        sb.append("# 资源变灰检测距离（世界像素）\n");
        sb.append("gray.distance=").append(GRAY_DISTANCE).append("\n");
        sb.append("# 启用物资采集统计\n");
        sb.append("material.collection=").append(MATERIAL_COLLECTION).append("\n\n");
    }
}
