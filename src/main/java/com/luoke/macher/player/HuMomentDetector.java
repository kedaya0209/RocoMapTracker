package com.luoke.macher.player;

import org.bytedeco.javacpp.DoublePointer;
import org.bytedeco.javacpp.indexer.DoubleIndexer;
import org.bytedeco.opencv.opencv_core.*;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class HuMomentDetector {

    public static void main(String[] args) {
        String scenePath = "C:\\Users\\tangh\\Desktop\\test\\0-test-miniMap.png";
        String arrowPath = "C:\\Users\\tangh\\Desktop\\code\\realTimePosition\\src\\main\\resources\\source\\minMap-player-arrow.png";

        Mat scene = imread(scenePath);
        Mat rawArrow = imread(arrowPath, IMREAD_UNCHANGED);

        if (scene.empty() || rawArrow.empty()) {
            System.err.println("读取图片失败！");
            return;
        }

        // 1. 自动裁剪模板（去除多余透明边框）
        Mat arrowBase = cropAlpha(rawArrow);

        // 2. 预热匹配参数
        double bestScore = -1;
        Point bestLoc = new Point();
        int finalW = 0, finalH = 0;

        // 3. 核心：旋转匹配循环
        // 步长建议 15 度。如果还不够准，可以改为 10 度。
        for (int angle = 0; angle < 360; angle += 15) {
            Mat rotated = rotateImage(arrowBase, angle);

            // 分离通道以获取 Mask
            MatVector channels = new MatVector();
            split(rotated, channels);

            // 提取 BGR 模板
            Mat tBGR = new Mat();
            merge(new MatVector(channels.get(0), channels.get(1), channels.get(2)), tBGR);

            // 提取 Alpha 作为 Mask
            Mat mask = channels.get(3);

            Mat result = new Mat();
            // 使用 TM_CCORR_NORMED + Mask 是处理透明 UI 的工业级标准
            matchTemplate(scene, tBGR, result, TM_CCORR_NORMED, mask);

            DoublePointer maxVal = new DoublePointer(1);
            Point maxLoc = new Point();
            minMaxLoc(result, null, maxVal, null, maxLoc, null);

            if (maxVal.get() > bestScore) {
                bestScore = maxVal.get();
                bestLoc = new Point(maxLoc.x(), maxLoc.y());
                finalW = rotated.cols();
                finalH = rotated.rows();
            }

            // 及时释放内存，防止长时间挂机内存崩溃
            rotated.release();
            tBGR.release();
            mask.release();
            result.release();
            channels.close();
        }

        // 4. 判定与结果输出
        // TM_CCORR_NORMED 配合 Mask 时，分值通常非常接近 1.0
        if (bestScore > 0.8) {
            int centerX = bestLoc.x() + finalW / 2;
            int centerY = bestLoc.y() + finalH / 2;

            System.out.println("匹配成功！得分: " + bestScore);
            System.out.println("中心坐标: (" + centerX + ", " + centerY + ")");

            // 绘制定位红点
            circle(scene, new Point(centerX, centerY), 4, new Scalar(0, 0, 255, 0), -1, LINE_8, 0);
            imwrite("C:\\Users\\tangh\\Desktop\\test\\final_plugin_fix.png", scene);
        } else {
            System.out.println("匹配失败，最高分: " + bestScore);
        }

        scene.release();
        rawArrow.release();
        arrowBase.release();
    }

    /**
     * 自动裁剪：去除透明边框
     */
    private static Mat cropAlpha(Mat src) {
        if (src.channels() < 4) return src;
        MatVector channels = new MatVector();
        split(src, channels);
        Rect rect = boundingRect(channels.get(3));
        Mat cropped = new Mat(src, rect);
        channels.close();
        return cropped.clone();
    }

    /**
     * 旋转图像：通过 Indexer 手动修正平移矩阵，确保旋转后图像居中且不被裁切
     */
    private static Mat rotateImage(Mat src, double angle) {
        Point2f center = new Point2f(src.cols() / 2.0f, src.rows() / 2.0f);
        Mat rot = getRotationMatrix2D(center, angle, 1.0);

        Rect bbox = new RotatedRect(center, new Size2f(src.cols(), src.rows()), (float) angle).boundingRect();

        DoubleIndexer rotIdx = rot.createIndexer();
        rotIdx.put(0, 2, rotIdx.get(0, 2) + bbox.width() / 2.0 - center.x());
        rotIdx.put(1, 2, rotIdx.get(1, 2) + bbox.height() / 2.0 - center.y());

        Mat dst = new Mat();
        warpAffine(src, dst, rot, bbox.size(), INTER_LINEAR, BORDER_CONSTANT, new Scalar(0, 0, 0, 0));

        rot.release();
        rotIdx.release();
        return dst;
    }
}