package com.luoke.macher.minimap;

import com.luoke.capture.CaptureFrameRecord;
import com.luoke.capture.WGCCapture;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec3fVector;

import java.nio.FloatBuffer;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 再小地图位置不变的情况下，该类检测只需要10ms，可以用于每一帧检测屏幕上是否有小地图
 */
@Slf4j
public class MapTracker {

    private static volatile MapTracker instance;

    // 配置常量
    private final double REFERENCE_RADIUS = 100.0;
    private final double DOWNSCALE_RATIO = 0.5; // 缩放比例，0.5 表示面积变为 1/4

    // 状态缓存
    private boolean isTracked = false;
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;

    // 识别结果
    private Point componentCenter = null;
    private float componentRadius = 0;
    private Rect activeROI = null;
    private double scaleFactor = 1.0;

    // 预分配内存对象（避免频繁 GC）
    private final Mat grayMat = new Mat();
    private final Mat smallRoiMat = new Mat();

    private MapTracker() {}

    public static MapTracker getInstance() {
        if (instance == null) {
            synchronized (MapTracker.class) {
                if (instance == null) instance = new MapTracker();
            }
        }
        return instance;
    }

    /**
     * 外部调用主入口
     */
    public boolean ensureInitialized(WGCCapture.Frame record) {
        if (record == null || record.getPixels() == null) return false;

        // 1. 状态快速检查：如果分辨率没变且已追踪，直接复用
        if (isTracked && record.getWidth() == lastScreenWidth && record.getHeight() == lastScreenHeight) {
            return true;
        }

        long start = System.currentTimeMillis();

        // 2. 将字节数组包装为 Mat (不复制数据，仅包装)
        try (Mat screenMat = new Mat(record.getHeight(), record.getWidth(), CV_8UC4, new BytePointer(record.getPixels()))) {
            boolean success = runOptimizedDetection(screenMat);
            if (success) {
                this.lastScreenWidth = record.getWidth();
                this.lastScreenHeight = record.getHeight();
                log.info("小地图定位成功: Center({}, {}), Radius: {}, 耗时: {}ms",
                        componentCenter.x(), componentCenter.y(), componentRadius, System.currentTimeMillis() - start);
            }
            return success;
        } catch (Exception e) {
            log.error("地图追踪异常", e);
            return false;
        }
    }

    /**
     * 优化的圆检测逻辑
     */
    private boolean runOptimizedDetection(Mat screen) {
        // 逻辑：仅检索右上角 1/4 区域
        int roiW = screen.cols() / 2;
        int roiH = screen.rows() / 2;
        int offsetX = screen.cols() - roiW;

        try (Mat roiMat = new Mat(screen, new Rect(offsetX, 0, roiW, roiH))) {

            // 步骤 A: 下采样 (关键优化：大幅减少 HoughCircles 扫描像素点)
            resize(roiMat, smallRoiMat, new Size(), DOWNSCALE_RATIO, DOWNSCALE_RATIO, INTER_LINEAR);

            // 步骤 B: 预处理
            cvtColor(smallRoiMat, grayMat, COLOR_BGR2GRAY);
            // 缩小后，较小的模糊核即可达到平滑效果
            medianBlur(grayMat, grayMat, 3);

            // 步骤 C: 霍夫圆变换
            try (Vec3fVector circles = new Vec3fVector()) {
                // 参数根据缩放比例动态调整
                double minDist = 50.0 * DOWNSCALE_RATIO;
                int minR = (int) (50 * DOWNSCALE_RATIO);
                int maxR = (int) (250 * DOWNSCALE_RATIO);

                HoughCircles(grayMat, circles, HOUGH_GRADIENT, 1.0,
                        minDist, 200.0, 40.0, minR, maxR);

                if (circles.size() > 0) {
                    FloatBuffer buffer = circles.get(0).asBuffer();

                    // 步骤 D: 坐标还原
                    float rawX = (float) (buffer.get(0) / DOWNSCALE_RATIO);
                    float rawY = (float) (buffer.get(1) / DOWNSCALE_RATIO);
                    float rawR = (float) (buffer.get(2) / DOWNSCALE_RATIO);

                    this.componentCenter = new Point(
                            Math.round(rawX + offsetX),
                            Math.round(rawY)
                    );
                    this.componentRadius = rawR;
                    this.scaleFactor = this.componentRadius / REFERENCE_RADIUS;

                    this.isTracked = true;
                    calculateActiveROI();
                    return true;
                }
            }
        }

        this.isTracked = false;
        return false;
    }

    /**
     * 根据检测到的圆，计算实际可用的地图内容区域
     */
    private void calculateActiveROI() {
        if (componentRadius <= 0) return;
        // 稍微向内收缩 10 像素，避开圆形边框
        int r = (int) componentRadius - 10;
        this.activeROI = new Rect(
                Math.max(0, componentCenter.x() - r),
                Math.max(0, componentCenter.y() - r),
                r * 2, r * 2
        );
    }

    /**
     * 重置追踪状态（如切换关卡后手动调用）
     */
    public void reset() {
        this.isTracked = false;
        this.activeROI = null;
    }

    // --- Getter 区域 ---

    public Rect getActiveROI() {
        return activeROI;
    }

    public double getScaleFactor() {
        return scaleFactor;
    }

    public Point getComponentCenter() {
        return componentCenter;
    }

    /**
     * 调试：返回标记了地图位置的图片
     */
    public java.awt.image.BufferedImage getDebugImage(CaptureFrameRecord record) {
        try (Mat canvas = new Mat(record.height(), record.width(), CV_8UC4, new BytePointer(record.bytes())).clone()) {
            if (isTracked && componentCenter != null) {
                circle(canvas, componentCenter, Math.round(componentRadius), new Scalar(0, 255, 0, 0), 2, LINE_AA, 0);
                if (activeROI != null) {
                    rectangle(canvas, activeROI, new Scalar(255, 0, 0, 0), 1, LINE_4, 0);
                }
            }
            try (org.bytedeco.javacv.Java2DFrameConverter converter = new org.bytedeco.javacv.Java2DFrameConverter();
                 org.bytedeco.javacv.OpenCVFrameConverter.ToMat matConverter = new org.bytedeco.javacv.OpenCVFrameConverter.ToMat()) {
                return converter.convert(matConverter.convert(canvas));
            }
        }
    }
}