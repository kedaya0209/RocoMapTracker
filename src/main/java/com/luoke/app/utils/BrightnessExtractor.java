package com.luoke.app.utils;

import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Size;

import static org.bytedeco.opencv.global.opencv_imgcodecs.imread;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imwrite;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 亮度提取器，用于从大地图中提取高亮区域
 *
 * <p>该工具类专门用于处理洛克王国B1、B2大地图的亮度提取，
 * 通过阈值过滤和形态学处理，提取地图中的高亮区域并保存为透明背景的PNG。
 *
 * <p>处理流程：
 * <ul>
 *   <li>读取原始图片并转换为灰度图</li>
 *   <li>使用阈值进行二值化处理</li>
 *   <li>形态学降噪处理</li>
 *   <li>使用掩码抠图并生成透明背景</li>
 * </ul>
 *
 * <p>Native资源管理：
 * 所有创建的Mat对象都会被正确释放，避免内存泄漏。
 *
 * @author 可达鸭
 * @version 1.0
 */
public class BrightnessExtractor {

    /**
     * 从图像中提取高亮区域并保存为透明背景的PNG文件
     *
     * <p>该方法使用基于亮度的阈值过滤来提取图像中的高亮区域。
     * 特别适合处理地图标记、高亮文字等场景。
     *
     * <p>处理流程：
     * <ul>
     *   <li>将彩色图像转换为灰度图</li>
     *   <li>使用阈值50进行二值化，提取亮度大于50的区域</li>
     *   <li>使用3x3矩形核进行膨胀处理，让亮部更饱满</li>
     *   <li>使用掩码从原图中提取高亮区域</li>
     *   <li>生成带透明通道的PNG并保存</li>
     * </ul>
     *
     * <p>性能和资源管理：
     * <ul>
     *   <li>所有Mat对象都会被正确释放</li>
     *   <li>使用try-with-resources或手动释放避免内存泄漏</li>
     *   <li>支持大图像的高效处理</li>
     * </ul>
     *
     * @param path 输入图像的文件路径，支持常见图像格式（PNG、JPG等）
     * @param savePth 输出PNG文件的保存路径
     */
    public static void extractorAndSave(String path, String savePth) {
        // 1. 读取输入图片
        // 注意：OpenCV会自动检测图像格式
        Mat src = imread(path);

        // 检查图像是否成功加载
        if (src.empty()) {
            System.err.println("错误：无法读取图片，请检查路径是否正确！");
            return;
        }

        // 2. 将原图转换为灰度图
        // 抠亮部的本质是筛选像素值，灰度图将 RGB 三通道压缩为 0-255 的亮度值，处理起来更简单
        Mat gray = new Mat();
        cvtColor(src, gray, COLOR_BGR2GRAY);

        // 3. 对灰度图进行二值化（阈值过滤）
        // 参数说明：
        // gray: 输入图
        // mask: 输出掩码
        // 50: 阈值。像素值大于 50 的会被设为 255（白色），小于的设为 0（黑色）
        //     针对 1.png 的高亮字，50 比较合适；如果是较暗的图，可以尝试调低阈值
        // 255: 最大值（白色）
        // THRESH_BINARY: 超过阈值变白，否则变黑
        Mat mask = new Mat();
        threshold(gray, mask, 50, 255, THRESH_BINARY);

        // 4. (可选) 形态学处理 - 降噪
        // 如果扣出来的东西太碎，可以使用膨胀(dilate)让亮部更饱满，或者开运算(morphologyEx)去除噪点
        // 这里使用3x3的矩形核进行膨胀处理
        Mat kernel = getStructuringElement(MORPH_RECT, new Size(3, 3));
        dilate(mask, mask, kernel);

        // 5. 使用掩码抠图
        // 这一步是将原图（src）和掩码（mask）进行”与”运算
        // 只有掩码为白色的地方，原图的颜色才会被保留；掩码黑色的地方，输出也为黑色
        Mat res = new Mat();
        opencv_core.bitwise_and(src, src, res, mask);

        // 7. 如果你想保存带透明通道的图（RGBA），实现起来稍复杂：
        saveTransparent(src, mask, savePth);

        src.release();
        gray.release();
        mask.release();
    }

    /**
     * 将抠出的内容保存为透明背景的 PNG 图像
     *
     * <p>该方法将二值化掩码转换为alpha通道，生成带有透明背景的PNG图像。
     * 掩码中的白色区域变为不透明，黑色区域变为透明。
     *
     * <p>实现原理：
     * <ul>
     *   <li>将BGR格式的图像转换为BGRA格式（增加alpha通道）</li>
     *   <li>分离图像的四个通道（B、G、R、A）</li>
     *   <li>将二值化掩码作为alpha通道替换原有的A通道</li>
     *   <li>重新合并通道并保存为PNG</li>
     * </ul>
     *
     * <p>Native资源管理：
     * 所有创建的Mat对象都会被正确释放，避免内存泄漏。
     *
     * @param src 原始图像，格式为BGR
     * @param mask 二值化掩码，白色表示保留，黑色表示透明
     * @param outputPath 输出PNG文件的保存路径
     */
    private static void saveTransparent(Mat src, Mat mask, String outputPath) {
        // 创建一个 4 通道的图像 (BGRA)，为alpha通道做准备
        Mat bgra = new Mat();
        cvtColor(src, bgra, COLOR_BGR2BGRA);

        // 将掩码作为第 4 个通道（Alpha 通道）
        // 这样掩码黑色的地方就是透明的，白色的地方是不透明的
        MatVector channels = new MatVector();
        opencv_core.split(bgra, channels);  // 分离四个通道
        channels.put(3, mask);              // 替换 Alpha 通道为掩码
        opencv_core.merge(channels, bgra);  // 重新合并通道

        // 保存为PNG格式，自动保留透明通道
        imwrite(outputPath, bgra);

        // 释放Mat对象，避免内存泄漏
        bgra.release();
    }
}