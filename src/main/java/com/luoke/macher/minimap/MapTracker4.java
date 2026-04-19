package com.luoke.macher.minimap;

import com.luoke.capture.CaptureFrameRecord;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.Vec3fVector;

import java.nio.FloatBuffer;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * MapTracker - 高性能自适应地图追踪器
 * 1. 极速模式：<1ms (像素级梯度指纹校验)
 * 2. 全局模式：毫秒级 (降采样 + 霍夫圆 + 颜色指纹校验)
 *
 * 极速版与全全局模式都无法正常工作
 */
@Slf4j
public class MapTracker4 {

    private static volatile MapTracker4 instance;

    private boolean isTracked = false;
    private int lastScreenWidth = -1;
    private int lastScreenHeight = -1;

    private Point componentCenter = null;
    private float componentRadius = 0;
    private Rect activeROI = null;
    private double scaleFactor = 1.0;

    // 预分配内存，避免频繁 GC
    private Mat smallGray = new Mat();

    private MapTracker4() {}

    public static MapTracker4 getInstance() {
        if (instance == null) {
            synchronized (MapTracker4.class) {
                if (instance == null) instance = new MapTracker4();
            }
        }
        return instance;
    }

    /**
     * 对外主接口
     */
    public boolean ensureInitialized(CaptureFrameRecord record) {
        if (record == null || record.bytes() == null) return false;

        // --- 模式 1：极速边缘特征校验 (Track Mode) ---
        if (isTracked && record.width() == lastScreenWidth && record.height() == lastScreenHeight) {
            if (fastCheckEdgeGradient(record)) {
                return true;
            }
            log.warn("[MapTracker] 极速校验失败，地图可能被遮挡或场景切换，尝试重新搜索");
            isTracked = false;
        }

        // --- 模式 2：全局搜索 (Search Mode) ---
        log.info("[MapTracker] 启动全局搜索：降采样检测 + 颜色特征指纹过滤...");
        long sStart = System.nanoTime();
        boolean success = performGlobalSearch(record);
        long sEnd = System.nanoTime();

        if (success) {
            log.info("[MapTracker] 定位成功! 耗时: {}ms, 半径: {}, 缩放: {}",
                    (sEnd - sStart) / 1_000_000.0, componentRadius, String.format("%.2f", scaleFactor));
        } else {
            log.debug("[MapTracker] 本帧未发现小地图...");
        }
        return success;
    }

    /**
     * 极速校验核心逻辑
     * 修复了 by 变量找不到的错误，并优化了采样逻辑
     */
    private boolean fastCheckEdgeGradient(CaptureFrameRecord record) {
        if (componentCenter == null || componentRadius <= 0) return false;

        byte[] data = record.bytes();
        int w = record.width();
        int h = record.height();
        int channels = 4;

        // 选取 4 个方向，对比“圆周点A”和“内缩点B”的亮度差
        int[][] directions = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        int validEdges = 0;

        for (int[] dir : directions) {
            int ax = componentCenter.x() + (int)(dir[0] * componentRadius);
            int ay = componentCenter.y() + (int)(dir[1] * componentRadius);

            // 圆周内侧 6 像素的点
            int bx = componentCenter.x() + (int)(dir[0] * (componentRadius - 6));
            int by = componentCenter.y() + (int)(dir[1] * (componentRadius - 6));

            if (isOut(ax, ay, w, h) || isOut(bx, by, w, h)) continue;

            int brightA = getBrightness(data, ax, ay, w, channels);
            int brightB = getBrightness(data, bx, by, w, channels);

            // 只要差值足够大，说明这里存在明显的边框纹理
            if (Math.abs(brightA - brightB) > 30) {
                validEdges++;
            }
        }

        // 4个方向中至少2个方向符合梯度特征，即认为地图还在
        return validEdges >= 2;
    }

