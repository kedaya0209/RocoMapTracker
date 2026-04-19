package com.luoke.macher.player;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.*;
import org.bytedeco.javacpp.indexer.Indexer;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 传统算法匹配，准确性图个乐
 */
@Slf4j
public class RocoTrackerUtils {

    /**
     * 解析小地图并更新玩家状态
     */
    public static Player updatePlayerInfo(Mat fullSrc) {
        if (fullSrc == null || fullSrc.empty()) {
            log.warn("输入图像为空，无法识别玩家");
            return Player.builder().found(false).build();
        }

        // 1. 预处理 (保持之前的 ROI 逻辑)
        int w = fullSrc.cols(), h = fullSrc.rows();
        Rect roi = new Rect((int) (w * 0.4), (int) (h * 0.4), (int) (w * 0.2), (int) (h * 0.2));

        Mat cropped = new Mat(fullSrc, roi).clone();
        Mat src = new Mat();
        resize(cropped, src, new Size(500, 500), 0, 0, INTER_LINEAR);

        // 2. HSV 过滤
        Mat hsv = new Mat();
        cvtColor(src, hsv, COLOR_BGR2HSV);
        Mat mask = new Mat();
        inRange(hsv, new Mat(hsv.size(), hsv.type(), new Scalar(15, 60, 70, 0)),
                new Mat(hsv.size(), hsv.type(), new Scalar(40, 255, 255, 0)), mask);

        // 3. 形态学处理
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(5, 5));
        morphologyEx(mask, mask, MORPH_CLOSE, kernel);

        // 4. 轮廓提取与筛选
        MatVector contours = new MatVector();
        findContours(mask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

        long targetIdx = -1;
        double minDistance = Double.MAX_VALUE;
        Point imgCenter = new Point(250, 250);

        for (long i = 0; i < contours.size(); i++) {
            Mat contour = contours.get(i);
            if (contourArea(contour) < 1500) continue;

            Moments mu = moments(contour);
            if (mu.m00() == 0) continue;
            int cx = (int) (mu.m10() / mu.m00());
            int cy = (int) (mu.m01() / mu.m00());

            double dist = Math.sqrt(Math.pow(cx - imgCenter.x(), 2) + Math.pow(cy - imgCenter.y(), 2));
            if (dist < minDistance && dist < 150) {
                minDistance = dist;
                targetIdx = i;
            }
        }

        // 5. 结果填充
        Player player;
        if (targetIdx != -1) {
            Mat bestContour = contours.get(targetIdx);
            Moments mu = moments(bestContour);
            int cx = (int) (mu.m10() / mu.m00());
            int cy = (int) (mu.m01() / mu.m00());

            // 计算朝向角度
            Point tip = findFarthestPoint(bestContour, new Point(cx, cy));
            double angleRad = Math.atan2(cy - tip.y(), tip.x() - cx);
            double angleDeg = (Math.toDegrees(angleRad) + 360) % 360;

            // 还原到原图坐标
            int originalX = (int) (cx / 500.0 * roi.width()) + roi.x();
            int originalY = (int) (cy / 500.0 * roi.height()) + roi.y();

            player = Player.builder()
                    .found(true)
                    .angle(angleDeg)
                    .pos(new Point(originalX, originalY))
                    .build();

            log.debug("成功锁定玩家: 坐标({}, {}), 角度 {}°", originalX, originalY, String.format("%.2f", angleDeg));
        } else {
            player = Player.builder().found(false).build();
            log.trace("未在当前帧发现目标区域");
        }

        // 资源回收
        releaseAll(hsv, mask, kernel, src, cropped);
        return player;
    }

    private static Point findFarthestPoint(Mat contour, Point center) {
        Point farthest = new Point(center.x(), center.y());
        double maxDist = -1;
        Indexer indexer = contour.createIndexer();
        for (long j = 0; j < contour.rows(); j++) {
            int px = (int) indexer.getDouble(j, 0, 0);
            int py = (int) indexer.getDouble(j, 0, 1);
            double d = Math.pow(px - center.x(), 2) + Math.pow(py - center.y(), 2);
            if (d > maxDist) {
                maxDist = d;
                farthest.x(px);
                farthest.y(py);
            }
        }
        return farthest;
    }

    // 辅助工具：批量释放内存
    private static void releaseAll(Mat... mats) {
        for (Mat m : mats) {
            if (m != null) m.release();
        }
    }
}