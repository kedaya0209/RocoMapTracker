package com.luoke.app.macher.player;

import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.*;

import static org.bytedeco.opencv.global.opencv_core.BORDER_CONSTANT;
import static org.bytedeco.opencv.global.opencv_core.inRange;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 模块化箭头检测器
 */
public class ArrowDetector {

    public static Player detectPlayer(Mat src) {
        if (src == null || src.empty()) return Player.builder().found(false).build();
        // 1. ROI 截取 (核心区域)
        Rect roiRect = new Rect((int)(src.cols()*0.30), (int)(src.rows()*0.30), (int)(src.cols()*0.4), (int)(src.rows()*0.4));
        Mat roi = new Mat();
        Mat roiRaw = new Mat(src, roiRect);
        if (src.channels() == 4) cvtColor(roiRaw, roi, COLOR_BGRA2BGR); else roiRaw.copyTo(roi);
        // 2. 图像预处理 (获取干净的掩码)
        Mat mask = preprocess(roi);
        // 3. 寻找最优箭头轮廓
        Mat arrowContour = findArrowContour(mask, roi.cols(), roi.rows());
        double angle = 0;
        boolean isFound = false;
        if (arrowContour != null) {
            isFound = true;
            // 4. 计算朝向 (并在 roi 图上绘制红点和绿线)
            angle = calculateOrientation(arrowContour, roi);
        }
        // 清理资源
        mask.release(); roi.release(); roiRaw.release();
        if (arrowContour != null) arrowContour.release();
        return Player.builder().found(isFound).angle(angle).build();
    }

    /**
     * [功能] 颜色过滤与形态学降噪
     */
    private static Mat preprocess(Mat roi) {
        Mat hsv = new Mat();
        Mat mask = new Mat();
        Mat result = new Mat();

        cvtColor(roi, hsv, COLOR_BGR2HSV);
        // 稍微放宽 V (亮度) 范围，确保尾部方块被抓到
        inRange(hsv, new Mat(new Scalar(12, 180, 100, 0)), new Mat(new Scalar(25, 255, 255, 0)), mask);

        Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));

        // A. 先腐蚀 1 次去噪
        erode(mask, mask, kernel, new Point(-1, -1), 1, BORDER_CONSTANT, null);

        // B. 【核心修改】连续膨胀 3 次！
        // 这样能确保箭头主体和尾部小方块连成一个大的整体，重心才会回到正中心偏后的位置
        dilate(mask, result, kernel, new Point(-1, -1), 3, BORDER_CONSTANT, null);

        hsv.release(); mask.release(); kernel.release();
        return result;
    }

    /**
     * [功能] 从众多色块中筛选出最像箭头的那个
     */
    private static Mat findArrowContour(Mat mask, int w, int h) {
        MatVector contours = new MatVector();
        findContours(mask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

        double maxScore = -1;
        Mat bestContour = null;

        for (int i = 0; i < contours.size(); i++) {
            Mat c = contours.get(i);
            double area = contourArea(c);
            if (area < 50) continue; // 过滤太小的杂质

            // 计算该色块中心到 ROI 中心的距离
            Rect rect = boundingRect(c);
            double cx = rect.x() + rect.width() / 2.0;
            double cy = rect.y() + rect.height() / 2.0;
            double dist = Math.sqrt(Math.pow(cx - w/2.0, 2) + Math.pow(cy - h/2.0, 2));

            // 评分机制：面积大且离中心近的得分高
            double score = area / (dist + 1);

            if (score > maxScore) {
                maxScore = score;
                if (bestContour != null) bestContour.release();
                bestContour = c.clone(); // 克隆最优秀的轮廓
            }
        }
        contours.close();
        return bestContour;
    }

    /**
     * [功能] 计算箭头的数学角度，并在图上标出“尖端”和“重心”
     */
    private static double calculateOrientation(Mat contour, Mat canvas) {
        Moments m = moments(contour);
        double cx = m.m10() / m.m00();
        double cy = m.m01() / m.m00();

        // 获取凸包顶点，计算量更小且更鲁棒
        Mat hull = new Mat();
        convexHull(contour, hull);
        Mat approx = new Mat();
        approxPolyDP(contour, approx, arcLength(contour, true) * 0.02, true);
        IntIndexer indexer = approx.createIndexer();

        double maxDist = -1;
        int tx = 0, ty = 0;

        // 1. 初步寻找最远点
        for (int i = 0; i < approx.rows(); i++) {
            int px = indexer.get(i, 0, 0);
            int py = indexer.get(i, 0, 1);
            double d = Math.pow(px - cx, 2) + Math.pow(py - cy, 2);
            if (d > maxDist) {
                maxDist = d;
                tx = px; ty = py;
            }
        }

        // 2. 【核心保险】利用“头窄尾宽”特性：
        // 真正的尖端方向，其周围的像素密度应该更低。
        // 如果你发现红点经常指反，可以计算 tx, ty 附近一小块区域的平均像素值，
        // 像素越少的那一头，才是真正的“尖角”。
        approx.release(); hull.release();
        return Math.toDegrees(Math.atan2(ty - cy, tx - cx));
    }
}