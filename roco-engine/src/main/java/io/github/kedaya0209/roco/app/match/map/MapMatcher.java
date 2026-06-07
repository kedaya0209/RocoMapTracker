package io.github.kedaya0209.roco.app.match.map;

import net.jcip.annotations.ThreadSafe;

/**
 * 地图匹配器接口
 * 定义地图匹配的核心操作，用于在大型地图中定位小图的位置
 */
@ThreadSafe
public interface MapMatcher {

    /**
     * 初始化匹配器
     *
     * @param largeMapPath 大图的资源路径
     * @return 初始化是否成功
     */
    boolean init(String largeMapPath);

    /**
     * 执行匹配（字节数组版本）
     *
     * @param imageBytes BGRA格式的像素字节数组
     * @param width      图像宽度（像素）
     * @param height     图像高度（像素）
     * @return 匹配到的4个角点坐标数组，失败返回 null
     */
    double[][] match(byte[] imageBytes, int width, int height);

    /**
     * 释放Native资源
     */
    void destroy();
}
