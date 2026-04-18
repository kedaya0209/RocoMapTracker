package com.luoke.macher.minimap;

import com.luoke.capture.CaptureFrameRecord;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_imgproc.Vec3fVector;

import java.nio.FloatBuffer;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class MapTracker {

    private static volatile MapTracker instance;
    private final double REFERENCE_RADIUS = 100.0;
    private double scaleFactor = 1.0;
    private Point componentCenter = null;
    private float componentRadius = 0;
    private Rect activeROI = null;
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;

    private MapTracker() {
    }

    public static MapTracker getInstance() {
        if (instance == null) {
            synchronized (MapTracker.class) {
                if (instance == null) instance = new MapTracker();
            }
        }
        return instance;
    }

    /**
     * 对外接口：接收 Record 自动处理初始化
     */
    public boolean ensureInitialized(CaptureFrameRecord record) {
        if (record == null || record.bytes() == null) return false;

        if (record.width() == lastScreenWidth && record.height() == lastScreenHeight && activeROI != null) {
            return true;
        }

        // 内部转换并执行检测
        try (Mat screenMat = recordToMat(record)) {
            boolean success = runCircleDetection(screenMat);
            if (success) {
                this.lastScreenWidth = record.width();
                this.lastScreenHeight = record.height();
            }
            return success;
        }
    }

    /**
     * 内部逻辑：仅分析右上角区域
     */
    private boolean runCircleDetection(Mat screen) {
        // 定义右上角 25% 区域
        int roiW = screen.cols() / 2;
        int roiH = screen.rows() / 2;
        int offsetX = screen.cols() - roiW;
        int offsetY = 0;

        try (Mat roiMat = new Mat(screen, new Rect(offsetX, offsetY, roiW, roiH));
             Mat gray = new Mat()) {

            cvtColor(roiMat, gray, COLOR_BGR2GRAY);
            medianBlur(gray, gray, 5);

            Vec3fVector circles = new Vec3fVector();
            HoughCircles(gray, circles, HOUGH_GRADIENT, 1.0, 100.0, 200.0, 50.0, 50, 250);

            if (circles.size() > 0) {
                FloatBuffer buffer = circles.get(0).asBuffer();
                this.componentCenter = new Point(
                        Math.round(buffer.get(0) + offsetX),
                        Math.round(buffer.get(1) + offsetY)
                );
                this.componentRadius = buffer.get(2);
                this.scaleFactor = this.componentRadius / REFERENCE_RADIUS;

                resetToFullRange();
                circles.close();
                return true;
            }
            circles.close();
        }
        return false;
    }

    /**
     * 核心辅助：将 Record 转为内部 Mat
     */
    private Mat recordToMat(CaptureFrameRecord record) {
        // 假设截图是 4 通道 (BGRA/RGBA)
        return new Mat(record.height(), record.width(), CV_8UC4, new BytePointer(record.bytes()));
    }

    public void resetToFullRange() {
        if (componentRadius <= 0) return;
        int r = (int) componentRadius - 10;
        this.activeROI = new Rect(
                Math.max(0, componentCenter.x() - r),
                Math.max(0, componentCenter.y() - r),
                r * 2, r * 2
        );
    }

    /**
     * 调试方法：同样适配 Record
     */
    public java.awt.image.BufferedImage getDebugImage(CaptureFrameRecord record) {
        try (Mat canvas = recordToMat(record).clone()) {
            if (componentCenter != null && componentRadius > 0) {
                circle(canvas, componentCenter, Math.round(componentRadius), new Scalar(0, 0, 255, 0), 3, LINE_AA, 0);
            }
            try (org.bytedeco.javacv.Java2DFrameConverter converter = new org.bytedeco.javacv.Java2DFrameConverter();
                 org.bytedeco.javacv.OpenCVFrameConverter.ToMat matConverter = new org.bytedeco.javacv.OpenCVFrameConverter.ToMat()) {
                return converter.convert(matConverter.convert(canvas));
            }
        }
    }

    public Rect getActiveROI() {
        return activeROI;
    }

    public double getScaleFactor() {
        return scaleFactor;
    }
}