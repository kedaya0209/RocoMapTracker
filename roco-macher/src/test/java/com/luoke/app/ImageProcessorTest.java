package com.luoke.app;

import org.bytedeco.javacpp.indexer.IntRawIndexer;
import org.bytedeco.javacpp.indexer.UByteRawIndexer;
import org.bytedeco.opencv.opencv_core.*;

import java.io.File;

import static org.bytedeco.opencv.global.opencv_core.bitwise_not;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class ImageProcessorTest {

    static void main(String[] args) {

        String inputDir = "C:\\Users\\tangh\\Desktop\\test";
        String outputDir = "C:\\Users\\tangh\\Desktop\\result";

        processFolder(inputDir, outputDir);
    }

    public static void processFolder(String inputDir, String outputDir) {
        File inFolder = new File(inputDir);
        if (!inFolder.exists()) return;

        File outFolder = new File(outputDir);
        if (!outFolder.exists()) outFolder.mkdirs();

        File[] files = inFolder.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName().toLowerCase();
            if (!(name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg"))) continue;

            System.out.println("处理: " + f.getName());
            processImage(f.getAbsolutePath(), outputDir);
        }
    }

    public static boolean processImage(String inputPath, String outputDir) {
        try {
            Mat src = imread(inputPath, IMREAD_GRAYSCALE);
            if (src.empty()) return false;

            // Step1: 对比度增强
            Mat step1 = new Mat();
            equalizeHist(src, step1);

            // Step2: 统计直方图，计算 P90
            int[] hist = new int[256];
            UByteRawIndexer indexer = step1.createIndexer();
            for (int y = 0; y < step1.rows(); y++) {
                for (int x = 0; x < step1.cols(); x++) {
                    int v = indexer.get(y, x) & 0xFF;
                    hist[v]++;
                }
            }
            indexer.release();

            int totalPixels = step1.rows() * step1.cols();
            int target = (int) (totalPixels * 0.90); // P90
            int cumulative = 0;
            int brightThreshold = 255;
            for (int i = 0; i < 256; i++) {
                cumulative += hist[i];
                if (cumulative >= target) {
                    brightThreshold = i;
                    break;
                }
            }
            System.out.println("P90 高亮阈值: " + brightThreshold);

            // Step3: 高亮区域 mask
            Mat brightMask = new Mat();
            threshold(step1, brightMask, brightThreshold, 255, THRESH_BINARY);

            // Step4: 替换高亮区域为灰色
            Mat otsuInput = step1.clone();
            Mat grayMat = new Mat(otsuInput.size(), otsuInput.type(), new Scalar(128));
            grayMat.copyTo(otsuInput, brightMask);

            // Step5: OTSU 二值化
            Mat step2 = new Mat();
            threshold(otsuInput, step2, 0, 255, THRESH_BINARY | THRESH_OTSU);

            // Step6: 反转
            Mat step3 = new Mat();
            bitwise_not(step2, step3);

            // Step7: 查找轮廓
            MatVector contours = new MatVector();
            findContours(step3.clone(), contours, RETR_EXTERNAL, CHAIN_APPROX_NONE);
            if (contours.size() == 0) return false;

            double centerX = src.cols() / 2.0;
            double centerY = src.rows() / 2.0;

            // Step8: 找离中心最近轮廓
            Mat bestContour = null;
            double bestDistance = Double.MAX_VALUE;
            for (long i = 0; i < contours.size(); i++) {
                Mat contour = contours.get(i);
                double area = contourArea(contour);
                if (area < 5 || area > 300) continue;
                Rect rect = boundingRect(contour);
                if (rect.width() < 4 || rect.height() < 4) continue;

                Moments m = moments(contour);
                if (m.m00() == 0) continue;

                double cx = m.m10() / m.m00();
                double cy = m.m01() / m.m00();
                double dx = cx - centerX;
                double dy = cy - centerY;
                double dist = dx * dx + dy * dy;
                if (dist < bestDistance) {
                    bestDistance = dist;
                    bestContour = contour;
                }
            }
            if (bestContour == null) return false;

            // Step9: 算中心和尖端
            Moments moments = moments(bestContour);
            double cx = moments.m10() / moments.m00();
            double cy = moments.m01() / moments.m00();

            Point tip = null;
            double maxDist = -1;
            IntRawIndexer cIndexer = bestContour.createIndexer();
            for (int i = 0; i < bestContour.rows(); i++) {
                int x = cIndexer.get(i, 0, 0);
                int y = cIndexer.get(i, 0, 1);
                double dx = x - cx;
                double dy = y - cy;
                double dist = dx * dx + dy * dy;
                if (dist > maxDist) {
                    maxDist = dist;
                    tip = new Point(x, y);
                }
            }
            cIndexer.release();
            if (tip == null) return false;

            // Step10: 计算角度
            double dx = tip.x() - cx;
            double dy = -(tip.y() - cy);
            double angle = Math.toDegrees(Math.atan2(dy, dx));
            if (angle < 0) angle += 360;

            // Step11: 可视化
            Mat result = new Mat();
            cvtColor(src, result, COLOR_GRAY2BGR);
            drawContours(result, new MatVector(bestContour), -1, new Scalar(0, 255, 0, 0), 1, LINE_AA, null, Integer.MAX_VALUE, new Point());
            circle(result, new Point((int) centerX, (int) centerY), 2, new Scalar(255, 255, 0, 0), -1, LINE_AA, 0);
            circle(result, new Point((int) cx, (int) cy), 2, new Scalar(255, 0, 0, 0), -1, LINE_AA, 0);
            circle(result, tip, 2, new Scalar(0, 0, 255, 0), -1, LINE_AA, 0);
            line(result, new Point((int) cx, (int) cy), tip, new Scalar(0, 255, 255, 0), 1, LINE_AA, 0);
            putText(result, String.format("%.1f", angle), new Point(2, 12), FONT_HERSHEY_SIMPLEX, 0.35, new Scalar(0, 255, 255, 0));

            // 保存
            String fileName = new File(inputPath).getName();
            String outputPath = outputDir + File.separator + fileName;
            imwrite(outputPath, result);

            System.out.println("方向角: " + angle);

            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}