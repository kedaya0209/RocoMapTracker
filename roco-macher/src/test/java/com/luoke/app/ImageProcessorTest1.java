package com.luoke.app;

import org.bytedeco.javacpp.indexer.IntRawIndexer;
import org.bytedeco.opencv.opencv_core.*;

import java.io.File;

import static org.bytedeco.opencv.global.opencv_core.bitwise_not;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class ImageProcessorTest1 {

    static void main(String[] args) {

        // 输入文件夹
        String inputDir =
                "C:\\Users\\tangh\\Desktop\\test";

        // 输出文件夹
        String outputDir =
                "C:\\Users\\tangh\\Desktop\\result";

        File outDir = new File(outputDir);

        if (!outDir.exists()) {
            outDir.mkdirs();
        }

        File dir = new File(inputDir);

        File[] files = dir.listFiles();

        if (files == null) {
            System.out.println("文件夹为空");
            return;
        }

        for (File file : files) {

            if (!file.isFile()) {
                continue;
            }

            String name = file.getName().toLowerCase();

            if (!(name.endsWith(".png")
                    || name.endsWith(".jpg")
                    || name.endsWith(".jpeg"))) {
                continue;
            }

            System.out.println("处理: " + file.getName());

            processImage(
                    file.getAbsolutePath(),
                    outputDir
            );
        }

        System.out.println("全部处理完成");
    }

    /**
     * 处理单张图片
     */
    public static void processImage(
            String inputPath,
            String outputDir
    ) {

        // =========================
        // 读取
        // =========================

        Mat src = imread(inputPath, IMREAD_GRAYSCALE);

        if (src.empty()) {
            System.out.println("读取失败: " + inputPath);
            return;
        }

        // =========================
        // 预处理
        // =========================

        Mat element =
                getStructuringElement(
                        MORPH_RECT,
                        new Size(3, 3)
                );

        // 对比度增强
        Mat step1 = new Mat();

        equalizeHist(src, step1);

        // OTSU 二值化
        Mat step2 = new Mat();

        threshold(
                step1,
                step2,
                0,
                255,
                THRESH_BINARY | THRESH_OTSU
        );

        // 膨胀
        Mat step3 = new Mat();

        dilate(step2, step3, element);

        // 反转
        Mat binary = new Mat();

        bitwise_not(step3, binary);

        // =========================
        // 查找轮廓
        // =========================

        MatVector contours = new MatVector();

        findContours(
                binary.clone(),
                contours,
                RETR_EXTERNAL,
                CHAIN_APPROX_NONE
        );

        if (contours.size() == 0) {
            return;
        }

        // =========================
        // 图像中心
        // =========================

        double centerX = src.cols() / 2.0;
        double centerY = src.rows() / 2.0;

        // =========================
        // 找离中心最近轮廓
        // =========================

        Mat bestContour = null;

        double bestDistance = Double.MAX_VALUE;

        for (long i = 0; i < contours.size(); i++) {

            Mat contour = contours.get(i);

            double area = contourArea(contour);

            if (area < 5 || area > 300) {
                continue;
            }

            Rect rect = boundingRect(contour);

            if (rect.width() < 4 || rect.height() < 4) {
                continue;
            }

            Moments m = moments(contour);

            if (m.m00() == 0) {
                continue;
            }

            double cx = m.m10() / m.m00();
            double cy = m.m01() / m.m00();

            double dx = cx - centerX;
            double dy = cy - centerY;

            double dist =
                    dx * dx + dy * dy;

            if (dist < bestDistance) {

                bestDistance = dist;

                bestContour = contour;
            }
        }

        if (bestContour == null) {
            return;
        }

        // =========================
        // 算质心
        // =========================

        Moments moments = moments(bestContour);

        double cx =
                moments.m10() / moments.m00();

        double cy =
                moments.m01() / moments.m00();

        // =========================
        // 找尖端
        // =========================

        Point tip = null;

        double maxDist = -1;

        IntRawIndexer indexer =
                bestContour.createIndexer();

        for (int i = 0; i < bestContour.rows(); i++) {

            int x = indexer.get(i, 0, 0);
            int y = indexer.get(i, 0, 1);

            double dx = x - cx;
            double dy = y - cy;

            double dist =
                    dx * dx + dy * dy;

            if (dist > maxDist) {

                maxDist = dist;

                tip = new Point(x, y);
            }
        }

        if (tip == null) {
            return;
        }

        // =========================
        // 算方向
        // =========================

        double dx = tip.x() - cx;

        double dy = -(tip.y() - cy);

        double angle =
                Math.toDegrees(
                        Math.atan2(dy, dx)
                );

        if (angle < 0) {
            angle += 360;
        }

        // =========================
        // 生成结果图
        // =========================

        Mat result = new Mat();

        cvtColor(src, result, COLOR_GRAY2BGR);

        // 轮廓
        drawContours(
                result,
                new MatVector(bestContour),
                -1,
                new Scalar(0, 255, 0, 0),
                1,
                LINE_AA,
                null,
                Integer.MAX_VALUE,
                new Point()
        );

        // 图像中心
        circle(
                result,
                new Point((int) centerX, (int) centerY),
                2,
                new Scalar(255, 255, 0, 0),
                -1,
                LINE_AA,
                0
        );

        // 箭头中心
        circle(
                result,
                new Point((int) cx, (int) cy),
                2,
                new Scalar(255, 0, 0, 0),
                -1,
                LINE_AA,
                0
        );

        // 尖端
        circle(
                result,
                tip,
                2,
                new Scalar(0, 0, 255, 0),
                -1,
                LINE_AA,
                0
        );

        // 朝向线
        line(
                result,
                new Point((int) cx, (int) cy + 10),
                tip,
                new Scalar(0, 255, 255, 0),
                1,
                LINE_AA,
                0
        );

        // 角度
        putText(
                result,
                String.format("%.1f", angle),
                new Point(2, 12),
                FONT_HERSHEY_SIMPLEX,
                0.35,
                new Scalar(0, 255, 255, 0)
        );

        // =========================
        // 输出 result.png
        // =========================

        String fileName =
                new File(inputPath).getName();

        String outputPath =
                outputDir + File.separator + fileName;

        imwrite(outputPath, result);

        System.out.println(
                "已保存: " + outputPath
        );
    }
}