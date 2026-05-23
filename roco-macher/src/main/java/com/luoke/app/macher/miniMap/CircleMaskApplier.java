package com.luoke.app.macher.miniMap;

import net.jcip.annotations.ThreadSafe;

import java.util.Arrays;

/**
 * 圆形遮罩工具：将图像中圆形区域外的像素置零。
 */
@ThreadSafe
public final class CircleMaskApplier {

    private CircleMaskApplier() {
    } // 禁止实例化

    /**
     * 对原始图像数据应用圆形遮罩
     *
     * @param data    图像数据（会被原地修改）
     * @param width   图像宽度
     * @param height  图像高度
     * @param centerX 圆心 X 坐标
     * @param centerY 圆心 Y 坐标
     * @param radius  圆半径
     */
    public static void applyMask(byte[] data, int width, int height,
                                 double centerX, double centerY, int radius) {
        double r2 = (double) radius * radius;
        for (int y = 0; y < height; y++) {
            int offset = y * width;
            double dy = y - centerY;
            double dy2 = dy * dy;

            if (dy2 >= r2) {
                Arrays.fill(data, offset, offset + width, (byte) 0);
                continue;
            }

            double dxSpan = Math.sqrt(r2 - dy2);
            int left = (int) Math.ceil(centerX - dxSpan);
            int right = (int) Math.floor(centerX + dxSpan);

            int safeLeft = Math.max(0, left);
            int safeRight = Math.min(width - 1, right);

            if (safeLeft > 0) {
                Arrays.fill(data, offset, offset + safeLeft, (byte) 0);
            }
            if (safeRight < width - 1) {
                Arrays.fill(data, offset + safeRight + 1, offset + width, (byte) 0);
            }
        }
    }
}