package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.roi.RoiRect;
import java.util.Properties;

/**
 * SIFT 与匹配参数持久化 
 */
@NotThreadSafe
public final class SiftConfig {

    // ============================================================
    // 匹配器类型
    // ============================================================
    /**
     * 匹配器类型（SIFT / SIFT-PCA / SIFT-ULTRA / SIFT-PCA-ULTRA）
     */
    public static String MAP_MATCHAER = "SIFT-ULTRA";

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
    public static double SIFT_CONTRAST_THRESHOLD = 0.0005;
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
    public static int FLANN_KD_TREES = 4;
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
    public static int SEARCH_RADIUS = 300;

    // --- RANSAC ---
    /**
     * RANSAC 重投影误差阈值
     */
    public static double RANSAC_REPROJ_THRESHOLD = 5.0;
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

    // ============================================================
    // 亮度路由（Brightness Routing）
    // ============================================================
    /**
     * 洞穴→大陆切换阈值（dark_ratio 低于此值切换到大陆）
     */
    public static float ROUTING_CAVE_TO_OW = 0.15f;
    /**
     * 大陆→洞穴切换阈值（dark_ratio 高于此值切换到洞穴）
     */
    public static float ROUTING_OW_TO_CAVE = 0.35f;
    /**
     * Sigmoid LUT 中点
     */
    public static int DARK_SIGMOID_MIDPOINT = 100;
    /**
     * Sigmoid LUT 陡峭度
     */
    public static float DARK_SIGMOID_STEEPNESS = 0.05f;
    /**
     * 暗像素采样步长
     */
    public static int DARK_STRIDE = 4;
    /**
     * Sigmoid 输出暗阈值（低于此值算暗像素）
     */
    public static int DARK_SIGMOID_THRESHOLD = 100;
    /**
     * 灰度下限裁剪
     */
    public static int DARK_TRIM_LOW = 5;
    /**
     * 灰度上限裁剪
     */
    public static int DARK_TRIM_HIGH = 250;
    /**
     * is_dark_minimap 默认暗像素比例阈值
     */
    public static float DARK_RATIO_THRESHOLD = 0.5f;

    // ============================================================
    // MiniMap 检测（MiniMap Detection）
    // ============================================================
    /**
     * HoughCircles 缩小宽度
     */
    public static int MM_SMALL_WIDTH = 120;
    /**
     * 圆验证最小黑像素比例
     */
    public static float MM_BLACK_RATIO_THRESHOLD = 0.15f;
    /**
     * 圆心最大偏移比例
     */
    public static float MM_CENTER_OFFSET_RATIO = 0.2f;
    /**
     * HoughCircles dp
     */
    public static float MM_HOUGH_DP = 1.2f;
    /**
     * HoughCircles param1（Canny 阈值）
     */
    public static int MM_HOUGH_PARAM1 = 50;
    /**
     * HoughCircles param2（圆心阈值）
     */
    public static int MM_HOUGH_PARAM2 = 35;
    /**
     * HoughCircles 最小半径比例
     */
    public static float MM_HOUGH_MIN_RADIUS_RATIO = 0.4f;
    /**
     * HoughCircles 最大半径比例
     */
    public static float MM_HOUGH_MAX_RADIUS_RATIO = 0.55f;
    /**
     * 圆边界采样点数
     */
    public static int MM_CIRCLE_SAMPLE_COUNT = 120;
    /**
     * 圆边界采样步长（度）
     */
    public static float MM_CIRCLE_STEP_DEG = 3.0f;
    /**
     * 圆边界黑色阈值
     */
    public static int MM_CIRCLE_BLACK_THRESHOLD = 150;

    // ============================================================
    // 箭头检测（Arrow Detection）
    // ============================================================
    /**
     * 箭头 HSV 色相下限
     */
    public static int ARROW_HUE_LOW = 15;
    /**
     * 箭头 HSV 色相上限
     */
    public static int ARROW_HUE_HIGH = 25;
    /**
     * 箭头 HSV 饱和度下限
     */
    public static int ARROW_SAT_LOW = 200;
    /**
     * 箭头 HSV 饱和度上限
     */
    public static int ARROW_SAT_HIGH = 240;
    /**
     * 箭头 HSV 明度下限
     */
    public static int ARROW_VAL_LOW = 230;
    /**
     * 箭头 HSV 明度上限
     */
    public static int ARROW_VAL_HIGH = 255;
    /**
     * 箭头最小轮廓面积
     */
    public static int ARROW_MIN_CONTOUR_AREA = 20;
    /**
     * 箭头检测最小半径
     */
    public static int ARROW_MIN_RADIUS = 15;
    /**
     * 箭头分析裁剪尺寸
     */
    public static int ARROW_CROP_SIZE = 64;

