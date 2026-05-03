package com.luoke.app.utils;

import com.luoke.app.context.MapContext;

public class CoordinateTransformer {

    public static double[] transform(double rawX, double rawY) {
        MapContext mm = MapContext.getInstance();
        return new double[]{
                rawX,
                rawY
        };
    }

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