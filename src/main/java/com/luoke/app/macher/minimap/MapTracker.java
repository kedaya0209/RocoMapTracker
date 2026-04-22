package com.luoke.app.macher.minimap;

import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.processor.MiniMapProcessor;
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

    private int lastW = -1;
    private int lastH = -1;
    private int mapX, mapY, mapSize;
    private static final double SCALE = 0.5;

    private MapTracker() {}

    public static MapTracker getInstance() {
        if (instance == null) {
            synchronized (MapTracker.class) {
                if (instance == null) instance = new MapTracker();
            }
        }
        return instance;
    }

    // ====================== ✅ 正确读取，无花屏 ======================
    public CaptureFrameRecord getMiniMapImage(Frame frame) {
        if (frame == null || frame.data() == null) return null;

        int w = frame.width();
        int h = frame.height();

        byte[] pixels = frame.getPixels();

        if (w != lastW || h != lastH) {
            reset();
            if (!detectStrictUpperRight(frame)) return null;
            lastW = w;
            lastH = h;
        }

        return MiniMapProcessor.extractCircleMaskMiniMapBytes(
                pixels, w, h, mapX, mapY, mapSize, mapSize
        );
    }

    private boolean detectStrictUpperRight(Frame frame) {
        int sw = frame.width();
        int sh = frame.height();
        byte[] data = frame.getPixels();

        int roiX = (int) (sw * 0.89);
        int roiY = (int) (sh * 0.08);
        int roiW = sw - roiX - ((int) (sw * 0.01));
        int roiH = (int) (sh * 0.15);

        try (Mat screenMat = new Mat(sh, sw, CV_8UC4, new BytePointer(data));
             Mat roiMat = new Mat(screenMat, new org.bytedeco.opencv.opencv_core.Rect(roiX, roiY, roiW, roiH));
             Mat smallMat = new Mat();
             Mat grayMat = new Mat();
             Mat enhancedMat = new Mat()) {

            resize(roiMat, smallMat, new Size(0, 0), SCALE, SCALE, INTER_LINEAR);
            cvtColor(smallMat, grayMat, COLOR_BGR2GRAY);
            convertScaleAbs(grayMat, enhancedMat, 2.0, 15);
            medianBlur(enhancedMat, enhancedMat, 3);

            try (org.bytedeco.opencv.opencv_imgproc.Vec3fVector circles = new org.bytedeco.opencv.opencv_imgproc.Vec3fVector()) {
                double minDist = 180 * SCALE;
                int minR = (int) (70 * SCALE);
                int maxR = (int) (190 * SCALE);

                HoughCircles(enhancedMat, circles, HOUGH_GRADIENT,
                        1, minDist, 150, 28, minR, maxR);

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