    // ============================================================
    // ROI 裁剪（ROI Crop）
    // ============================================================
    /**
     * ROI 裁剪半径倍数
     */
    public static float CROP_MARGIN = 1.5f;
    /**
     * ROI 裁剪最小尺寸
     */
    public static int CROP_MIN_DIM = 64;
    /**
     * ROI 裁剪最大面积比例
     */
    public static float CROP_MAX_AREA_RATIO = 0.85f;

    // ============================================================
    // 训练/特征（Training / Features）
    // ============================================================
    /**
     * PCA 目标维度
     */
    public static int PCA_TARGET_DIM = 64;
    /**
     * 内容裁剪亮度阈值
     */
    public static int CONTENT_RECT_THRESHOLD = 16;
    /**
     * 内容裁剪采样步长
     */
    public static int CONTENT_RECT_STRIDE = 4;

    private SiftConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        MAP_MATCHAER = ConfigHelper.getStr(prop, "map.matcher", MAP_MATCHAER);
        SIFT_N_FEATURES = ConfigHelper.getInt(prop, "sift.n.features", SIFT_N_FEATURES);
        SIFT_N_OCTAVE_LAYERS = ConfigHelper.getInt(prop, "sift.n.octave.layers", SIFT_N_OCTAVE_LAYERS);
        SIFT_CONTRAST_THRESHOLD = ConfigHelper.getDouble(prop, "sift.contrast.threshold", SIFT_CONTRAST_THRESHOLD);
        SIFT_EDGE_THRESHOLD = ConfigHelper.getDouble(prop, "sift.edge.threshold", SIFT_EDGE_THRESHOLD);
        SIFT_SIGMA = ConfigHelper.getDouble(prop, "sift.sigma", SIFT_SIGMA);
        FLANN_KD_TREES = ConfigHelper.getInt(prop, "flann.kd.trees", FLANN_KD_TREES);
        FLANN_SEARCH_CHECKS = ConfigHelper.getInt(prop, "flann.search.checks", FLANN_SEARCH_CHECKS);
        MATCH_RATIO_THRESHOLD = (float) ConfigHelper.getDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        MATCH_MIN_COUNT = ConfigHelper.getInt(prop, "match.min.count", MATCH_MIN_COUNT);
        SEARCH_RADIUS = ConfigHelper.getInt(prop, "search.radius", SEARCH_RADIUS);
        RANSAC_REPROJ_THRESHOLD = ConfigHelper.getDouble(prop, "ransac.reproj.threshold", RANSAC_REPROJ_THRESHOLD);
        RANSAC_MAX_ITERS = ConfigHelper.getInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        RANSAC_CONFIDENCE = ConfigHelper.getDouble(prop, "ransac.confidence", RANSAC_CONFIDENCE);
        SIFT_LARGE_MAP_THRESHOLD = ConfigHelper.getLong(prop, "sift.large.map.threshold", SIFT_LARGE_MAP_THRESHOLD);
        MATCH_TIMEOUT_MS = ConfigHelper.getLong(prop, "match.timeout.ms", MATCH_TIMEOUT_MS);
        SIFT_MATCHING_ENABLED = ConfigHelper.getBool(prop, "sift.matching.enabled", true);
        SIFT_TILE_SIZE = ConfigHelper.getInt(prop, "sift.tile.size", SIFT_TILE_SIZE);
        SIFT_TILE_OVERLAP = ConfigHelper.getInt(prop, "sift.tile.overlap", SIFT_TILE_OVERLAP);
        SIFT_DEDUP_DISTANCE = (float) ConfigHelper.getDouble(prop, "sift.dedup.distance", SIFT_DEDUP_DISTANCE);

