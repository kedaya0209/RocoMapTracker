package com.luoke.app.macher.miniMap;

import com.luoke.app.config.MiniMapConfig;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point3f;
import org.bytedeco.opencv.opencv_imgproc.Vec3fVector;

/**
 * 小地图检测器：从灰度图像中定位小地图的圆心坐标与半径。
 * 内部复用 Mat 缓存，提升性能。
 */
@Slf4j
public class MiniMapDetector implements AutoCloseable {

    private double scale;
    private Mat grayMat;
    private Mat smallGray;
    private byte[] smallGrayData;
    private Mat blurMat;

    /**
     * 检测小地图圆心和半径
     *
     * @param data   原始图像数据（灰度图，8UC1）
     * @param width  原始图像宽度
     * @param height 原始图像高度
     * @return 检测结果
     */
    public DetectionResult detect(byte[] data, int width, int height) {
        initMats(width, height);
        // 复制数据到灰度图
        grayMat.data().put(data);

        // 缩放 + 中值滤波
        opencv_imgproc.resize(grayMat, smallGray, smallGray.size());
        smallGray.data().get(smallGrayData);
        opencv_imgproc.medianBlur(smallGray, blurMat, MiniMapConfig.MM_MEDIAN_BLUR_KERNEL);

        int minSide = Math.min(smallGray.cols(), smallGray.rows());

        // Hough 圆检测
        try (PointerScope scope = new PointerScope()) {
            Vec3fVector circles = new Vec3fVector();
            opencv_imgproc.HoughCircles(blurMat, circles, opencv_imgproc.HOUGH_GRADIENT,
                    MiniMapConfig.MM_HOUGH_DP, minSide * 0.6, MiniMapConfig.MM_HOUGH_PARAM1, MiniMapConfig.MM_HOUGH_PARAM2,
                    (int) (minSide * 0.4), (int) (minSide * 0.55));

            if (circles.empty() || circles.size() == 0) {
                return DetectionResult.failure();
            }

            Point3f c = circles.get(0);
            double detCx = c.get(0);
            double detCy = c.get(1);
            double detR = c.get(2);

            // 边缘黑边比例校验
            int blackCount = 0;
            for (int i = 0; i < MiniMapConfig.MM_EDGE_SAMPLE_COUNT; i++) {
                double theta = Math.toRadians(i * MiniMapConfig.MM_EDGE_SAMPLE_STEP);
                int sx = (int) (detCx + detR * Math.cos(theta));
                int sy = (int) (detCy + detR * Math.sin(theta));
                if (sx >= 0 && sx < MiniMapConfig.MM_SMALL_WIDTH && sy >= 0 && sy < smallGray.rows()) {
                    if ((smallGrayData[sy * MiniMapConfig.MM_SMALL_WIDTH + sx] & 0xFF) < MiniMapConfig.MM_BLACK_PIXEL_THRESHOLD) {
                        blackCount++;
                    }
                }
            }

            double distToCenter = Math.hypot(detCx - MiniMapConfig.MM_SMALL_WIDTH / 2.0, detCy - smallGray.rows() / 2.0);
            double maxDist = minSide * MiniMapConfig.MM_CENTER_OFFSET_RATIO;
            if ((double) blackCount / MiniMapConfig.MM_EDGE_SAMPLE_COUNT > MiniMapConfig.MM_BLACK_RATIO_THRESHOLD && distToCenter < maxDist) {
                // 转换回原始图像坐标
                double origCX = detCx / scale;
                double origCY = detCy / scale;
                int origR = (int) (detR / scale);
                return DetectionResult.success(origCX, origCY, origR);
            }
        }
        return DetectionResult.failure();
    }

    private void initMats(int w, int h) {
        if (grayMat == null || grayMat.cols() != w || grayMat.rows() != h) {
            releaseMats();
            scale = (double) MiniMapConfig.MM_SMALL_WIDTH / w;
            int sh = (int) (h * scale);
            grayMat = new Mat(h, w, opencv_core.CV_8UC1);
            smallGray = new Mat(sh, MiniMapConfig.MM_SMALL_WIDTH, opencv_core.CV_8UC1);
            smallGrayData = new byte[MiniMapConfig.MM_SMALL_WIDTH * sh];
            blurMat = new Mat(sh, MiniMapConfig.MM_SMALL_WIDTH, opencv_core.CV_8UC1);
        }
    }

    private void releaseMats() {
        if (grayMat != null) {
            grayMat.close();
            grayMat = null;
        }
        if (smallGray != null) {
            smallGray.close();
            smallGray = null;
        }
        if (blurMat != null) {
            blurMat.close();
            blurMat = null;
        }
        smallGrayData = null;
    }

    @Override
    public void close() {
        releaseMats();
    }

    /**
     * 检测结果封装
     */
    public static class DetectionResult {
        public final boolean success;
        public final double centerX;
        public final double centerY;
        public final int radius;

        private DetectionResult(boolean success, double centerX, double centerY, int radius) {
            this.success = success;
            this.centerX = centerX;
            this.centerY = centerY;
            this.radius = radius;
        }

        public static DetectionResult failure() {
            return new DetectionResult(false, 0, 0, 0);
        }

        public static DetectionResult success(double cx, double cy, int r) {
            return new DetectionResult(true, cx, cy, r);
        }
    }
}