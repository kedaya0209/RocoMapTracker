package com.luoke.app.ui.util;

import net.jcip.annotations.ThreadSafe;

/**
 * 坐标变换工具 — 导航模式下的旋转补偿计算。
 *
 * <p>数学公式（视口中心为轴心）：
 * <pre>
 * Forward (world→screen):
 *   sx = worldX * scale + ox
 *   sy = worldY * scale + oy
 *   dx = sx - pivotX, dy = sy - pivotY
 *   rx = dx * cos - dy * sin + pivotX
 *   ry = dx * sin + dy * cos + pivotY
 *
 * Inverse (screen→world):
 *   dx = screenX - pivotX, dy = screenY - pivotY
 *   cos = cos(+navAngle), sin = sin(+navAngle)
 *   ux = dx * cos - dy * sin + pivotX
 *   uy = dx * sin + dy * cos + pivotY
 *   worldX = (ux - ox) / scale
 *   worldY = (uy - oy) / scale
 * </pre>
 */
@ThreadSafe
public final class CoordinateUtil {

    private CoordinateUtil() {
        throw new AssertionError("禁止实例化工具类");
    }

    /**
     * 世界坐标 → 屏幕坐标（含导航模式旋转补偿），结果写入 out[0]=x, out[1]=y。
     */
    public static void worldToScreenInto(double[] out, double worldX, double worldY,
                                          double ox, double oy, double scale,
                                          double navAngleDeg, double pivotX, double pivotY) {
        double sx = worldX * scale + ox;
        double sy = worldY * scale + oy;
        if (navAngleDeg == 0) {
            out[0] = sx;
            out[1] = sy;
            return;
        }
        double rad = Math.toRadians(-navAngleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double dx = sx - pivotX;
        double dy = sy - pivotY;
        out[0] = dx * cos - dy * sin + pivotX;
        out[1] = dx * sin + dy * cos + pivotY;
    }

    /**
     * 世界坐标 → 屏幕坐标（含导航模式旋转补偿）。
     *
     * @deprecated 热路径中请使用 {@link #worldToScreenInto} 避免数组分配
     */
    @Deprecated
    public static double[] worldToScreen(double worldX, double worldY,
                                          double ox, double oy, double scale,
                                          double navAngleDeg, double pivotX, double pivotY) {
        double[] out = new double[2];
        worldToScreenInto(out, worldX, worldY, ox, oy, scale, navAngleDeg, pivotX, pivotY);
        return out;
    }

    /**
     * 屏幕坐标 → 世界坐标（含导航模式逆旋转补偿），结果写入 out[0]=x, out[1]=y。
     */
    public static void screenToWorldInto(double[] out, double screenX, double screenY,
                                          double ox, double oy, double scale,
                                          double navAngleDeg, double pivotX, double pivotY) {
        if (navAngleDeg == 0) {
            out[0] = (screenX - ox) / scale;
            out[1] = (screenY - oy) / scale;
            return;
        }
        double rad = Math.toRadians(navAngleDeg);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double dx = screenX - pivotX;
        double dy = screenY - pivotY;
        double ux = dx * cos - dy * sin + pivotX;
        double uy = dx * sin + dy * cos + pivotY;
        out[0] = (ux - ox) / scale;
        out[1] = (uy - oy) / scale;
    }

    /**
     * 屏幕坐标 → 世界坐标（含导航模式逆旋转补偿）。
     *
     * @deprecated 热路径中请使用 {@link #screenToWorldInto} 避免数组分配
     */
    @Deprecated
    public static double[] screenToWorld(double screenX, double screenY,
                                          double ox, double oy, double scale,
                                          double navAngleDeg, double pivotX, double pivotY) {
        double[] out = new double[2];
        screenToWorldInto(out, screenX, screenY, ox, oy, scale, navAngleDeg, pivotX, pivotY);
        return out;
    }
}
