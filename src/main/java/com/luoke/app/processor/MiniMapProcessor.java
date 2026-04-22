package com.luoke.app.processor;

import com.luoke.app.capture.CaptureFrameRecord;
import com.luoke.app.capture.ImageConverter;
import com.luoke.app.capture.WGCCapture;

import java.awt.image.BufferedImage;

public final class MiniMapProcessor {

    private MiniMapProcessor() {
    }

    /**
     * 修改版：提取正方形小地图并封装进 CaptureFrameRecord
     * 这样调用方可以直接获取处理后的 width 和 height
     */
    public static CaptureFrameRecord extractFinalMiniMap(
            CaptureFrameRecord frameRecord,
            double xRatio,
            double yRatio,
            double hRatio
    ) {
        if (frameRecord == null || frameRecord.bytes() == null) return null;

        int fullWidth = frameRecord.width();
        int fullHeight = frameRecord.height();

        // 1. 计算坐标与尺寸
        int h = (int) (fullHeight * hRatio);
        int startX = (int) (fullWidth * xRatio);
        int startY = (int) (fullHeight * yRatio);

        // 2. 自动调整为正方形
        int maxPossibleW = fullWidth - startX;
        int maxPossibleH = fullHeight - startY;
        int squareSize = Math.min(h, Math.min(maxPossibleW, maxPossibleH));

        if (squareSize <= 0) return null;

        return extractCircleMaskMiniMapBytes(frameRecord.bytes(), fullWidth, fullHeight, startX, startY, squareSize, squareSize);
    }

    public static CaptureFrameRecord extractCircleMaskMiniMapBytes(byte[] bytes, int fullWidth, int fullHeight, int x, int y, int width, int height) {
        bytes = extractMiniMapBytes(fullWidth, fullHeight, bytes,
                x, y, width, height);

        // 4. 应用圆形遮罩
        if (bytes != null) {
            bytes = applyCircleMask(bytes, width, height);

            return CaptureFrameRecord.builder()
                    .width(width)
                    .height(height)
                    .bytes(bytes)
                    .build();
        }
        return null;
    }

    /**
     * 核心切割方法
     */
    public static byte[] extractMiniMapBytes(int fullWidth, int fullHeight, byte[] src,
                                             int x, int y, int w, int h) {
        if (src == null || fullWidth <= 0 || fullHeight <= 0) return null;

        int safeX = Math.max(0, Math.min(x, fullWidth - 1));
        int safeY = Math.max(0, Math.min(y, fullHeight - 1));
        int safeW = Math.min(w, fullWidth - safeX);
        int safeH = Math.min(h, fullHeight - safeY);

        // 内存安全检查：BGRA 为 4 字节
        if (src.length < (long) fullWidth * fullHeight * 4) return null;

        byte[] miniBytes = new byte[safeW * safeH * 4];

        for (int row = 0; row < safeH; row++) {
            int srcPos = ((safeY + row) * fullWidth + safeX) * 4;
            int destPos = row * safeW * 4;
            System.arraycopy(src, srcPos, miniBytes, destPos, safeW * 4);
        }
        return miniBytes;
    }

    /**
     * 圆形遮罩
     */
    public static byte[] applyCircleMask(byte[] srcBytes, int width, int height) {
        if (srcBytes == null || srcBytes.length != width * height * 4) return null;

        byte[] dstBytes = new byte[width * height * 4];
        double cx = (width - 1) / 2.0;
        double cy = (height - 1) / 2.0;
        double radius = Math.min(width, height) / 2.0;
        double radiusSq = radius * radius;

        for (int y = 0; y < height; y++) {
            double dy = y - cy;
            double dySq = dy * dy;
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * 4;

                // 复制像素颜色
                dstBytes[idx] = srcBytes[idx];         // B
                dstBytes[idx + 1] = srcBytes[idx + 1]; // G
                dstBytes[idx + 2] = srcBytes[idx + 2]; // R

                double dx = x - cx;
                double distSq = dx * dx + dySq;

                if (distSq <= radiusSq) {
                    dstBytes[idx + 3] = srcBytes[idx + 3]; // A (保持原透明度)
                } else {
                    dstBytes[idx + 3] = 0; // 圆外设为完全透明
                }
            }
        }
        return dstBytes;
    }

    // 辅助方法：快速将 Record 转换为图片
    public static BufferedImage toImage(WGCCapture.Frame frame) {
        if (frame == null) return null;
        return ImageConverter.convertBgraToImage(frame.getPixels(), frame.getWidth(), frame.getHeight());
    }
}