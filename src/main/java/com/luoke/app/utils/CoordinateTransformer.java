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
     * 平滑更新坐标：使用线性插值 (Lerp) 减少红点跳动
     *
     * @param lerp 因子 (0.0~1.0)，建议 0.5-0.8 之间以兼顾平滑和响应速度
     */
    public static void updatePositionSmoothly(double rawX, double rawY, double lerp) {
        double[] local = transform(rawX, rawY);
        MapManager mm = MapManager.getInstance();

        // 插值计算：当前位置 + (目标位置 - 当前位置) * 系数
        double targetX = mm.getPlayerX() + (local[0] - mm.getPlayerX()) * lerp;
        double targetY = mm.getPlayerY() + (local[1] - mm.getPlayerY()) * lerp;

        mm.setPlayerX(targetX);
        mm.setPlayerY(targetY);
    }
}