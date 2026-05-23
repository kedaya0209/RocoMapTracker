package com.luoke.app;

import net.jcip.annotations.ThreadSafe;
import org.bytedeco.opencv.opencv_core.*;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.bytedeco.opencv.global.opencv_core.*;
import static org.bytedeco.opencv.global.opencv_imgproc.*;
import static org.bytedeco.opencv.global.opencv_imgcodecs.*;

/**
 * 箭头方向批量识别工具。
 *
 * <p>读取输入文件夹中所有 PNG，通过 HSV 颜色过滤 + 几何分析识别箭头方向，
 * 在原图上画方向延长线，输出到另一文件夹。</p>
 *
 * <hr>
 * <h3>算法思路（凸包最小内角 + 底边中点定向）</h3>
 *
 * <p>游戏小地图上的玩家箭头具有以下特征：</p>
 * <ul>
 *   <li>颜色固定为橙色（H 10~25, S 200~255, V 200~255）</li>
 *   <li>形状近似三角形（箭头尖端尖锐，尾部略宽）</li>
 *   <li>HSV 过滤后尾部可能分离出一个独立的小圆点（箭尾装饰）</li>
 * </ul>
 *
 * <p>算法流程：</p>
 *
 * <ol>
 *   <li><b>HSV 颜色过滤</b> — 在 HSV 空间用固定阈值提取箭头像素，生成二值掩码。
 *       阈值参照标定工具 DatasetGeneratorServer 的参数设定。</li>
 *
 *   <li><b>形态学去噪</b> — 用 3×3 矩形核对掩码做闭运算（MORPH_CLOSE），
 *       填充箭头内部的细小孔洞，使轮廓连续。</li>
 *
 *   <li><b>最大轮廓提取</b> — 用 findContours 查找所有外轮廓，
 *       按 contourArea 排序取最大的一个。
 *       这一步过滤掉尾部离群小圆点（它的面积远小于箭头主体）。</li>
 *
 *   <li><b>凸包构建</b> — 对最大轮廓计算凸包（convexHull, returnPoints=true），
 *       得到箭头的外接多边形顶点。凸包能有效消除轮廓上的锯齿噪声，
 *       同时保留箭头的整体几何形状。</li>
 *
 *   <li><b>凸包简化</b> — 如果凸包顶点数超过 5 个，用 approxPolyDP 做多边形逼近简化。
 *       采用 2% 周长的 epsilon，在保留形状的前提下减少冗余顶点。</li>
 *
 *   <li><b>最小内角顶点 = 箭头尖端</b> — 遍历凸包所有顶点，计算每个顶点的内角：
 *       <pre>{@code
 *       angle = acos( (e1·e2) / (|e1|·|e2|) )
 *       }</pre>
 *       其中 e1、e2 是从当前顶点指向相邻顶点的向量。
 *       箭头尖端的内角最小（通常 &lt; 45°），箭尾两个顶点的内角较大（通常 &gt; 90°）。</li>
 *
 *   <li><b>底边中点定向</b> — 找到尖端后，它在凸包上的两个相邻顶点构成"底边"。
 *       计算底边的中点坐标。箭头的指向 = 从底边中点指向尖端顶点：
 *       <pre>{@code
 *       dx = tipX - baseMidX
 *       dy = tipY - baseMidY
 *       angle = atan2(dy, dx)    // 归一化到 0~360°
 *       }</pre>
 *       使用底边中点而非重心作为定向参考点的原因：
 *       重心受尾部质量分布影响，如果尾部有小圆点或形状不对称，
 *       重心会偏移，导致方向计算偏差。
 *       底边中点是纯几何量，不受质量分布影响。</li>
 *
 *   <li><b>画延长线</b> — 以重心为起点（视觉上更自然），沿计算出的方向画一条
 *       长度为图像长边 80% 的红色直线，并在重心位置画一个蓝色圆点标记。</li>
 * </ol>
 *
 * <p>角度约定：0° = 正右，90° = 正下，180° = 正左，270° = 正上，
 * 与标准数学坐标系一致（atan2(y, x)）。</p>
 */
