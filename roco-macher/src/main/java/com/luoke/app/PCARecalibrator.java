package com.luoke.app;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.FloatPointer;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_imgproc.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

public class PCARecalibrator {

    private static final String BASE_PATH = "C:\\Users\\tangh\\Desktop\\dataset\\";

    public static void main(String[] args) throws Exception {
        File trainDir = new File(BASE_PATH + "result");
        String valPcaPath = BASE_PATH + "validation_pca";
        Files.createDirectories(Paths.get(valPcaPath));

        File[] files = trainDir.listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null) return;

        System.out.println("开始处理，共 " + files.length + " 张图片...");

        for (File file : files) {
            Mat src = imread(file.getAbsolutePath());
            if (src.empty()) continue;

            // 1. 提取轮廓
            Mat hsv = new Mat();
            cvtColor(src, hsv, COLOR_BGR2HSV);
            Mat mask = new Mat();
            inRange(hsv, new Mat(new Scalar(10, 200, 200, 0)),
                    new Mat(new Scalar(25, 255, 255, 0)), mask);

            MatVector contours = new MatVector();
            findContours(mask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

            Mat target = null;
            double maxArea = 0;
            for (long i = 0; i < contours.size(); i++) {
                double area = contourArea(contours.get(i));
                if (area > maxArea) {
                    maxArea = area;
                    target = contours.get(i);
                }
            }

            if (target == null || maxArea < 20) {
                System.out.println("未检测到有效轮廓，跳过：" + file.getName());
                continue;
            }

            // 2. 使用 fitLine 计算轮廓主方向
            double[] lineResult = fitLineToContourOptimized(target);
            if (lineResult == null) {
                System.out.println("拟合失败，跳过：" + file.getName());
                continue;
            }
            double angle = lineResult[0];
            double cx = lineResult[1];
            double cy = lineResult[2];
            double vx = lineResult[3];
            double vy = lineResult[4];

            // 3. 绘制结果
            Mat debug = src.clone();
            drawContours(debug, new MatVector(target), -1, new Scalar(0, 255, 0, 0), 1, LINE_8, null, 0, null);

            Point centerP = new Point((int) cx, (int) cy);
            Point tipP = new Point((int) (cx + vx * 25), (int) (cy + vy * 25));
            line(debug, centerP, tipP, new Scalar(255, 0, 0, 0), 2, LINE_AA, 0);
            circle(debug, centerP, 2, new Scalar(0, 0, 255, 0), -1, LINE_AA, 0);

            // 4. 保存
            String newName = (int) angle + "_" + file.getName().split("_")[1];
            imwrite(valPcaPath + "\\" + newName, debug);
        }
        System.out.println("处理完成！结果已保存至 validation_pca");
    }

    /**
     * 使用 fitLine 拟合轮廓主方向
     * 完全基于 ptr() 和 FloatPointer，无任何 get() 调用
     */
    public static double[] fitLineToContour(Mat contour) {
        int totalPoints = contour.rows();
        if (totalPoints < 5) return null;

        // 1. 构建点矩阵 (N×2, CV_32F)
        Mat points = new Mat(totalPoints, 2, CV_32F);
        FloatPointer fp = new FloatPointer(points.data());

        for (int i = 0; i < totalPoints; i++) {
            BytePointer row = contour.ptr(i);
            float x = row.getFloat(0);
            float y = row.getFloat(4);
            fp.put(i * 2, x);
            fp.put(i * 2 + 1, y);
        }

        // 2. 调用 fitLine
        Mat line = new Mat(4, 1, CV_32F); // 输出：vx, vy, x0, y0
        fitLine(points, line, DIST_L2, 0, 0.01, 0.01);

        // 3. 读取拟合结果
        FloatPointer linePtr = new FloatPointer(line.data());
        float vx = linePtr.get(0);
        float vy = linePtr.get(1);
        float x0 = linePtr.get(2);
        float y0 = linePtr.get(3);

        // 4. 计算角度
        double angle = Math.toDegrees(Math.atan2(vy, vx));

        // 5. 为了区分箭头头部方向，需要判断向量指向箭头的哪一端
        // 方法：判断轮廓点投影到方向向量上的分布
        double sumProj = 0.0;
        for (int i = 0; i < totalPoints; i++) {
            double x = fp.get(i * 2);
            double y = fp.get(i * 2 + 1);
            double dx = x - x0;
            double dy = y - y0;
            sumProj += dx * vx + dy * vy;
        }

        // 如果投影和为负，则方向向量反向
        if (sumProj < 0) {
            vx = -vx;
            vy = -vy;
            angle = Math.toDegrees(Math.atan2(vy, vx));
        }

        // 6. 质心作为参考点（也可以直接用 x0, y0）
        Moments m = moments(contour);
        double cx = m.m10() / m.m00();
        double cy = m.m01() / m.m00();

        return new double[]{angle, cx, cy, vx, vy};
    }

    /**
     * 优化版箭头方向计算
     * 说明：
     * 1. 保留原有轮廓查找逻辑，不改 inRange 和 findContours。
     * 2. 使用质心 + fitLine + 凸包尖端判断箭头方向，避免投影和方法的不稳定。
     * 3. 修复轮廓点读取方式，保证类型安全。
     *
     * @param contour 输入轮廓 Mat
     * @return double[]{angle, cx, cy, vx, vy} 角度 0~360°, 质心坐标, 方向向量
     */
    public static double[] fitLineToContourOptimized(Mat contour) {
        int n = contour.rows();
        if (n < 5) return null;

        // 1. 构建点矩阵 (CV_32F)，保证类型安全
        Mat points = new Mat(n, 2, CV_32F);
        for (int i = 0; i < n; i++) {
            int x = contour.ptr(i).getInt(0); // CV_32S 类型
            int y = contour.ptr(i).getInt(4);
            points.ptr(i).putFloat(0, x);
            points.ptr(i).putFloat(4, y);
        }

        // 2. 拟合主方向
        Mat line = new Mat(4, 1, CV_32F);
        fitLine(points, line, DIST_L2, 0, 0.01, 0.01);
        FloatPointer ptr = new FloatPointer(line.data());
        double vx = ptr.get(0);
        double vy = ptr.get(1);
        double x0 = ptr.get(2);
        double y0 = ptr.get(3);

        // 3. 计算质心
        Moments m = moments(contour);
        double cx = m.m10() / m.m00();
        double cy = m.m01() / m.m00();

        // 4. 找凸包尖端
        Mat hull = new Mat();
        convexHull(contour, hull);
        Point tip = null;
        double maxDist = 0;
        for (int i = 0; i < hull.rows(); i++) {
            int hx = hull.ptr(i).getInt(0);
            int hy = hull.ptr(i).getInt(4);
            double dist = Math.hypot(hx - cx, hy - cy);
            if (dist > maxDist) {
                maxDist = dist;
                tip = new Point(hx, hy);
            }
        }

        // 5. 修正方向向量指向尖端
        if (tip != null) {
            double dx = tip.x() - cx;
            double dy = tip.y() - cy;
            double dot = dx * vx + dy * vy;
            if (dot < 0) {
                vx = -vx;
                vy = -vy;
            }
        }

        // 6. 计算角度
        double angle = Math.toDegrees(Math.atan2(vy, vx));
        if (angle < 0) angle += 360.0;

        return new double[]{angle, cx, cy, vx, vy};
    }
}