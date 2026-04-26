package com.luoke.app.macher.player;

import org.bytedeco.javacpp.indexer.IntIndexer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.*;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 模块化箭头检测器
 *
 * <p>该类负责从游戏屏幕中检测玩家方向箭头，并计算箭头的朝向角度。</p>
 * <p>使用颜色过滤和形态学处理提取箭头轮廓，通过几何计算确定朝向。</p>
 *
 * <h3>检测流程：</h3>
 * <ol>
 * <li>ROI截取：提取屏幕中心区域的图像</li>
 * <li>图像预处理：颜色过滤和形态学处理</li>
 * <li>轮廓提取：查找所有可能的箭头轮廓</li>
 * <li>轮廓筛选：选择最像箭头的轮廓</li>
 * <li>角度计算：计算箭头的朝向角度</li>
 * </ol>
 *
 * <h3>性能优化点：</h3>
 * <ul>
 * <li>ROI截取减少处理区域，提高速度</li>
 * <li>形态学操作优化（连续膨胀3次）</li>
 * <li>使用凸包和近似多边形降低计算量</li>
 * <li>评分机制选择最优轮廓</li>
 * </ul>
 *
 * <h3>Native资源管理：</h3>
 * <ul>
 * <li>所有本地资源使用 try-with-resources 或显式 release() 释放</li>
 * <li>修复了方法返回已释放对象导致空指针的逻辑 Bug</li>
 * <li>确保 20 小时连续运行下堆外内存（Off-heap）保持稳定</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.1
 */
public class ArrowDetector {

    /**
     * 检测玩家方向箭头
     *
     * <p>从输入图像中检测箭头轮廓，计算箭头的朝向角度，返回Player对象。</p>
     *
     * @param src 输入图像（BGRA或BGR格式）
     * @return Player对象，包含found标志和angle值
     */
    public static Player detectPlayer(Mat src) {
        // 前置检查：确保输入图像有效
        if (src == null || src.empty()) return Player.builder().found(false).build();

        // 1. ROI 截取 (核心区域)
        // 使用 try-with-resources 自动管理这一层的所有临时 Native 资源
        try (Rect roiRect = new Rect((int)(src.cols()*0.30), (int)(src.rows()*0.30),
                (int)(src.cols()*0.4), (int)(src.rows()*0.4));
             Mat roiRaw = new Mat(src, roiRect); // 原始ROI视图
             Mat roi = new Mat();                // 转换后的BGR图像
             Mat mask = new Mat()) {             // 预处理结果掩码

            // 颜色转换：如果是BGRA格式，转换为BGR格式
            if (src.channels() == 4) {
                cvtColor(roiRaw, roi, COLOR_BGRA2BGR);
            } else {
                roiRaw.copyTo(roi);
            }

            // 2. 图像预处理 (获取干净的掩码)
            // 优化：通过引用传递 mask，避免方法内部 release 导致返回对象失效
            preprocess(roi, mask);

            // 3. 寻找最优箭头轮廓
            // findArrowContour 返回的是一个克隆出的新 Mat，需要单独管理
            try (Mat arrowContour = findArrowContour(mask, roi.cols(), roi.rows())) {
                if (arrowContour != null) {
                    // 4. 计算朝向角度
                    double angle = calculateOrientation(arrowContour);
                    return Player.builder().found(true).angle(angle).build();
                }
            }
        } catch (Exception e) {
            // 兜底异常处理，防止图像处理中的偶发错误导致主程序崩溃
            System.err.println("[ArrowDetector] Error during detection: " + e.getMessage());
        }

        return Player.builder().found(false).build();
    }

