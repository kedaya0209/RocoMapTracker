package com.luoke.app.utils;

import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;

/**
 * 洛克王国地图数学工具类
 *
 * <p>该工具类提供地图相关的数学计算功能，包括：
 * <ul>
 *   <li>几何中心计算</li>
 *   <li>包围盒计算</li>
 *   <li>坐标转换和平移补偿</li>
 *   <li>多边形包含判断</li>
 * </ul>
 *
 * <p>特别关注地图追踪和相机焦点相关的计算，为地图显示提供数学支持。
 * 所有计算都是线程安全的，不依赖共享状态。
 *
 * @author 可达鸭
 * @version 1.0
 */
public class MapMathUtil {

    /**
     * 计算四个坐标点的几何中心 (Centroid)
     *
     * <p>几何中心是所有坐标点的平均值，适用于计算地图标注、
     * 区域中心等场景。相比几何中心，平均值计算更简单快速。
     *
     * <p>应用场景：
     * <ul>
     *   <li>地图标注的居中定位</li>
     *   <li>四个角点定义的区域中心</li>
     *   <li>相机焦点的粗略定位</li>
     * </ul>
     *
     * @param corners double[4][2] 数组，每个元素表示一个点 {x, y}
     * @return 包含几何中心坐标的数组 [centerX, centerY]，如果输入无效则返回 [0, 0]
     */
    public static double[] getCentroid(double[][] corners) {
        // 处理无效输入
        if (corners == null || corners.length < 4) return new double[]{0, 0};

        // 累加所有点的X和Y坐标
        double sumX = 0;
        double sumY = 0;
        for (int i = 0; i < 4; i++) {
            sumX += corners[i][0];
            sumY += corners[i][1];
        }

        // 返回平均值作为几何中心
        return new double[]{sumX / 4.0, sumY / 4.0};
    }

    /**
     * 计算能够包含所有点的最小包围盒中心
     *
     * <p>包围盒中心是最小矩形边界的中心点，有时比几何中心更适合作为相机焦点。
     * 特别适合不规则形状的标注或区域，可以更准确地反映视觉中心。
     *
     * <p>应用场景：
     * <ul>
     *   <li>相机焦点精确定位</li>
     *   <li>不规则形状的视觉中心计算</li>
     *   <li>地图区域的边界检测</li>
     * </ul>
     *
     * @param corners 坐标点数组，每个元素表示一个点 {x, y}
     * @return 包含包围盒中心坐标的数组 [centerX, centerY]，如果输入无效则返回 [0, 0]
     */
    public static double[] getBoundingBoxCenter(double[][] corners) {
        // 处理无效输入
        if (corners == null || corners.length == 0) return new double[]{0, 0};

        // 初始化边界值为极值，确保第一次比较会更新
        double minX = Double.MAX_VALUE, maxX = Double.MIN_VALUE;
        double minY = Double.MAX_VALUE, maxY = Double.MIN_VALUE;

        // 遍历所有点，找到最小和最大X、Y坐标
        for (double[] point : corners) {
            minX = Math.min(minX, point[0]);
            maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]);
            maxY = Math.max(maxY, point[1]);
        }

        // 返回包围盒的中心坐标
        return new double[]{(minX + maxX) / 2.0, (minY + maxY) / 2.0};
    }

    /**
     * 计算从追踪模式切换回手动模式时需要的位移补偿 (panX, panY)
     *
     * <p>该方法用于在追踪模式和手动模式之间切换时保持视觉一致性。
     * 通过计算当前目标位置和默认居中位置之间的差异，生成补偿值。
     *
     * <p>计算逻辑：
     * <ul>
     *   <li>计算当前的缩放比例（基础缩放 × 用户缩放）</li>
     *   <li>计算追踪模式下目标点在屏幕中心的偏移量</li>
     *   <li>计算手动模式下的默认居中偏移量</li>
     *   <li>返回两者之间的差值作为补偿值</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>追踪模式与手动模式的无缝切换</li>
     *   <li>保持相机焦点的视觉连续性</li>
     *   <li>用户交互体验优化</li>
     * </ul>
     *
     * @param canvas 当前的画布，提供视图尺寸
     * @param mapImage 地图图片，提供原始图像尺寸
     * @param zoomLevel 当前缩放级别，如1.0表示100%，2.0表示200%
     * @param targetX 追踪目标的地图原始X坐标（如玩家X或几何中心X）
     * @param targetY 追踪目标的地图原始Y坐标
     * @return 包含补偿值的数组 {newPanX, newPanY}，用于手动模式的平移参数
     */
    public static double[] calculatePanCompensation(Canvas canvas, Image mapImage, double zoomLevel, double targetX, double targetY) {
        // 获取画布和图像的尺寸
        double viewW = canvas.getWidth();
        double viewH = canvas.getHeight();
        double imgW = mapImage.getWidth();
        double imgH = mapImage.getHeight();

        // 1. 计算当前缩放比例
        // 基础缩放：使图像适应画布（保持宽高比）
        double baseScale = Math.min(viewW / imgW, viewH / imgH);
        // 最终缩放：基础缩放 × 用户缩放级别
        double finalScale = baseScale * zoomLevel;

        // 2. 追踪模式下的总偏移（目标点正好在屏幕中心）
        // 计算使目标点位于屏幕中心所需的偏移量
        double trackTotalX = (viewW / 2.0) - (targetX * finalScale);
        double trackTotalY = (viewH / 2.0) - (targetY * finalScale);

        // 3. 普通模式下的基础居中偏移
        // 计算使图像在画布中居中所需的偏移量
        double centeredX = (viewW - imgW * finalScale) / 2.0;
        double centeredY = (viewH - imgH * finalScale) / 2.0;

        // 4. 返回差值作为手动模式的 panX/panY
        // 这样切换模式时，目标点仍然保持在屏幕上相同的位置
        return new double[]{trackTotalX - centeredX, trackTotalY - centeredY};
    }

    /**
     * 判断点是否在多边形内（射线法）
     *
     * <p>该方法使用射线投射算法判断一个点是否位于多边形内部。
     * 通过从测试点向任意方向发射射线，计算与多边形边界的交点数量。
     *
     * <p>算法原理：
     * <ul>
     *   <li>从测试点向右发射水平射线</li>
     *   <li>计算射线与每条多边形边的交点</li>
     *   <li>交点数量为奇数表示在内部，偶数表示在外部</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>判断玩家是否点击了某个特定的地图区域</li>
     *   <li>检测坐标是否在定义的区域内</li>
     *   <li>地图区域的点击交互处理</li>
     * </ul>
     *
     * @param px 测试点的X坐标
     * @param py 测试点的Y坐标
     * @param corners 多边形的顶点坐标数组，按顺时针或逆时针顺序排列
     * @return 如果点在多边形内部返回true，否则返回false
     */
    public static boolean isPointInPolygon(double px, double py, double[][] corners) {
        boolean inside = false;

        // 遍历多边形的所有边
        for (int i = 0, j = corners.length - 1; i < corners.length; j = i++) {
            // 检查射线是否与当前边相交
            // 条件1：边的两个端点必须在射线的上下两侧
            // 条件2：交点的X坐标必须大于测试点的X坐标
            if (((corners[i][1] > py) != (corners[j][1] > py)) &&
                    (px < (corners[j][0] - corners[i][0]) * (py - corners[i][1]) / (corners[j][1] - corners[i][1]) + corners[i][0])) {
                // 切换内部/外部状态
                inside = !inside;
            }
        }

        return inside;
    }
}