@ThreadSafe
@Slf4j
public class ArrowAngleDrawer {

    /** 数据集根目录 */
    private static final String BASE_PATH = "C:\\Users\\tangh\\Desktop\\dataset";
    /** 输入文件夹（存放待识别的 PNG） */
    private static final String INPUT_DIR = BASE_PATH + "\\result";
    /** 输出文件夹（存放画好延长线的 PNG） */
    private static final String OUTPUT_DIR = BASE_PATH + "\\output_angle";

    // ───────────────── HSV 阈值（箭头橙色） ─────────────────
    // Hue: 10~25（橙色范围，0~179）
    // Saturation: 200~255（高饱和度，过滤背景浅色）
    // Value: 200~255（中高亮度，捕获箭头渐变阴影）
    // 与 DatasetGeneratorServer / PCARecalibrator 保持一致
    private static final Scalar HSV_LOWER = new Scalar(10, 200, 200, 0);
    private static final Scalar HSV_UPPER = new Scalar(25, 255, 255, 0);

    public static void main(String[] args) throws Exception {
        Files.createDirectories(Paths.get(OUTPUT_DIR));

        File[] files = new File(INPUT_DIR).listFiles((dir, name) -> name.endsWith(".png"));
        if (files == null || files.length == 0) {
            log.info("未找到 PNG 文件: {}", INPUT_DIR);
            return;
        }

        log.info("开始处理，共 {} 张图片...", files.length);
        for (File file : files) {
            String name = file.getName();
            Mat src = imread(file.getAbsolutePath());
            if (src.empty()) {
                log.info("跳过（读取失败）: {}", name);
                continue;
            }

            double angle = drawArrowLine(src);

            String outPath = OUTPUT_DIR + "\\" + name;
            imwrite(outPath, src);
            log.info("{}: {}\u00b0", name, String.format("%.2f", angle));
        }
        log.info("处理完成，结果已输出到: {}", OUTPUT_DIR);
    }

