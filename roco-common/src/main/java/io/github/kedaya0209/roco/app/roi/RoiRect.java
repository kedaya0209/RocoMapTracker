package io.github.kedaya0209.roco.app.roi;

import net.jcip.annotations.ThreadSafe;

/**
 * ROI 万分数坐标值对象（0~10000）。
 * <p>
 * 不可变 record，用于统一 ROI 坐标传递。{@code h == 0} 表示自动正方形模式。
 */
@ThreadSafe
public record RoiRect(int x, int y, int w, int h) {

    public RoiRect {
        if (x < 0 || x > 10000) throw new IllegalArgumentException("x out of range: " + x);
        if (y < 0 || y > 10000) throw new IllegalArgumentException("y out of range: " + y);
        if (w < 0 || w > 10000) throw new IllegalArgumentException("w out of range: " + w);
        if (h < 0 || h > 10000) throw new IllegalArgumentException("h out of range: " + h);
    }

    /** 万分数 → 像素坐标 */
    public RoiRect toPixels(int frameW, int frameH) {
        int px = Math.round((float) x * frameW / 10000);
        int py = Math.round((float) y * frameH / 10000);
        int pw = Math.round((float) w * frameW / 10000);
        int ph = h == 0 ? pw : Math.round((float) h * frameH / 10000);
        return new RoiRect(px, py, pw, ph);
    }

    /** 像素坐标 → 万分数 */
    public static RoiRect fromPixels(double px, double py, double pw, double ph,
                                     int frameW, int frameH) {
        int rx = (int) Math.round(px / frameW * 10000);
        int ry = (int) Math.round(py / frameH * 10000);
        int rw = (int) Math.round(pw / frameW * 10000);
        int rh = (int) Math.round(ph / frameH * 10000);
        rx = Math.clamp(rx, 0, 10000);
        ry = Math.clamp(ry, 0, 10000);
        rw = Math.clamp(rw, 1, 10000 - rx);
        rh = Math.clamp(rh, 1, 10000 - ry);
        return new RoiRect(rx, ry, rw, rh);
    }

    /** h == 0 表示自动正方形（宽度 = 高度） */
    public boolean isAutoSquare() {
        return h == 0;
    }
}
