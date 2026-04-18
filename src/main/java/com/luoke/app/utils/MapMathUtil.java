package com.luoke.app.utils;

import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;

/**
 * 洛克王国地图数学工具类
 */
public class MapMathUtil {

    /**
     * 计算四个坐标点的几何中心 (Centroid)
     *
     * @param corners double[4][2] 数组
     * @return [centerX, centerY]
     */
    public static double[] getCentroid(double[][] corners) {
        if (corners == null || corners.length < 4) return new double[]{0, 0};

        double sumX = 0;
        double sumY = 0;
        for (int i = 0; i < 4; i++) {
            sumX += corners[i][0];
            sumY += corners[i][1];
        }
        return new double[]{sumX / 4.0, sumY / 4.0};
    }

    /**
     * 计算能够包含所有点的最小包围盒中心
     * (有时比几何中心更适合作为相机焦点)
     */
    public static double[] getBoundingBoxCenter(double[][] corners) {
        if (corners == null || corners.length == 0) return new double[]{0, 0};

        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

        for (double[] point : corners) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }
        return new double[]{(minX + maxX) / 2.0, (minY + maxY) / 2.0};
    }

    /**
     * 计算从追踪模式切换回手动模式时需要的位移补偿 (panX, panY)
     * * @param canvas    当前的画布
     *
     * @param mapImage  地图图片
     * @param zoomLevel 当前缩放级别
     * @param targetX   追踪目标的地图原始X (如玩家X或几何中心X)
     * @param targetY   追踪目标的地图原始Y
     * @return double[2] {newPanX, newPanY}
     */
    public static double[] calculatePanCompensation(Canvas canvas, Image mapImage, double zoomLevel, double targetX, double targetY) {
        double viewW = canvas.getWidth();
        double viewH = canvas.getHeight();
        double imgW = mapImage.getWidth();
        double imgH = mapImage.getHeight();

        // 1. 计算当前缩放比例
        double baseScale = Math.min(viewW / imgW, viewH / imgH);
        double finalScale = baseScale * zoomLevel;

        // 2. 追踪模式下的总偏移 (即目标点正好在屏幕中心)
        double trackTotalX = (viewW / 2.0) - (targetX * finalScale);
        double trackTotalY = (viewH / 2.0) - (targetY * finalScale);

        // 3. 普通模式下的基础居中偏移
        double centeredX = (viewW - imgW * finalScale) / 2.0;
        double centeredY = (viewH - imgH * finalScale) / 2.0;

        // 4. 返回差值作为手动模式的 panX/panY
        return new double[]{trackTotalX - centeredX, trackTotalY - centeredY};
    }

    /**
     * 判断点是否在多边形内 (射线法)
     * 用于判断玩家是否点击了某个特定的地图区域
     */
    public static boolean isPointInPolygon(double px, double py, double[][] corners) {
        boolean inside = false;
        for (int i = 0, j = corners.length - 1; i < corners.length; j = i++) {
            if (((corners[i][1] > py) != (corners[j][1] > py)) &&
                    (px < (corners[j][0] - corners[i][0]) * (py - corners[i][1]) / (corners[j][1] - corners[i][1]) + corners[i][0])) {
                inside = !inside;
            }
        }
        return inside;
    }
}