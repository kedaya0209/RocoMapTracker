package com.luoke.macher.minimap;

import com.luoke.capture.CaptureFrameRecord;
import com.luoke.capture.WGCCapture;
import com.luoke.processor.MiniMapProcessor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_core.convertScaleAbs;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

@Slf4j
public class MapTracker {

    private static volatile MapTracker instance;
    private static final double SCALE = 0.5;

    private int lastW = -1;
    private int lastH = -1;
    private int mapX, mapY, mapSize;

    private MapTracker() {}

    public static MapTracker getInstance() {
        if (instance == null) {
            synchronized (MapTracker.class) {
                if (instance == null) instance = new MapTracker();
            }
        }
        return instance;
    }

    public CaptureFrameRecord getMiniMapImage(WGCCapture.Frame frame) {
        if (frame == null || frame.getPixels() == null) return null;

        int w = frame.getWidth();
        int h = frame.getHeight();

        // 窗口大小变化时重新检测小地图
        if (w != lastW || h != lastH) {
            if (!detectStrictUpperRight(frame)) return null;
            lastW = w;
            lastH = h;
        }

        // 使用已定位的坐标提取小地图
        return MiniMapProcessor.extractCircleMaskMiniMapBytes(
                frame.getPixels(), w, h, mapX, mapY, mapSize, mapSize
        );
    }

    /**
     * 强化版小地图检测
     * 支持：地上圆形边框 + 地下无框高亮小地图
     * 你的 ROI 完全保持不变！
     */
    private boolean detectStrictUpperRight(WGCCapture.Frame frame) {
        int sw = frame.getWidth();
        int sh = frame.getHeight();

        // ======================
        // 你已经调好的完美 ROI
        // ======================
        int roiX = (int) (sw * 0.89);
        int roiY = (int) (sh * 0.08);
        int roiW = sw - roiX - ((int) (sw * 0.01));
        int roiH = (int) (sh * 0.15);

        try (Mat screenMat = new Mat(sh, sw, CV_8UC4, new BytePointer(frame.getPixels()));
             Mat roiMat = new Mat(screenMat, new org.bytedeco.opencv.opencv_core.Rect(roiX, roiY, roiW, roiH));
             Mat smallMat = new Mat();
             Mat grayMat = new Mat();
             Mat enhancedMat = new Mat()) {

            // 缩小图像，提升检测速度
            resize(roiMat, smallMat, new Size(0, 0), SCALE, SCALE, INTER_LINEAR);

            // 转灰度图
            cvtColor(smallMat, grayMat, COLOR_BGR2GRAY);

            // ======================
            // 【强化】对比度拉满，地下小地图立刻变清晰
            // ======================
            convertScaleAbs(grayMat, enhancedMat, 2.0, 15);

            // 去噪
            medianBlur(enhancedMat, enhancedMat, 3);

            try (org.bytedeco.opencv.opencv_imgproc.Vec3fVector circles = new org.bytedeco.opencv.opencv_imgproc.Vec3fVector()) {

                double minDist = 180 * SCALE;
                int minR = (int) (70 * SCALE);
                int maxR = (int) (190 * SCALE);

                // ======================
                // 【强化】霍夫圆参数大幅优化，地下也能稳定检测
                // ======================
                HoughCircles(enhancedMat, circles, HOUGH_GRADIENT,
                        1, minDist,
                        150,   // 更低的边缘阈值，能检出淡边框
                        28,    // 更高灵敏度，地下无框也能识别
                        minR, maxR);

                if (circles.size() > 0) {
                    float x = circles.get(0).get(0);
                    float y = circles.get(0).get(1);
                    float r = circles.get(0).get(2);

                    int cx = (int) (x / SCALE);
                    int cy = (int) (y / SCALE);
                    int radius = (int) (r / SCALE);

                    this.mapX = roiX + cx - radius;
                    this.mapY = roiY + cy - radius;
                    this.mapSize = radius * 2;

                    log.info("✅ 小地图稳定定位：x={} y={} size={}", mapX, mapY, mapSize);
                    return true;
                }
            }
        }

        log.warn("❌ 小地图检测失败");
        return false;
    }

    public void reset() {
        lastW = -1;
        lastH = -1;
    }
}