    /**
     * 在单张图片上识别箭头方向并画延长线。
     *
     * <p>流程：HSV 过滤 → 找最大轮廓 → 凸包 → 最小内角顶点（尖端）
     * → 底边中点定向 → 画线。</p>
     *
     * @param src 输入 BGR 图像（会被修改，画上延长线和圆心标记）
     * @return 箭头方向角度 0~360°，失败返回 -1
     */
    static double drawArrowLine(Mat src) {
        // ── 1. HSV 颜色过滤 ──────────────────────────────────
        // 将 BGR 转换到 HSV，用固定阈值提取橙色箭头像素
        Mat hsv = new Mat();
        cvtColor(src, hsv, COLOR_BGR2HSV);

        Mat mask = new Mat();
        inRange(hsv, new Mat(HSV_LOWER), new Mat(HSV_UPPER), mask);

        // 闭运算：先膨胀后腐蚀，填充箭头内部的细小孔洞
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
        morphologyEx(mask, mask, MORPH_CLOSE, kernel);

        // ── 2. 找最大轮廓 ─────────────────────────────────────
        // 箭头尾部有时会分离出一个独立小圆点（箭尾装饰），
        // 只取最大轮廓可以自动过滤这种干扰
        MatVector contours = new MatVector();
        findContours(mask, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);
        if (contours.size() == 0) return -1;

        int maxIdx = 0;
        double maxArea = 0;
        for (long i = 0; i < contours.size(); i++) {
            double area = contourArea(contours.get(i));
            if (area > maxArea) {
                maxArea = area;
                maxIdx = (int) i;
            }
        }
        Mat contour = contours.get(maxIdx);
        if (maxArea < 20) return -1; // 面积太小，不可靠

        // ── 3. 凸包构建 ───────────────────────────────────────
        // 凸包能消除轮廓上的锯齿，保留整体几何形状
        Mat hullPts = new Mat();
        convexHull(contour, hullPts, false, true);
        int h = hullPts.rows();
        if (h < 3) return -1;

        // 顶点过多时用 approxPolyDP 简化
        // epsilon = 2% 周长，仅简化不改变基本形状
        if (h > 5) {
            Mat simplified = new Mat();
            approxPolyDP(hullPts, simplified, 0.02 * arcLength(contour, true), true);
            if (simplified.rows() >= 3) {
                hullPts = simplified;
                h = hullPts.rows();
            }
        }

        // ── 4. 最小内角顶点 = 箭头尖端 ────────────────────────
        // 遍历凸包所有顶点，计算每个顶点的内角：
        //   angle = acos( (e1·e2) / (|e1|·|e2|) )
        // 箭头尖端的内角最小（尖锐），箭尾顶点的内角较大（钝角）
        int tipIdx = 0;
        double minAngle = Double.MAX_VALUE;
        for (int i = 0; i < h; i++) {
            int prev = (i - 1 + h) % h;
            int next = (i + 1) % h;

            // 读取当前顶点坐标（CV_32SC2 格式：字节偏移 0=x, 4=y）
            double ax = hullPts.ptr(i).getInt(0);
            double ay = hullPts.ptr(i).getInt(4);

            // 两条边向量：前驱→当前 和 后继→当前
            double e1x = hullPts.ptr(prev).getInt(0) - ax;
            double e1y = hullPts.ptr(prev).getInt(4) - ay;
            double e2x = hullPts.ptr(next).getInt(0) - ax;
            double e2y = hullPts.ptr(next).getInt(4) - ay;

            double len1 = Math.sqrt(e1x * e1x + e1y * e1y);
            double len2 = Math.sqrt(e2x * e2x + e2y * e2y);
            if (len1 < 1 || len2 < 1) continue;

            // 余弦定理计算内角
            double dot = (e1x * e2x + e1y * e2y) / (len1 * len2);
            double angle = Math.acos(Math.max(-1, Math.min(1, dot)));
            if (angle < minAngle) {
                minAngle = angle;
                tipIdx = i;
            }
        }

        // ── 5. 底边中点定向 ───────────────────────────────────
        // 尖端在凸包上的两个相邻顶点构成"底边"。
        // 计算底边中点，方向 = 底边中点 → 尖端。
        // 使用底边中点而非重心作为参考点的原因：
        //   重心受尾部质量分布影响（如尾部小圆点），可能偏移；
        //   底边中点是纯几何量，不受质量分布影响，方向更准确。
        double tipX = hullPts.ptr(tipIdx).getInt(0);
        double tipY = hullPts.ptr(tipIdx).getInt(4);

        int prevIdx = (tipIdx - 1 + h) % h;
        int nextIdx = (tipIdx + 1) % h;
        double baseMX = (hullPts.ptr(prevIdx).getInt(0)
                       + hullPts.ptr(nextIdx).getInt(0)) / 2.0;
        double baseMY = (hullPts.ptr(prevIdx).getInt(4)
                       + hullPts.ptr(nextIdx).getInt(4)) / 2.0;

        double dx = tipX - baseMX;
        double dy = tipY - baseMY;
        double angleRad = Math.atan2(dy, dx);
        double angleDeg = Math.toDegrees(angleRad);
        if (angleDeg < 0) angleDeg += 360;

        // ── 6. 画延长线 ───────────────────────────────────────
        // 起点用重心（视觉上比底边更自然）
        Moments m = moments(contour);
        double cx = m.m10() / m.m00();
        double cy = m.m01() / m.m00();
        Point center = new Point((int) Math.round(cx), (int) Math.round(cy));

        double extLen = Math.max(src.cols(), src.rows()) * 0.8;
        Point extEnd = new Point(
                (int) Math.round(cx + extLen * Math.cos(angleRad)),
                (int) Math.round(cy + extLen * Math.sin(angleRad)));
        // 红色延长线 + 蓝色重心标记
        line(src, center, extEnd, new Scalar(0, 0, 255, 0), 2, LINE_AA, 0);
        circle(src, center, 5, new Scalar(255, 0, 0, 0), -1, 0, 0);

        return angleDeg;
    }
}
