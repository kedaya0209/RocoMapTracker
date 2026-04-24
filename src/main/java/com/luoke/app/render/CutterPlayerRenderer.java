package com.luoke.app.render;

import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.context.MapContext;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;

/**
 * 从整张小地图 抠 中心圆形（比例 0.15）
 * 纯字节、无Mat、双线程安全
 */
@Slf4j
public class CutterPlayerRenderer {
    private static final int CHANNELS = 4; // BGRA
    private static final double CIRCLE_RATIO = 0.18; // 圆形占画面短边比例

    // 渲染用的圆形箭头图
    private volatile Image currentArrowImage;

    // ===================== 单例 =====================
    private CutterPlayerRenderer() {
    }

    public static CutterPlayerRenderer getInstance() {
        return Holder.INSTANCE;
    }

    // ==============================================
    // 工具方法（保留你需要的）
    // ==============================================
    public static BufferedImage toBufferedImage(byte[] bytes, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        WritableRaster raster = image.getRaster();
        DataBufferByte dataBuffer = (DataBufferByte) raster.getDataBuffer();
        byte[] targetBytes = dataBuffer.getData();
        System.arraycopy(bytes, 0, targetBytes, 0, Math.min(bytes.length, targetBytes.length));
        return image;
    }

    public static BufferedImage toBufferedImage(CaptureFrameRecord frame) {
        if (frame == null || frame.bytes() == null) return null;
        return toBufferedImage(frame.bytes(), frame.width(), frame.height());
    }

    // ==============================================
    // 【生产线程】从整张小地图 抠中心圆形
    // ==============================================
    public void updateArrow(CaptureFrameRecord frame) {
        if (frame == null || frame.bytes() == null) return;

        int w = frame.width();
        int h = frame.height();
        byte[] srcBytes = frame.bytes();

        // 圆心 = 画面正中心
        int cx = w / 2;
        int cy = h / 2;

        // 圆形直径 = 画面短边 * 0.15
        int diameter = (int) (Math.min(w, h) * CIRCLE_RATIO);
        if (diameter < 4) diameter = 4;
        int radius = diameter / 2;

        // 输出图像尺寸 = 圆形直径
        int outSize = diameter;

        // ==============================================
        // 逐像素复制：只保留圆内，圆外透明
        // ==============================================
        byte[] outBytes = new byte[outSize * outSize * CHANNELS];

        for (int dy = 0; dy < outSize; dy++) {
            // 源图 Y
            int srcY = cy - radius + dy;
            if (srcY < 0 || srcY >= h) continue;

            for (int dx = 0; dx < outSize; dx++) {
                // 源图 X
                int srcX = cx - radius + dx;
                if (srcX < 0 || srcX >= w) continue;

                // 到圆心距离
                int distX = srcX - cx;
                int distY = srcY - cy;
                boolean inCircle = (distX * distX + distY * distY) <= radius * radius;

                int srcPos = (srcY * w + srcX) * CHANNELS;
                int dstPos = (dy * outSize + dx) * CHANNELS;

                if (inCircle) {
                    // 圆内：复制 BGRA
                    System.arraycopy(srcBytes, srcPos, outBytes, dstPos, CHANNELS);
                } else {
                    // 圆外：全透明
                    outBytes[dstPos] = 0;    // B
                    outBytes[dstPos + 1] = 0;    // G
                    outBytes[dstPos + 2] = 0;    // R
                    outBytes[dstPos + 3] = 0;    // A
                }
            }
        }

        // 生成最终圆形图
        WritableImage img = new WritableImage(outSize, outSize);
        PixelWriter writer = img.getPixelWriter();
        writer.setPixels(0, 0, outSize, outSize,
                javafx.scene.image.PixelFormat.getByteBgraInstance(),
                outBytes, 0, outSize * CHANNELS);

        currentArrowImage = img;
    }

    // ==============================================
    // 【渲染线程】绘制中心圆形箭头
    // ==============================================
    public void draw(GraphicsContext gc) {
        Image img = currentArrowImage;
        if (img == null) return;

        MapContext mm = MapContext.getInstance();
        if (!mm.isPlayerInitialized()) return;

        double centerX = mm.getPlayerCanvasX();
        double centerY = mm.getPlayerCanvasY();
        double halfW = img.getWidth() / 2;
        double halfH = img.getHeight() / 2;

        gc.save();
        gc.drawImage(img, centerX - halfW, centerY - halfH);
        gc.restore();
    }

    // 释放
    public void release() {
        currentArrowImage = null;
    }

    // ===================== 单例 =====================
    private static class Holder {
        private static final CutterPlayerRenderer INSTANCE = new CutterPlayerRenderer();
    }
}