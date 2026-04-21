package com.luoke.macher.minimap;

import com.luoke.capture.CaptureFrameRecord;
import com.luoke.capture.WGCCapture;
import com.luoke.processor.MiniMapProcessor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
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

        // ==============================
        // 【修复】只有成功才缓存窗口大小
        // ==============================
        if (w != lastW || h != lastH) {
            if (!detectStrictUpperRight(frame)) return null;
            lastW = w;
            lastH = h;
        }

        return MiniMapProcessor.extractCircleMaskMiniMapBytes(
                frame.getPixels(), w, h, mapX, mapY, mapSize, mapSize
        );
    }

    // ==============================
    // 改为返回 boolean：成功/失败
    // ==============================
    private boolean detectStrictUpperRight(WGCCapture.Frame frame) {
        int sw = frame.getWidth();
        int sh = frame.getHeight();

        int roiX = (int) (sw * 0.89);
        int roiY = (int) (sh * 0.08);
        int roiW = sw - roiX - ((int) (sw * 0.01));
        int roiH = (int) (sh * 0.15);

        try (Mat screenMat = new Mat(sh, sw, CV_8UC4, new BytePointer(frame.getPixels()));
             Mat roiMat = new Mat(screenMat, new org.bytedeco.opencv.opencv_core.Rect(roiX, roiY, roiW, roiH));
             Mat smallMat = new Mat();
             Mat grayMat = new Mat()) {

            resize(roiMat, smallMat, new Size(0, 0), SCALE, SCALE, INTER_LINEAR);
            cvtColor(smallMat, grayMat, COLOR_BGR2GRAY);
            medianBlur(grayMat, grayMat, 3);

            try (org.bytedeco.opencv.opencv_imgproc.Vec3fVector circles = new org.bytedeco.opencv.opencv_imgproc.Vec3fVector()) {

                double minDist = 180 * SCALE;
                int minR = (int) (80 * SCALE);
                int maxR = (int) (180 * SCALE);

                HoughCircles(grayMat, circles, HOUGH_GRADIENT,
                        1, minDist, 260, 55, minR, maxR);

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
                    return true; // 成功
                }
            }
        }

        log.warn("❌ 小地图检测失败");
        return false; // 失败
    }

    public void reset() {
        lastW = -1;
        lastH = -1;
    }
}