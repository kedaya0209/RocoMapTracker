package com.luoke.macher;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_UNCHANGED;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

@Slf4j
public class ImageBytesLoader {

    /**
     * 加载图片并转换为 BGRA 格式的字节数组
     * BGRA 顺序：Blue, Green, Red, Alpha (透明度)
     */
    public static byte[] loadImageToBgraBytes(String imagePath) {
        // 1. 读取原始图片 (保持原样读取)
        try (Mat src = imread(imagePath, IMREAD_UNCHANGED)) {
            if (src.empty()) {
                log.error("无法加载图片，请检查路径: {}", imagePath);
                return null;
            }

            // 2. 统一转换为 BGRA (4通道)
            try (Mat bgraMat = new Mat()) {
                int type = src.type();

                // 根据输入图片的通道数进行转换
                if (src.channels() == 3) {
                    cvtColor(src, bgraMat, COLOR_BGR2BGRA);
                } else if (src.channels() == 1) {
                    cvtColor(src, bgraMat, COLOR_GRAY2BGRA);
                } else if (src.channels() == 4) {
                    src.copyTo(bgraMat);
                } else {
                    log.warn("未知通道数 ({}), 尝试直接转换...", src.channels());
                    cvtColor(src, bgraMat, COLOR_BGR2BGRA);
                }

                // 3. 计算字节大小并提取
                int totalBytes = bgraMat.rows() * bgraMat.cols() * (int) bgraMat.elemSize();
                byte[] bytes = new byte[totalBytes];

                // 直接从原生内存拷贝到 Java byte 数组
                bgraMat.data().get(bytes);

                log.debug("图片加载成功: {}, 字节数: {}", imagePath, totalBytes);
                return bytes;
            }
        } catch (Exception e) {
            log.error("处理图片字节流时出错", e);
            return null;
        }
    }

    /**
     * 获取图片高度
     */
    public static int getImageHeight(String imagePath) {
        try (Mat src = imread(imagePath, IMREAD_UNCHANGED)) {
            return src.empty() ? 0 : src.rows();
        }
    }

    /**
     * 获取图片宽度
     */
    public static int getImageWidth(String imagePath) {
        try (Mat src = imread(imagePath, IMREAD_UNCHANGED)) {
            return src.empty() ? 0 : src.cols();
        }
    }
}