        // 亮度路由
        ROUTING_CAVE_TO_OW = (float) ConfigHelper.getDouble(prop, "routing.cave.to.ow", ROUTING_CAVE_TO_OW);
        ROUTING_OW_TO_CAVE = (float) ConfigHelper.getDouble(prop, "routing.ow.to.cave", ROUTING_OW_TO_CAVE);
        DARK_SIGMOID_MIDPOINT = ConfigHelper.getInt(prop, "routing.sigmoid.midpoint", DARK_SIGMOID_MIDPOINT);
        DARK_SIGMOID_STEEPNESS = (float) ConfigHelper.getDouble(prop, "routing.sigmoid.steepness", DARK_SIGMOID_STEEPNESS);
        DARK_STRIDE = ConfigHelper.getInt(prop, "routing.dark.stride", DARK_STRIDE);
        DARK_SIGMOID_THRESHOLD = ConfigHelper.getInt(prop, "routing.sigmoid.threshold", DARK_SIGMOID_THRESHOLD);
        DARK_TRIM_LOW = ConfigHelper.getInt(prop, "routing.trim.low", DARK_TRIM_LOW);
        DARK_TRIM_HIGH = ConfigHelper.getInt(prop, "routing.trim.high", DARK_TRIM_HIGH);
        DARK_RATIO_THRESHOLD = (float) ConfigHelper.getDouble(prop, "routing.dark.ratio", DARK_RATIO_THRESHOLD);

        // MiniMap 检测
        MM_SMALL_WIDTH = ConfigHelper.getInt(prop, "mm.small.width", MM_SMALL_WIDTH);
        MM_BLACK_RATIO_THRESHOLD = (float) ConfigHelper.getDouble(prop, "mm.black.ratio", MM_BLACK_RATIO_THRESHOLD);
        MM_CENTER_OFFSET_RATIO = (float) ConfigHelper.getDouble(prop, "mm.center.offset", MM_CENTER_OFFSET_RATIO);
        MM_HOUGH_DP = (float) ConfigHelper.getDouble(prop, "mm.hough.dp", MM_HOUGH_DP);
        MM_HOUGH_PARAM1 = ConfigHelper.getInt(prop, "mm.hough.param1", MM_HOUGH_PARAM1);
        MM_HOUGH_PARAM2 = ConfigHelper.getInt(prop, "mm.hough.param2", MM_HOUGH_PARAM2);
        MM_HOUGH_MIN_RADIUS_RATIO = (float) ConfigHelper.getDouble(prop, "mm.hough.min.radius", MM_HOUGH_MIN_RADIUS_RATIO);
        MM_HOUGH_MAX_RADIUS_RATIO = (float) ConfigHelper.getDouble(prop, "mm.hough.max.radius", MM_HOUGH_MAX_RADIUS_RATIO);
        MM_CIRCLE_SAMPLE_COUNT = ConfigHelper.getInt(prop, "mm.circle.samples", MM_CIRCLE_SAMPLE_COUNT);
        MM_CIRCLE_STEP_DEG = (float) ConfigHelper.getDouble(prop, "mm.circle.step", MM_CIRCLE_STEP_DEG);
        MM_CIRCLE_BLACK_THRESHOLD = ConfigHelper.getInt(prop, "mm.circle.black", MM_CIRCLE_BLACK_THRESHOLD);

        // 箭头检测
        ARROW_HUE_LOW = ConfigHelper.getInt(prop, "arrow.hue.low", ARROW_HUE_LOW);
        ARROW_HUE_HIGH = ConfigHelper.getInt(prop, "arrow.hue.high", ARROW_HUE_HIGH);
        ARROW_SAT_LOW = ConfigHelper.getInt(prop, "arrow.sat.low", ARROW_SAT_LOW);
        ARROW_SAT_HIGH = ConfigHelper.getInt(prop, "arrow.sat.high", ARROW_SAT_HIGH);
        ARROW_VAL_LOW = ConfigHelper.getInt(prop, "arrow.val.low", ARROW_VAL_LOW);
        ARROW_VAL_HIGH = ConfigHelper.getInt(prop, "arrow.val.high", ARROW_VAL_HIGH);
        ARROW_MIN_CONTOUR_AREA = ConfigHelper.getInt(prop, "arrow.min.area", ARROW_MIN_CONTOUR_AREA);
        ARROW_MIN_RADIUS = ConfigHelper.getInt(prop, "arrow.min.radius", ARROW_MIN_RADIUS);
        ARROW_CROP_SIZE = ConfigHelper.getInt(prop, "arrow.crop.size", ARROW_CROP_SIZE);

        // ROI 裁剪
        CROP_MARGIN = (float) ConfigHelper.getDouble(prop, "crop.margin", CROP_MARGIN);
        CROP_MIN_DIM = ConfigHelper.getInt(prop, "crop.min.dim", CROP_MIN_DIM);
        CROP_MAX_AREA_RATIO = (float) ConfigHelper.getDouble(prop, "crop.max.area", CROP_MAX_AREA_RATIO);

