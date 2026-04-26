package com.luoke.app.utils;

import com.luoke.app.context.MapContext;

/**
 * 坐标转换器，处理地图坐标的各种转换和更新
 *
 * <p>该工具类提供地图坐标转换和平滑更新功能，包括：
 * <ul>
 *   <li>全图坐标到本地坐标的转换</li>
 *   <li>玩家位置的平滑更新</li>
 *   <li>传送检测和处理</li>
 *   <li>插值算法实现</li>
 * </ul>
 *
 * <p>特别关注：
 * <ul>
 *   <li>坐标系统的标准化处理</li>
 *   <li>玩家移动的平滑过渡</li>
 *   <li>传送事件的智能检测</li>
 *   <li>地图上下文的生命周期管理</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
public class CoordinateTransformer {

    /**
     * 将全图原始坐标转换为裁剪后的本地地图坐标
     *
     * <p>该方法用于坐标系统的标准化处理。
     * 当前实现为简单映射，可根据需要扩展为更复杂的转换逻辑。
     *
     * <p>应用场景：
     * <ul>
     *   <li>大地图裁剪后的坐标映射</li>
     *   <li>不同地图坐标系之间的转换</li>
     *   <li>坐标归一化和标准化</li>
     * </ul>
     *
     * <p>注意：此方法依赖MapContext单例，确保在调用前已初始化。
     *
     * @param rawX 原始X坐标（全图坐标系）
     * @param rawY 原始Y坐标（全图坐标系）
     * @return 本地坐标数组 [localX, localY]
     */
    public static double[] transform(double rawX, double rawY) {
        MapContext mm = MapContext.getInstance();
        return new double[]{
                rawX,
                rawY
        };
    }

    /**
     * 平滑更新玩家位置：支持传送检测与平滑插值
     *
     * <p>该方法实现了智能的位置更新算法，能够：
     * <ul>
     *   <li>检测传送事件（距离突变）</li>
     *   <li>使用线性插值实现平滑移动</li>
     *   <li>处理首次定位的特殊情况</li>
     *   <li>减少位置抖动和视觉闪烁</li>
     * </ul>
     *
     * <p>算法原理：
     * <ul>
     *   <li>计算当前位置与目标位置的欧氏距离</li>
     *   <li>如果距离超过阈值（150像素），判定为传送，直接跳跃</li>
     *   <li>否则使用线性插值实现平滑过渡</li>
     *   <li>插值因子控制平滑程度：越小越平滑但响应越慢</li>
     * </ul>
     *
     * <p>参数说明：
     * <ul>
     *   <li>lerp = 0.2-0.5：平滑移动，适合减少抖动</li>
     *   <li>lerp = 0.6-0.8：快速响应，适合实时追踪</li>
     *   <li>传送阈值：150像素，可根据地图尺寸调整</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>玩家位置的实时更新</li>
     *   <li>地图追踪的平滑过渡</li>
     *   <li>传送事件的自动检测</li>
     * </ul>
     *
     * @param rawX 原始X坐标
     * @param rawY 原始Y坐标
     * @param lerp 插值因子 (0.0~1.0)，控制平滑程度，建议 0.2-0.5 消除抖动，0.6-0.8 响应快
     */
    public static void updatePositionSmoothly(double rawX, double rawY, double lerp) {
        // 转换为本地坐标
        double[] local = transform(rawX, rawY);
        MapContext mm = MapContext.getInstance();

        // 获取当前玩家位置
        double currentX = mm.getPlayerX();
        double currentY = mm.getPlayerY();

        // 1. 首次定位检查
        // 如果当前位置为原点，说明是首次定位，直接设置位置
        if (currentX == 0 && currentY == 0) {
            mm.setPlayerX(local[0]);
            mm.setPlayerY(local[1]);
            return;
        }

        // 2. 传送/突变检测
        // 计算当前点与目标点的像素距离（欧氏距离）
        double dist = Math.sqrt(Math.pow(local[0] - currentX, 2) + Math.pow(local[1] - currentY, 2));

        // 判定阈值：如果位移超过 150 像素（可根据大图尺寸调整），判定为传送
        // 传送时不使用 lerp 慢慢滑过去，而是直接”瞬间移动”
        double teleportThreshold = 150.0;

        if (dist > teleportThreshold) {
            // 传送事件：直接设置新位置
            mm.setPlayerX(local[0]);
            mm.setPlayerY(local[1]);
        } else {
            // 3. 常规移动：线性插值 (Lerp)
            // 线性插值公式：current + (target - current) * lerp
            // 当lerp=1.0时，直接跳跃；当lerp=0.5时，每次移动一半距离
            double targetX = currentX + (local[0] - currentX) * lerp;
            double targetY = currentY + (local[1] - currentY) * lerp;

            mm.setPlayerX(targetX);
            mm.setPlayerY(targetY);
        }
    }
}