package com.luoke.app.macher.map;

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

/**
 * 匹配结果工具类
 *
 * <p>提供匹配结果的可视化和数据导出功能，主要用于调试和结果验证。</p>
 * <p>该类中的所有方法都是静态方法，可以直接调用。</p>
 *
 * <h3>核心功能：</h3>
 * <ul>
 *   <li>坐标日志输出：打印匹配到的4个角点坐标</li>
 *   <li>像素数据保存：将BGRA/BGR字节数组保存为图片文件</li>
 *   <li>匹配框绘制：在原图上绘制匹配到的矩形区域（私有方法）</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 1. 打印匹配坐标
 * double[][] corners = matcher.match(smallImage);
 * MatchingResultUtil.logCorners(corners);
 *
 * // 2. 保存像素数据为图片
 * byte[] bgraPixels = ...; // BGRA格式字节数组
 * MatchingResultUtil.saveRawPixelsToFile(bgraPixels, width, height, "C:/debug/match_result.png");
 * }</pre>
 *
 * <h3>Native资源管理：</h3>
 * <ul>
 *   <li>所有Native资源（Mat、BytePointer等）都使用try-with-resources管理</li>
 *   <li>确保资源在方法结束时自动释放，避免内存泄漏</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class MatchingResultUtil {

    /**
     * 红色常量（用于绘制匹配框）
     * <p>格式：BGRA，值为 (0, 0, 255, 0) 表示红色</p>
     */
    private static final Scalar COLOR_RED = new Scalar(0, 0, 255, 0); // BGRA 中的红色

    /**
     * 线条粗细（用于绘制匹配框）
     * <p>较大的值可以使匹配框更明显，便于查看</p>
     */
    private static final int LINE_THICKNESS = 400;

    /**
     * 打印匹配坐标到日志
     *
     * <p>将匹配到的4个角点坐标格式化输出到日志，方便调试和验证匹配结果。</p>
     *
     * <h3>输出格式：</h3>
     * <pre>
     * 坐标点 0: [x=100, y=200]
     * 坐标点 1: [x=100, y=400]
     * 坐标点 2: [x=300, y=400]
     * 坐标点 3: [x=300, y=200]
     * </pre>
     *
     * <h3>使用场景：</h3>
     * <ul>
     *   <li>匹配算法调试：验证匹配结果是否正确</li>
     *   <li>测试验证：检查匹配精度</li>
     *   <li>问题排查：定位匹配失败的原因</li>
     * </ul>
     *
     * @param corners 匹配到的4个角点坐标数组，格式为 double[4][2]
     *               如果为null或长度小于4，则不做任何操作
     */
    public static void logCorners(double[][] corners) {
        // 前置置检查：确保坐标数组有效
        if (corners == null || corners.length < 4) return;

        // 遍历4个坐标点并输出到日志
        for (int i = 0; i < corners.length; i++) {
            log.info("坐标点 {}: [x={}, y={}]", i, (int) corners[i][0], (int) corners[i][1]);
        }
    }

    /**
     * 在图像上绘制匹配框（私有方法）
     *
     * <p>使用红色线条在图像上绘制匹配到的矩形区域，用于可视化匹配结果。</p>
     * <p>该方法为私有方法，仅供内部使用。</p>
     *
     * <h3>绘制逻辑：</h3>
     * <ul>
     *   <li>按顺序连接4个坐标点，形成闭合矩形</li>
     *   <li>使用红色线条，粗细为LINE_THICKNESS</li>
     *   <li>直接修改原图，请确保原图可写</li>
     * </ul>
     *
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>该方法直接修改输入的Mat对象</li>
     *   <li>如果需要在原图上绘制，请先克隆一份</li>
     *   <li>使用后无需释放points，由系统自动管理</li>
     * </ul>
     *
     * @param img 目标图像Mat对象（会被修改）
     * @param corners 匹配到的4个角点坐标数组
     */
    private static void drawLinesOnMat(Mat img, double[][] corners) {
        // 前置检查：确保坐标数组有效
        if (corners == null || corners.length < 4) return;

        // 遍历4条边，绘制红色线条
        for (int i = 0; i < 4; i++) {
            // 当前点和下一个点的坐标（形成闭合矩形）
            Point p1 = new Point((int) corners[i][0], (int) corners[i][1]);
            Point p2 = new Point((int) corners[(i + 1) % 4][0], (int) corners[(i + 1) % 4][1]);

            // 在图像上绘制红色线条
            line(img, p1, p2, COLOR_RED, LINE_THICKNESS, LINE_8, 0);
        }
    }

    /**
     * 将 BGRA/BGR 原始像素字节数组保存为图片文件
     *
     * <p>该方法适用于将屏幕截图或视频帧等原始像素数据保存为图片文件，用于调试和验证。</p>
     *
     * <h3>格式说明：</h3>
     * <ul>
     *   <li>支持BGRA（4字节/像素）和BGR（3字节/像素）两种格式</li>
     *   <li>自动根据数组长度判断格式：length == width * height * 4 为BGRA，否则为BGR</li>
     *   <li>数据数据顺序：从左到右、从上到下</li>
     * </ul>
     *
     * <h3>支持的文件格式：</h3>
     * <ul>
     *   <li>JPG (.jpg, .jpeg)</li>
     *   <li>PNG (.png)</li>
     *   <li>BMP (.bmp)</li>
     *   <li>TIFF (.tiff, .tif)</li>
     * </ul>
     *
     * <h3>Native资源管理：</h3>
     * <ul>
     *   <li>使用try-with-resources管理BytePointer和Mat对象</li>
     *   <li>确保Native资源在方法结束时自动释放</li>
     *   <li>避免内存泄漏泄漏</li>
     * </ul>
     *
     * <h3>使用示例：</h3>
     * <pre>{@code
     * // 屏幕截图（BGRA格式）
     * byte[] pixels = screenCapture();
     * MatchingResultUtil.saveRawPixelsToFile(pixels, 1920, 1080, "C:/debug/screenshot.png");
     *
     * // 视频帧（BGR格式）
     * byte[] framePixels = videoFrameCapture();
     * MatchingResultUtil.saveRawPixelsToFile(framePixels, 640, 480, "C:/debug/frame.jpg");
     * }</pre>
     *
     * <h3>注意事项：</h3>
     * <ul>
     *   <li>确保输出路径存在且可写</li>
     *   <li>文件后缀名决定保存格式</li>
     *   <li>JPG格式会压缩图像，PNG格式无损</li>
     * </ul>
     *
     * @param bytes 像素字节数组（BGRA或BGR格式）
     *              BGRA格式：length = width * height * 4
     *              BGR格式：length = width * height * 3
     * @param width 图片宽度（像素）
     * @param height 图片高度（像素）
     * @param outputPath 保存路径（包含文件名和后缀）
     *                  例如："C:/test/result.jpg" 或 "C:/debug/screenshot.png"
     *                  后缀名决定保存格式（.jpg、.png、.bmp等）
     */
    public static void saveRawPixelsToFile(byte[] bytes, int width, int height, String outputPath) {
        // 前置检查：确保字节数组有效
        if (bytes == null || bytes.length == 0) {
            log.error("字节数组为空，无法保存");
            return;
        }

        // 根据数组长度判断像素格式
        // BGRA格式：每个像素4字节
        // BGR格式：每个像素3字节
        int type = (bytes.length == width * height * 4) ? CV_8UC4 : CV_8UC3;

        // 使用try-with-resources管理Native资源，确保自动释放
        try (BytePointer ptr = new BytePointer(bytes);  // 包装Java数组为Native指针（不复制数据）
             Mat mat = new Mat(height, width, type, ptr)) {  // 创建Mat对象，直接引用Native指针

            // 将Mat对象保存为图片文件
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
