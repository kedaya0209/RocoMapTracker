package com.luoke.app.utils;

import com.luoke.app.context.MapManager;

public class CoordinateTransformer {

    /**
     * 将全图原始坐标转换为裁剪后的本地地图坐标
     */
    public static double[] transform(double rawX, double rawY) {
        MapManager mm = MapManager.getInstance();
        return new double[]{
                rawX - mm.getTrimOffsetX(),
                rawY - mm.getTrimOffsetY()
        };
    }

    /**
     * 平滑更新坐标：支持传送检测与平滑插值
     *
     * @param lerp 因子 (0.0~1.0)，建议 0.2-0.5 消除抖动，0.6-0.8 响应快
     */
    public static void updatePositionSmoothly(double rawX, double rawY, double lerp) {
        double[] local = transform(rawX, rawY);
        MapManager mm = MapManager.getInstance();

        double currentX = mm.getPlayerX();
        double currentY = mm.getPlayerY();

        // 1. 首次定位检查
        if (currentX == 0 && currentY == 0) {
            mm.setPlayerX(local[0]);
            mm.setPlayerY(local[1]);
            return;
        }

        // 2. 传送/突变检测
        // 计算当前点与目标点的像素距离
        double dist = Math.sqrt(Math.pow(local[0] - currentX, 2) + Math.pow(local[1] - currentY, 2));

        // 判定阈值：如果位移超过 150 像素（可根据大图尺寸调整），判定为传送
        // 传送时不使用 lerp 慢慢滑过去，而是直接“瞬间移动”
        double teleportThreshold = 150.0;

        if (dist > teleportThreshold) {
            mm.setPlayerX(local[0]);
            mm.setPlayerY(local[1]);
        } else {
            // 3. 常规移动：线性插值 (Lerp)
            double targetX = currentX + (local[0] - currentX) * lerp;
            double targetY = currentY + (local[1] - currentY) * lerp;

            mm.setPlayerX(targetX);
            mm.setPlayerY(targetY);
        }
    }
}