    /**
     * 图像预处理：颜色过滤与形态学降噪
     *
     * <h3>设计意图：</h3>
     * <ul>
     * <li>膨胀3次是关键优化：确保箭头主体和尾部小方块连成整体</li>
     * <li>连接后重心会回到正中心偏后的位置，确保角度计算准确</li>
     * </ul>
     *
     * @param roi 输入图像
     * @param outMask 输出的二值掩码（由外部管理生命周期）
     */
    private static void preprocess(Mat roi, Mat outMask) {
        // 内部中间变量随 try 块结束自动释放
        try (Mat hsv = new Mat();
             Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
             Mat lower = new Mat(new Scalar(12, 180, 100, 0));
             Mat upper = new Mat(new Scalar(25, 255, 255, 0))) {

            cvtColor(roi, hsv, COLOR_BGR2HSV);

            // 颜色范围过滤：提取橙色/黄色区域
            inRange(hsv, lower, upper, outMask);

            // 4. 腐蚀操作：去除小噪点（1次）
            erode(outMask, outMask, kernel, new Point(-1, -1), 1, BORDER_CONSTANT, null);

            // 5. 膨胀操作：【核心优化】连续膨胀 3 次！
            dilate(outMask, outMask, kernel, new Point(-1, -1), 3, BORDER_CONSTANT, null);
        }
    }

    /**
     * 从众多色块中筛选出最像箭头的那个
     *
     * <h3>评分机制：</h3>
     * <ul>
     * <li>评分公式：score = area / (distance + 1)</li>
     * <li>原理：面积大且离中心近的更有可能是玩家箭头</li>
     * </ul>
     *
     * @return 最优轮廓（由调用者负责释放）
     */
    private static Mat findArrowContour(Mat mask, int w, int h) {
        try (MatVector contours = new MatVector()) {
            findContours(mask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            double maxScore = -1;
            Mat bestContour = null;

            for (int i = 0; i < contours.size(); i++) {
                // 必须在循环内手动释放 get(i) 产生的每一个中间轮廓对象
                try (Mat c = contours.get(i)) {
                    double area = contourArea(c);
                    if (area < 50) continue;

                    try (Rect rect = boundingRect(c)) {
                        double cx = rect.x() + rect.width() / 2.0;
                        double cy = rect.y() + rect.height() / 2.0;
                        double dist = Math.sqrt(Math.pow(cx - w / 2.0, 2) + Math.pow(cy - h / 2.0, 2));

                        double score = area / (dist + 1);

                        if (score > maxScore) {
                            maxScore = score;
                            // 关键：释放掉之前保留的旧轮廓，再存入新的
                            if (bestContour != null) bestContour.release();
                            bestContour = c.clone();
                        }
                    }
                }
            }
            return bestContour;
        }
    }

    /**
     * 计算箭头的数学角度
     *
     * <h3>技术细节：</h3>
     * <ul>
     * <li>使用几何矩计算重心，比轮廓点均值更稳</li>
     * <li>利用凸包和多边形近似 (2% 周长精度) 快速锁定尖端</li>
     * </ul>
     */
    private static double calculateOrientation(Mat contour) {
        try (Moments m = moments(contour);
             Mat hull = new Mat();
             Mat approx = new Mat()) {

            // 除零保护：如果面积为0，直接返回
            if (m.m00() == 0) return 0;

            double cx = m.m10() / m.m00(); // 重心X
            double cy = m.m01() / m.m00(); // 重心Y

            convexHull(contour, hull);
            approxPolyDP(contour, approx, arcLength(contour, true) * 0.02, true);

            // 显式释放 Indexer 是防止高频堆外碎片化的最佳实践
            try (IntIndexer indexer = approx.createIndexer()) {
                double maxDist = -1;
                int tx = 0, ty = 0;

                for (int i = 0; i < approx.rows(); i++) {
                    int px = indexer.get(i, 0, 0);
                    int py = indexer.get(i, 0, 1);
                    double d = Math.pow(px - cx, 2) + Math.pow(py - cy, 2);
                    if (d > maxDist) {
                        maxDist = d;
                        tx = px; ty = py;
                    }
                }
                // 返回度数坐标
                return Math.toDegrees(Math.atan2(ty - cy, tx - cx));
            }
        }
    }
}