        // 训练/特征
        PCA_TARGET_DIM = ConfigHelper.getInt(prop, "pca.target.dim", PCA_TARGET_DIM);
        CONTENT_RECT_THRESHOLD = ConfigHelper.getInt(prop, "content.rect.threshold", CONTENT_RECT_THRESHOLD);
        CONTENT_RECT_STRIDE = ConfigHelper.getInt(prop, "content.rect.stride", CONTENT_RECT_STRIDE);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 匹配器类型（SIFT / SIFT-PCA / SIFT-ULTRA / SIFT-PCA-ULTRA）\n");
        sb.append("map.matcher=").append(MAP_MATCHAER).append("\n");
        sb.append("# SIFT 最大特征点数（0=无限制）\n");
        sb.append("sift.n.features=").append(SIFT_N_FEATURES).append("\n");
        sb.append("# SIFT 每层组数\n");
        sb.append("sift.n.octave.layers=").append(SIFT_N_OCTAVE_LAYERS).append("\n");
        sb.append("# SIFT 对比度阈值\n");
        sb.append("sift.contrast.threshold=").append(SIFT_CONTRAST_THRESHOLD).append("\n");
        sb.append("# SIFT 边缘阈值\n");
        sb.append("sift.edge.threshold=").append(SIFT_EDGE_THRESHOLD).append("\n");
        sb.append("# SIFT sigma\n");
        sb.append("sift.sigma=").append(SIFT_SIGMA).append("\n");
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
        sb.append("# RANSAC 重投影误差阈值\n");
        sb.append("ransac.reproj.threshold=").append(RANSAC_REPROJ_THRESHOLD).append("\n");
        sb.append("# RANSAC 最大迭代次数\n");
        sb.append("ransac.max.iters=").append(RANSAC_MAX_ITERS).append("\n");
        sb.append("# RANSAC 置信度\n");
        sb.append("ransac.confidence=").append(RANSAC_CONFIDENCE).append("\n");
        sb.append("# 启用分块的地图像素阈值\n");
        sb.append("sift.large.map.threshold=").append(SIFT_LARGE_MAP_THRESHOLD).append("\n");
        sb.append("# SIFT 匹配等待超时（毫秒）\n");
        sb.append("match.timeout.ms=").append(MATCH_TIMEOUT_MS).append("\n");
        sb.append("# SIFT 匹配总开关\n");
        sb.append("sift.matching.enabled=").append(SIFT_MATCHING_ENABLED).append("\n");
        sb.append("# 训练瓦片基础尺寸（像素）\n");
        sb.append("sift.tile.size=").append(SIFT_TILE_SIZE).append("\n");
        sb.append("# 训练瓦片重叠宽度（像素）\n");
        sb.append("sift.tile.overlap=").append(SIFT_TILE_OVERLAP).append("\n");
        sb.append("# 重叠区域特征去重距离（像素）\n");
        sb.append("sift.dedup.distance=").append(SIFT_DEDUP_DISTANCE).append("\n");

        // --- 亮度路由 ---
        sb.append("# 洞穴→大陆切换阈值\n");
        sb.append("routing.cave.to.ow=").append(ROUTING_CAVE_TO_OW).append("\n");
        sb.append("# 大陆→洞穴切换阈值\n");
        sb.append("routing.ow.to.cave=").append(ROUTING_OW_TO_CAVE).append("\n");
        sb.append("# Sigmoid LUT 中点\n");
        sb.append("routing.sigmoid.midpoint=").append(DARK_SIGMOID_MIDPOINT).append("\n");
        sb.append("# Sigmoid LUT 陡峭度\n");
        sb.append("routing.sigmoid.steepness=").append(DARK_SIGMOID_STEEPNESS).append("\n");
        sb.append("# 暗像素采样步长\n");
        sb.append("routing.dark.stride=").append(DARK_STRIDE).append("\n");
        sb.append("# Sigmoid 输出暗阈值\n");
        sb.append("routing.sigmoid.threshold=").append(DARK_SIGMOID_THRESHOLD).append("\n");
        sb.append("# 灰度下限裁剪\n");
        sb.append("routing.trim.low=").append(DARK_TRIM_LOW).append("\n");
        sb.append("# 灰度上限裁剪\n");
        sb.append("routing.trim.high=").append(DARK_TRIM_HIGH).append("\n");
        sb.append("# is_dark_minimap 暗像素比例阈值\n");
        sb.append("routing.dark.ratio=").append(DARK_RATIO_THRESHOLD).append("\n");

