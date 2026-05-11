package com.luoke.app.macher.map;

/**
 * 地图匹配器接口
 * 定义地图匹配的核心操作，用于在大型地图中定位小图的位置
 */
public interface MapMatcher {

    /**
     * 初始化匹配器
     * @param largeMapPath 大图的资源路径
     * @return 初始化是否成功
     */
    boolean init(String largeMapPath);

    /**
     * 执行匹配（字节数组版本）
     * @param imageBytes BGRA格式的像素字节数组
     * @param width 图像宽度（像素）
     * @param height 图像高度（像素）
     * @return 匹配到的4个角点坐标数组，失败返回 null
     */
    double[][] match(byte[] imageBytes, int width, int height);

    /**
     * 带 ROI 提示的匹配。
     * hintX/hintY 为预测的参考地图坐标，null 表示无提示（全图搜索）。
     * 实现类可在 hint 非空时仅搜索周边特征点子集以加速匹配。
     */
    default double[][] match(byte[] imageBytes, int width, int height, Double hintX, Double hintY) {
        return match(imageBytes, width, height);
    }

    /**
     * 释放Native资源
     */
    void destroy();
}
