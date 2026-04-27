package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * 🗺️ 亮度提取器 - DJL OpenCV 物理内存回收版
 */
@Slf4j
public class BrightnessExtractor {

    static {
        // 确保原生库已加载（通常由 DJL 自动完成，显式触发更稳健）
        // ai.djl.opencv.OpenCVImageFactory.getInstance();
    }

    /**
     * 提取高亮区域并保存为透明 PNG
     */
    public static void extractorAndSave(String path, String savePath) {
        // 1. 读取原图
        Mat src = Imgcodecs.imread(path);
        if (src.empty()) {
            log.error("错误：无法读取图片，请检查路径: {}", path);
            return;
        }

        // 定义需要手动释放的 Mat
        Mat gray = new Mat();
        Mat mask = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3));

        try {
            // 2. 转换灰度
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

            // 3. 二值化提取亮部 (阈值 50)
            Imgproc.threshold(gray, mask, 50, 255, Imgproc.THRESH_BINARY);

            // 4. 膨胀处理
            Imgproc.dilate(mask, mask, kernel);

            // 5. 执行透明化保存
            saveTransparent(src, mask, savePath);
            log.info("高亮提取完成，已保存至: {}", savePath);

        } catch (Exception e) {
            log.error("亮度提取过程发生异常", e);
        } finally {
            // 🚀 手动释放所有 Native 内存
            src.release();
            gray.release();
            mask.release();
            kernel.release();
        }
    }

    /**
     * 将 BGR 图像结合 Mask 保存为 BGRA (透明)
     */
    private static void saveTransparent(Mat src, Mat mask, String outputPath) {
        Mat bgra = new Mat();
        List<Mat> channels = new ArrayList<>();

        try {
            // 转换为 4 通道 (BGRA)
            Imgproc.cvtColor(src, bgra, Imgproc.COLOR_BGR2BGRA);

            // 分离通道
            Core.split(bgra, channels);

            // 💡 替换 Alpha 通道 (索引为 3)
            // 注意：OpenCV 的 List 方式需要 set 替换原来的通道 Mat
            Mat oldAlpha = channels.set(3, mask.clone()); // 使用 clone 避免引用污染
            oldAlpha.release(); // 释放被替换掉的旧 Alpha 通道

            // 合并通道
            Core.merge(channels, bgra);

            // 保存结果
            if (!Imgcodecs.imwrite(outputPath, bgra)) {
                log.error("透明 PNG 保存失败: {}", outputPath);
            }
        } finally {
            // 🚀 释放列表中的中间通道 Mat
            for (Mat m : channels) {
                m.release();
            }
            bgra.release();
        }
    }
}