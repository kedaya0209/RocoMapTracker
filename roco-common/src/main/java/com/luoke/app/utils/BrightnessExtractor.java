package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Size;

/**
 * 亮度提取器 - JavaCPP OpenCV 版本
 */
@Slf4j
public class BrightnessExtractor {

    /**
     * 提取高亮区域并保存为透明 PNG
     */
    public static void extractorAndSave(String path, String savePath) {
        try (PointerScope scope = new PointerScope()) {
            Mat src = opencv_imgcodecs.imread(path);
            if (src.empty()) {
                log.error("错误：无法读取图片，请检查路径: {}", path);
                return;
            }

            Mat gray = new Mat();
            Mat mask = new Mat();
            Mat kernel = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, new Size(3, 3));

            opencv_imgproc.cvtColor(src, gray, opencv_imgproc.COLOR_BGR2GRAY);
            opencv_imgproc.threshold(gray, mask, 50, 255, opencv_imgproc.THRESH_BINARY);
            opencv_imgproc.dilate(mask, mask, kernel);
            saveTransparent(src, mask, savePath);
            log.info("高亮提取完成，已保存至: {}", savePath);
        } catch (Exception e) {
            log.error("亮度提取过程发生异常", e);
        }
    }

    /**
     * 将 BGR 图像结合 Mask 保存为 BGRA (透明)
     */
    private static void saveTransparent(Mat src, Mat mask, String outputPath) {
        try (PointerScope scope = new PointerScope()) {
            Mat bgra = new Mat();
            opencv_imgproc.cvtColor(src, bgra, opencv_imgproc.COLOR_BGR2BGRA);

            MatVector channels = new MatVector();
            opencv_core.split(bgra, channels);

            // 替换 Alpha 通道 (索引为 3)
            Mat newAlpha = mask.clone();
            channels.put(3, newAlpha);

            opencv_core.merge(channels, bgra);

            if (!opencv_imgcodecs.imwrite(outputPath, bgra)) {
                log.error("透明 PNG 保存失败: {}", outputPath);
            }
        }
    }
}
