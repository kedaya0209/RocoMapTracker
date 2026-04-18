package com.luoke.macher.map;

import java.awt.image.BufferedImage;

public interface MapMatcher {
    /**
     * 初始化匹配器（通常用于预加载大图特征）
     *
     * @param largeMapPath 大图路径
     */
    void init(String largeMapPath);

    /**
     * 执行匹配
     *
     * @param smallImgPath 待定位的小图路径
     * @return 匹配到的 4 个角点坐标，失败返回 null
     */
    double[][] run(String smallImgPath);

    // 支持字节数组（通常是 BGRA 格式）
    double[][] run(byte[] imageBytes, int width, int height);

    // 支持 BufferedImage
    double[][] run(BufferedImage image);

    /**
     * 释放持久化资源（如缓存的大图特征）
     */
    void destroy();
}