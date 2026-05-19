package com.luoke.app.config;

import java.util.Properties;

/**
 * 统计显示配置持久化 
 */
public final class StatsConfig {

    // ============================================================
    // 统计显示参数
    // ============================================================
    /**
     * 显示匹配耗时
     */
    public static boolean SHOW_STATS_MATCH_TIME = true;
    /**
     * 显示朝向检测耗时
     */
    public static boolean SHOW_STATS_DIR_TIME = true;
    /**
     * 显示 SIFT 小地图检测耗时
     */
    public static boolean SHOW_STATS_SIFT_MINIMAP_TIME = true;
    /**
     * 显示 SIFT 特征提取耗时
     */
    public static boolean SHOW_STATS_SIFT_EXTRACT_TIME = true;
    /**
     * 显示 SIFT FLANN 匹配耗时
     */
    public static boolean SHOW_STATS_SIFT_FLANN_TIME = true;
    /**
     * 显示 FPS
     */
    public static boolean SHOW_STATS_FPS = true;
    /**
     * FPS 计算窗口（毫秒）
     */
    public static int STATS_FPS_WINDOW_MS = 1000;
    /**
     * 资源点空间网格单元大小（屏幕坐标）
     */
    public static int GRID_CELL_SIZE = 120;

    private StatsConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        SHOW_STATS_FPS = ConfigHelper.getBool(prop, "show.stats.fps", SHOW_STATS_FPS);
        SHOW_STATS_MATCH_TIME = ConfigHelper.getBool(prop, "show.stats.match.time", SHOW_STATS_MATCH_TIME);
        SHOW_STATS_DIR_TIME = ConfigHelper.getBool(prop, "show.stats.dir.time", SHOW_STATS_DIR_TIME);
        SHOW_STATS_SIFT_MINIMAP_TIME = ConfigHelper.getBool(prop, "show.stats.sift.minimap.time", SHOW_STATS_SIFT_MINIMAP_TIME);
        SHOW_STATS_SIFT_EXTRACT_TIME = ConfigHelper.getBool(prop, "show.stats.sift.extract.time", SHOW_STATS_SIFT_EXTRACT_TIME);
        SHOW_STATS_SIFT_FLANN_TIME = ConfigHelper.getBool(prop, "show.stats.sift.flann.time", SHOW_STATS_SIFT_FLANN_TIME);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 显示 FPS\n");
        sb.append("show.stats.fps=").append(SHOW_STATS_FPS).append("\n");
        sb.append("# 显示匹配耗时\n");
        sb.append("show.stats.match.time=").append(SHOW_STATS_MATCH_TIME).append("\n");
        sb.append("# 显示朝向检测耗时\n");
        sb.append("show.stats.dir.time=").append(SHOW_STATS_DIR_TIME).append("\n");
        sb.append("# 显示 SIFT 小地图检测耗时\n");
        sb.append("show.stats.sift.minimap.time=").append(SHOW_STATS_SIFT_MINIMAP_TIME).append("\n");
        sb.append("# 显示 SIFT 特征提取耗时\n");
        sb.append("show.stats.sift.extract.time=").append(SHOW_STATS_SIFT_EXTRACT_TIME).append("\n");
        sb.append("# 显示 SIFT FLANN 匹配耗时\n");
        sb.append("show.stats.sift.flann.time=").append(SHOW_STATS_SIFT_FLANN_TIME).append("\n\n");
    }
}
