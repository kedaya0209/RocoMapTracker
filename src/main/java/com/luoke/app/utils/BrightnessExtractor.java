package com.luoke.app.utils;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Size;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 对B1，B2大地图抠图
 */
public class BrightnessExtractor {

    public static void extractorAndSave(String path, String savePth) {
        // 1. 读取输入图片
        // 注意：请修改为你本地的实际路径
        Mat src = imread(path);

        if (src.empty()) {
            System.err.println("错误：无法读取图片，请检查路径是否正确！");
            return;
        }
        // 2. 将原图转换为灰度图
        // 抠亮部的本质是筛选像素值，灰度图将 RGB 三通道压缩为 0-255 的亮度值，处理起来更简单。
        Mat gray = new Mat();
        cvtColor(src, gray, COLOR_BGR2GRAY);

        // 3. 对灰度图进行二值化（阈值过滤）
        // 参数说明：
        // gray: 输入图
        // mask: 输出掩码
        // 200: 阈值。像素值大于 200 的会被设为 255（白色），小于的设为 0（黑色）。
        //      针对 1.png 的高亮字，200 比较合适；如果是 image_167f65.jpg 这种较暗的图，可以尝试调低到 150。
        // 255: 最大值
        // THRESH_BINARY: 超过阈值变白，否则变黑
        Mat mask = new Mat();
        threshold(gray, mask, 50, 255, THRESH_BINARY);
        // 4. (可选) 形态学处理 - 降噪
        // 如果扣出来的东西太碎，可以使用膨胀(dilate)让亮部更饱满，或者开运算(morphologyEx)去除噪点。
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
        dilate(mask, mask, kernel);
        // 5. 使用掩码抠图
        // 这一步是将原图（src）和掩码（mask）进行“与”运算。
        // 只有掩码为白色的地方，原图的颜色才会被保留；掩码黑色的地方，输出也为黑色。
        Mat res = new Mat();
        opencv_core.bitwise_and(src, src, res, mask);
        // 7. 如果你想保存带透明通道的图（RGBA），实现起来稍复杂：
        saveTransparent(src, mask, savePth);

        src.release();
        gray.release();
        mask.release();
    }

    /**
     * 将抠出的内容保存为透明背景的 PNG
     */
    private static void saveTransparent(Mat src, Mat mask, String outputPath) {
        // 创建一个 4 通道的图像 (BGRA)
        Mat bgra = new Mat();
        cvtColor(src, bgra, COLOR_BGR2BGRA);

        // 将掩码作为第 4 个通道（Alpha 通道）
        // 这样掩码黑色的地方就是透明的，白色的地方是不透明的
        MatVector channels = new MatVector();
        opencv_core.split(bgra, channels);
        channels.put(3, mask); // 替换 Alpha 通道
        opencv_core.merge(channels, bgra);

        imwrite(outputPath, bgra);
        bgra.release();
    }
}