        // --- MiniMap 检测 ---
        sb.append("# HoughCircles 缩小宽度\n");
        sb.append("mm.small.width=").append(MM_SMALL_WIDTH).append("\n");
        sb.append("# 圆验证最小黑像素比例\n");
        sb.append("mm.black.ratio=").append(MM_BLACK_RATIO_THRESHOLD).append("\n");
        sb.append("# 圆心最大偏移比例\n");
        sb.append("mm.center.offset=").append(MM_CENTER_OFFSET_RATIO).append("\n");
        sb.append("# HoughCircles dp\n");
        sb.append("mm.hough.dp=").append(MM_HOUGH_DP).append("\n");
        sb.append("# HoughCircles param1\n");
        sb.append("mm.hough.param1=").append(MM_HOUGH_PARAM1).append("\n");
        sb.append("# HoughCircles param2\n");
        sb.append("mm.hough.param2=").append(MM_HOUGH_PARAM2).append("\n");
        sb.append("# HoughCircles 最小半径比例\n");
        sb.append("mm.hough.min.radius=").append(MM_HOUGH_MIN_RADIUS_RATIO).append("\n");
        sb.append("# HoughCircles 最大半径比例\n");
        sb.append("mm.hough.max.radius=").append(MM_HOUGH_MAX_RADIUS_RATIO).append("\n");
        sb.append("# 圆边界采样点数\n");
        sb.append("mm.circle.samples=").append(MM_CIRCLE_SAMPLE_COUNT).append("\n");
        sb.append("# 圆边界采样步长（度）\n");
        sb.append("mm.circle.step=").append(MM_CIRCLE_STEP_DEG).append("\n");
        sb.append("# 圆边界黑色阈值\n");
        sb.append("mm.circle.black=").append(MM_CIRCLE_BLACK_THRESHOLD).append("\n");

        // --- 箭头检测 ---
        sb.append("# 箭头 HSV 色相下限\n");
        sb.append("arrow.hue.low=").append(ARROW_HUE_LOW).append("\n");
        sb.append("# 箭头 HSV 色相上限\n");
        sb.append("arrow.hue.high=").append(ARROW_HUE_HIGH).append("\n");
        sb.append("# 箭头 HSV 饱和度下限\n");
        sb.append("arrow.sat.low=").append(ARROW_SAT_LOW).append("\n");
        sb.append("# 箭头 HSV 饱和度上限\n");
        sb.append("arrow.sat.high=").append(ARROW_SAT_HIGH).append("\n");
        sb.append("# 箭头 HSV 明度下限\n");
        sb.append("arrow.val.low=").append(ARROW_VAL_LOW).append("\n");
        sb.append("# 箭头 HSV 明度上限\n");
        sb.append("arrow.val.high=").append(ARROW_VAL_HIGH).append("\n");
        sb.append("# 箭头最小轮廓面积\n");
        sb.append("arrow.min.area=").append(ARROW_MIN_CONTOUR_AREA).append("\n");
        sb.append("# 箭头检测最小半径\n");
        sb.append("arrow.min.radius=").append(ARROW_MIN_RADIUS).append("\n");
        sb.append("# 箭头分析裁剪尺寸\n");
        sb.append("arrow.crop.size=").append(ARROW_CROP_SIZE).append("\n");

        // --- ROI 裁剪 ---
        sb.append("# ROI 裁剪半径倍数\n");
        sb.append("crop.margin=").append(CROP_MARGIN).append("\n");
        sb.append("# ROI 裁剪最小尺寸\n");
        sb.append("crop.min.dim=").append(CROP_MIN_DIM).append("\n");
        sb.append("# ROI 裁剪最大面积比例\n");
        sb.append("crop.max.area=").append(CROP_MAX_AREA_RATIO).append("\n");

        // --- 训练/特征 ---
        sb.append("# PCA 目标维度\n");
        sb.append("pca.target.dim=").append(PCA_TARGET_DIM).append("\n");
        sb.append("# 内容裁剪亮度阈值\n");
        sb.append("content.rect.threshold=").append(CONTENT_RECT_THRESHOLD).append("\n");
        sb.append("# 内容裁剪采样步长\n");
        sb.append("content.rect.stride=").append(CONTENT_RECT_STRIDE).append("\n\n");
    }
}