    /**
     * 全局搜索：通过降采样加速，通过颜色指纹防背景误判
     */
    private boolean performGlobalSearch(CaptureFrameRecord record) {
        if (smallGray != null) { smallGray.release(); smallGray = new Mat(); }

        try (Mat screenMat = new Mat(record.height(), record.width(), CV_8UC4, new BytePointer(record.bytes()))) {
            int roiW = screenMat.cols() / 2;
            int roiH = screenMat.rows() / 2;
            int offsetX = screenMat.cols() - roiW;

            try (Mat roiMat = new Mat(screenMat, new Rect(offsetX, 0, roiW, roiH))) {
                // 1. 降采样 0.5x
                double dsFactor = 0.5;
                resize(roiMat, smallGray, new Size(), dsFactor, dsFactor, INTER_LINEAR);
                cvtColor(smallGray, smallGray, COLOR_BGR2GRAY);
                // 2. 强力高斯模糊：抹除背景碎纹理，保留明显的圆框
                GaussianBlur(smallGray, smallGray, new Size(7, 7), 2.0);

                double ratio = (double) record.height() / 1080.0;
                int minR = (int) (75 * ratio * dsFactor);
                int maxR = (int) (135 * ratio * dsFactor);

                try (Vec3fVector circles = new Vec3fVector()) {
                    // param2=45，提高圆检测阈值，减少背景杂色干扰
                    HoughCircles(smallGray, circles, HOUGH_GRADIENT, 1.2, 100 * ratio * dsFactor, 100, 45, minR, maxR);

                    for (int i = 0; i < circles.size(); i++) {
                        FloatBuffer buffer = circles.get(i).asBuffer();
                        float rawX = (buffer.get(0) / (float)dsFactor) + offsetX;
                        float rawY = (buffer.get(1) / (float)dsFactor);
                        float rawR = (buffer.get(2) / (float)dsFactor);

                        // --- 3. 颜色指纹校验（防误判关键） ---
                        if (checkCircleColorAndGradient(record, (int)rawX, (int)rawY, rawR)) {
                            this.componentCenter = new Point(Math.round(rawX), Math.round(rawY));
                            this.componentRadius = rawR;
                            this.scaleFactor = rawR / 100.0 / ratio;

                            calculateROI();
                            this.lastScreenWidth = record.width();
                            this.lastScreenHeight = record.height();
                            this.isTracked = true;
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("[MapTracker] 搜索过程异常", e);
        }
        return false;
    }

    /**
     * 颜色指纹校验：检查候选圆周点是否符合金色/亮色边框特征
     */
    private boolean checkCircleColorAndGradient(CaptureFrameRecord record, int cx, int cy, float radius) {
        byte[] data = record.bytes();
        int w = record.width();
        int h = record.height();
        int channels = 4;

        int matchPoints = 0;
        // 采样 6 个角度
        int[][] dirs = {{1,0}, {-1,0}, {0,1}, {0,-1}, {1,1}, {-1,-1}};

        for (int[] d : dirs) {
            int x = cx + (int)(d[0] * radius);
            int y = cy + (int)(d[1] * radius);

            if (isOut(x, y, w, h)) continue;

            int offset = (y * w + x) * channels;
            int b = data[offset] & 0xFF;
            int g = data[offset + 1] & 0xFF;
            int r = data[offset + 2] & 0xFF;

            // 洛克小地图边框特征：偏金/黄/棕，即 R > G 且 R > B
            // 如果背景是绿草地或蓝水面，这里会直接判定不通过
            if (r > 65 && r > b + 15) {
                matchPoints++;
            }
        }
        return matchPoints >= 3;
    }

    private int getBrightness(byte[] data, int x, int y, int width, int channels) {
        int offset = (y * width + x) * channels;
        int b = data[offset] & 0xFF;
        int g = data[offset + 1] & 0xFF;
        int r = data[offset + 2] & 0xFF;
        return (r + g + b) / 3;
    }

    private boolean isOut(int x, int y, int w, int h) {
        return x < 0 || x >= w || y < 0 || y >= h;
    }

    private void calculateROI() {
        if (componentRadius <= 0) return;
        int r = (int) (componentRadius * 0.9);
        this.activeROI = new Rect(
                Math.max(0, componentCenter.x() - r),
                Math.max(0, componentCenter.y() - r),
                r * 2, r * 2
        );
    }

    public void forceReset() {
        this.isTracked = false;
    }

    public Rect getActiveROI() { return activeROI; }
    public double getScaleFactor() { return scaleFactor; }
}