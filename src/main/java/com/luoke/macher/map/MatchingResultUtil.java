package com.luoke.macher.map;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Scalar;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC3;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.LINE_8;
import static org.bytedeco.opencv.global.opencv_imgproc.line;

@Slf4j
public class MatchingResultUtil {

    private static final Scalar COLOR_RED = new Scalar(0, 0, 255, 0); // BGRA 中的红色
    private static final int LINE_THICKNESS = 400;

    /**
     * 1. 打印坐标日志
     */
    public static void logCorners(double[][] corners) {
        if (corners == null || corners.length < 4) return;
        for (int i = 0; i < corners.length; i++) {
            log.info("坐标点 {}: [x={}, y={}]", i, (int) corners[i][0], (int) corners[i][1]);
        }
    }

    /**
     * 核心私有方法：执行具体的画线操作
     */
    private static void drawLinesOnMat(Mat img, double[][] corners) {
        if (corners == null || corners.length < 4) return;
        for (int i = 0; i < 4; i++) {
            Point p1 = new Point((int) corners[i][0], (int) corners[i][1]);
            Point p2 = new Point((int) corners[(i + 1) % 4][0], (int) corners[(i + 1) % 4][1]);
            line(img, p1, p2, COLOR_RED, LINE_THICKNESS, LINE_8, 0);
        }
    }

    /**
     * 将 BGRA 原始像素字节数组保存为图片文件
     * * @param bytes  像素字节数组
     *
     * @param width      图片宽度
     * @param height     图片高度
     * @param outputPath 保存路径 (例如 "C:/test/result.jpg")
     */
    public static void saveRawPixelsToFile(byte[] bytes, int width, int height, String outputPath) {
        if (bytes == null || bytes.length == 0) {
            log.error("字节数组为空，无法保存");
            return;
        }
        // 假设是 BGRA 格式，每个像素 4 字节
        // 如果是 BGR 格式，请将 CV_8UC4 改为 CV_8UC3
        int type = (bytes.length == width * height * 4) ? CV_8UC4 : CV_8UC3;

        try (BytePointer ptr = new BytePointer(bytes);
             Mat mat = new Mat(height, width, type, ptr)) {

            boolean success = imwrite(outputPath, mat);
            if (success) {
                log.info("图片已成功保存至: {}", outputPath);
            } else {
                log.error("图片保存失败，请检查路径权限或后缀是否正确");
            }
        } catch (Exception e) {
            log.error("保存像素数据时发生异常", e);
        }
    }

}