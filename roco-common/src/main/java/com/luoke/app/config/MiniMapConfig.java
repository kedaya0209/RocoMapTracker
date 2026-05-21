package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * 小地图检测配置持久化 
 */
@NotThreadSafe
public final class MiniMapConfig {

    // ============================================================
    // 小地图检测参数
    // ============================================================
    /**
     * 缩小检测宽度（像素）
     */
    public static int MM_SMALL_WIDTH = 120;
    /**
     * 黑边比例阈值
     */
    public static double MM_BLACK_RATIO_THRESHOLD = 0.15;
    /**
     * 圆心偏移比例阈值
     */
    public static double MM_CENTER_OFFSET_RATIO = 0.2;
    /**
     * 中值滤波核大小
     */
    public static int MM_MEDIAN_BLUR_KERNEL = 5;
    /**
     * HoughCircles dp
     */
    public static double MM_HOUGH_DP = 1.2;
    /**
     * HoughCircles param1（Canny 高阈值）
     */
    public static double MM_HOUGH_PARAM1 = 50;
    /**
     * HoughCircles param2（累加器阈值）
     */
    public static double MM_HOUGH_PARAM2 = 35;
    /**
     * 边缘黑边像素灰度阈值
     */
    public static int MM_BLACK_PIXEL_THRESHOLD = 150;
    /**
     * 边缘采样点数
     */
    public static int MM_EDGE_SAMPLE_COUNT = 120;
    /**
     * 边缘采样角度步长（度）
     */
    public static double MM_EDGE_SAMPLE_STEP = 3.0;

    private MiniMapConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        MM_SMALL_WIDTH = ConfigHelper.getInt(prop, "mm.small.width", MM_SMALL_WIDTH);
        MM_BLACK_RATIO_THRESHOLD = ConfigHelper.getDouble(prop, "mm.black.ratio.threshold", MM_BLACK_RATIO_THRESHOLD);
        MM_CENTER_OFFSET_RATIO = ConfigHelper.getDouble(prop, "mm.center.offset.ratio", MM_CENTER_OFFSET_RATIO);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 缩小检测宽度（像素）\n");
        sb.append("mm.small.width=").append(MM_SMALL_WIDTH).append("\n");
        sb.append("# 黑边比例阈值\n");
        sb.append("mm.black.ratio.threshold=").append(MM_BLACK_RATIO_THRESHOLD).append("\n");
        sb.append("# 圆心偏移比例阈值\n");
        sb.append("mm.center.offset.ratio=").append(MM_CENTER_OFFSET_RATIO).append("\n\n");
    }
}
