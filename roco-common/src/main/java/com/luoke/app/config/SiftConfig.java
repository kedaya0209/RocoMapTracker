package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * SIFT 与匹配参数持久化 
 */
@NotThreadSafe
public final class SiftConfig {

    // ============================================================
    // SIFT 与匹配参数
    // ============================================================
    /**
     * 匹配器类型（SIFT / SIFT-PCA / SIFT-ULTRA / SIFT-PCA-ULTRA）
     */
    public static String MAP_MATCHAER = "SIFT-ULTRA";
    /**
     * 缩放因子
     */
    public static double SCALE_FACTOR = 1.0;

    // --- SIFT 检测器 ---
    /**
     * SIFT 最大特征点数（0=无限制）
     */
    public static int SIFT_N_FEATURES = 0;
    /**
     * SIFT 每层组数
     */
    public static int SIFT_N_OCTAVE_LAYERS = 3;
    /**
     * SIFT 对比度阈值
     */
    public static double SIFT_CONTRAST_THRESHOLD = 0.001;
    /**
     * SIFT 边缘阈值
     */
    public static double SIFT_EDGE_THRESHOLD = 50.0;
    /**
     * SIFT sigma
     */
    public static double SIFT_SIGMA = 1.6;

    // --- FLANN 索引 ---
    /**
     * FLANN KD 树数量
     */
    public static int FLANN_KD_TREES = 1;
    /**
     * FLANN 搜索检查次数
     */
    public static int FLANN_SEARCH_CHECKS = 24;

    // --- 重叠分块训练 ---
    /**
     * 训练瓦片基础尺寸（像素）
     */
    public static int SIFT_TILE_SIZE = 2000;
    /**
     * 训练瓦片重叠宽度（像素）
     */
    public static int SIFT_TILE_OVERLAP = 200;
    /**
     * 启用分块的地图像素阈值
     */
    public static long SIFT_LARGE_MAP_THRESHOLD = 3000L * 3000L;
    /**
     * 重叠区域特征去重距离（像素）
     */
    public static float SIFT_DEDUP_DISTANCE = 4.0f;

    // --- 匹配过滤 ---
    /**
     * 比率测试阈值
     */
    public static float MATCH_RATIO_THRESHOLD = 0.6f;
    /**
     * 匹配点最小数量
     */
    public static int MATCH_MIN_COUNT = 10;
    /**
     * 空间过滤搜索半径（像素）
     */
    public static int SEARCH_RADIUS = 500;

    // --- RANSAC ---
    /**
     * RANSAC 重投影误差阈值
     */
    public static double RANSAC_REPROJ_THRESHOLD = 10.0;
    /**
     * RANSAC 最大迭代次数
     */
    public static int RANSAC_MAX_ITERS = 200;
    /**
     * RANSAC 置信度
     */
    public static double RANSAC_CONFIDENCE = 0.95;

    // --- 匹配开关 ---
    /**
     * SIFT 匹配总开关
     */
    public static volatile boolean SIFT_MATCHING_ENABLED = true;

    // --- ROI 与匹配调度 ---
    /**
     * 小地图 ROI 万分比坐标 X
     */
    public static int ROI_MAP_X = 8900;
    /**
     * 小地图 ROI 万分比坐标 Y
     */
    public static int ROI_MAP_Y = 300;
    /**
     * 小地图 ROI 万分比宽度
     */
    public static int ROI_MAP_W = 1000;
    /**
     * 小地图 ROI 万分比高度（0=自动正方形）
     */
    public static int ROI_MAP_H = 0;
    /**
     * SIFT 匹配等待超时（毫秒）
     */
    public static long MATCH_TIMEOUT_MS = 500;

    // --- 箭头检测 ---
    /**
     * 箭头 CNN 裁剪尺寸（像素，正方形）
     */
    public static int ARROW_CROP_SIZE = 64;

    private SiftConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        MAP_MATCHAER = ConfigHelper.getStr(prop, "map.matcher", MAP_MATCHAER);
        SCALE_FACTOR = ConfigHelper.getDouble(prop, "scale.factor", SCALE_FACTOR);
        SIFT_N_FEATURES = ConfigHelper.getInt(prop, "sift.n.features", SIFT_N_FEATURES);
        FLANN_KD_TREES = ConfigHelper.getInt(prop, "flann.kd.trees", FLANN_KD_TREES);
        FLANN_SEARCH_CHECKS = ConfigHelper.getInt(prop, "flann.search.checks", FLANN_SEARCH_CHECKS);
        MATCH_RATIO_THRESHOLD = (float) ConfigHelper.getDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        MATCH_MIN_COUNT = ConfigHelper.getInt(prop, "match.min.count", MATCH_MIN_COUNT);
        SEARCH_RADIUS = ConfigHelper.getInt(prop, "search.radius", SEARCH_RADIUS);
        RANSAC_MAX_ITERS = ConfigHelper.getInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        MATCH_TIMEOUT_MS = ConfigHelper.getLong(prop, "match.timeout.ms", MATCH_TIMEOUT_MS);
        ARROW_CROP_SIZE = ConfigHelper.getInt(prop, "arrow.crop.size", ARROW_CROP_SIZE);
        SIFT_MATCHING_ENABLED = ConfigHelper.getBool(prop, "sift.matching.enabled", true);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 匹配器类型（SIFT / SIFT-PCA / SIFT-ULTRA / SIFT-PCA-ULTRA）\n");
        sb.append("map.matcher=").append(MAP_MATCHAER).append("\n");
        sb.append("# 缩放因子\n");
        sb.append("scale.factor=").append(SCALE_FACTOR).append("\n");
        sb.append("# SIFT 最大特征点数（0=无限制）\n");
        sb.append("sift.n.features=").append(SIFT_N_FEATURES).append("\n");
        sb.append("# FLANN KD 树数量\n");
        sb.append("flann.kd.trees=").append(FLANN_KD_TREES).append("\n");
        sb.append("# FLANN 搜索检查次数\n");
        sb.append("flann.search.checks=").append(FLANN_SEARCH_CHECKS).append("\n");
        sb.append("# 比率测试阈值\n");
        sb.append("match.ratio.threshold=").append(MATCH_RATIO_THRESHOLD).append("\n");
        sb.append("# 匹配点最小数量\n");
        sb.append("match.min.count=").append(MATCH_MIN_COUNT).append("\n");
        sb.append("# 空间过滤搜索半径（像素）\n");
        sb.append("search.radius=").append(SEARCH_RADIUS).append("\n");
        sb.append("# RANSAC 最大迭代次数\n");
        sb.append("ransac.max.iters=").append(RANSAC_MAX_ITERS).append("\n");
        sb.append("# SIFT 匹配等待超时（毫秒）\n");
        sb.append("match.timeout.ms=").append(MATCH_TIMEOUT_MS).append("\n");
        sb.append("# 箭头 CNN 裁剪尺寸（像素，正方形）\n");
        sb.append("arrow.crop.size=").append(ARROW_CROP_SIZE).append("\n");
        sb.append("# SIFT 匹配总开关\n");
        sb.append("sift.matching.enabled=").append(SIFT_MATCHING_ENABLED).append("\n\n");
    }
}
