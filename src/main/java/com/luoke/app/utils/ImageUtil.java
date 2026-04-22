package com.luoke.app.utils;

import com.luoke.app.capture.CaptureFrameRecord;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_UNCHANGED;
import static org.opencv.core.CvType.CV_8UC1;

@Slf4j
public class ImageUtil {
    // 高效转换 Mat 到 JavaFX Image
    public static WritableImage matToImage(Mat mat) {
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();
        byte[] sourcePixels = new byte[width * height * channels];
        mat.data().get(sourcePixels);

        WritableImage wimg = new WritableImage(width, height);
        // 如果是 BGR (OpenCV默认) 格式，转为 JavaFX 的 BGRA 或类似
        wimg.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getByteRgbInstance(), sourcePixels, 0, width * channels);
        return wimg;
    }

    /**
     * 将 JavaFX Image 转换为 OpenCV Mat (BGRA 格式)
     * 用于初始化玩家图标时识别其初始朝向
     */
    public static Mat imageToMat(Image img) {
        if (img == null) return null;

        int w = (int) img.getWidth();
        int h = (int) img.getHeight();

        // 1. 准备一个字节数组来接收像素数据
        // JavaFX 默认读取格式为 ByteBgraPre (BGRA 顺序，每个像素 4 字节)
        byte[] buffer = new byte[w * h * 4];
        PixelReader pr = img.getPixelReader();

        // 2. 将像素数据读入 buffer
        pr.getPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), buffer, 0, w * 4);

        // 3. 创建 Mat。注意：CV_8UC4 对应 4 通道 (BGRA)
        Mat mat = new Mat(h, w, CV_8UC4);
        mat.data().put(buffer);

        return mat;
    }

    public static InputStream readImageAsStream(String path) {
        return ImageUtil.class.getResourceAsStream(path);
    }

    public static Rectangle2D calculateTrimRect(Image img) {
        PixelReader pr = img.getPixelReader();
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int minX = w, minY = h, maxX = 0, maxY = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((pr.getArgb(x, y) >> 24) & 0xff) > 0) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return new Rectangle2D(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static Image trimEmptyPixels(Image img) {
        Rectangle2D rect = calculateTrimRect(img);
        return new WritableImage(img.getPixelReader(), (int) rect.getMinX(), (int) rect.getMinY(), (int) rect.getWidth(), (int) rect.getHeight());
    }

    /**
     * 将截图帧转换为 OpenCV 的 Mat 对象
     * 假设 CaptureFrameRecord 内部存储的是原始 BGR 或 RGBA 字节流
     */
    public static Mat convertToMat(CaptureFrameRecord frame) {
        if (frame == null || frame.bytes() == null) return null;

        // 根据你的截图插件，如果是 4 通道 (BGRA/RGBA) 使用 CV_8UC4
        // 如果是 3 通道 (BGR) 使用 CV_8UC3
        Mat mat = new Mat(frame.height(), frame.width(), CV_8UC4, new BytePointer(frame.bytes()));

        // 这里的 Mat 只是对 byte[] 的封装，不涉及大块内存拷贝，效率很高
        return mat;
    }

    /**
     * 将 OpenCV 的 Mat 转换为字节数组，供 Matcher 使用
     */
    public static byte[] matToBytes(Mat mat) {
        if (mat == null || mat.empty()) return null;
        int size = (int) (mat.total() * mat.elemSize());
        byte[] bytes = new byte[size];
        mat.data().get(bytes);
        return bytes;
    }

    /**
     * 核心方法：从 ClassPath 读取图片并直接转为 OpenCV Mat
     * 无论在 IDE 还是打包成 EXE，此方法都有效
     *
     * @param resourcePath 资源路径，例如 "/assets/maps/large_map.png"
     * @param flags        读取模式，例如 opencv_imgcodecs.IMREAD_GRAYSCALE
     */
    public static Mat loadResourceToMat(String resourcePath, int flags) {
        // 1. 使用流读取，规避 "URI is not hierarchical" 报错
        try (InputStream is = ImageUtil.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new RuntimeException("找不到资源文件: " + resourcePath);
            }

            // 2. 将流读入内存字节数组
            byte[] bytes = is.readAllBytes();

            // 3. 将字节数组解码为 Mat 像素矩阵
            try (BytePointer bp = new BytePointer(bytes)) {
                try (Mat encodedMat = new Mat(1, bytes.length, CV_8UC1, bp)) {
                    Mat decodedMat = opencv_imgcodecs.imdecode(encodedMat, flags);
                    if (decodedMat.empty()) {
                        throw new RuntimeException("解码失败: " + resourcePath);
                    }
                    return decodedMat;
                }
            }
        } catch (IOException e) {
            log.error("读取资源异常: {}", resourcePath, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 将文件路径读取并转为 Mat 对象
     * 适用于本地文件系统路径
     */
    public static Mat readFileToMat(Path path) throws IOException {
        // 1. 使用 Files.readAllBytes 读取原始字节 (编码后的数据)
        byte[] bytes = Files.readAllBytes(path);

        // 2. 将字节数组转为 Mat 并解码
        return bytesToMat(bytes, IMREAD_UNCHANGED);
    }

    /**
     * 核心转换逻辑：将字节数组解码为 Mat
     */
    public static Mat bytesToMat(byte[] bytes, int flags) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("字节数组为空，无法转换 Mat");
        }

        // 1. 将 byte[] 包装进 BytePointer
        // 注意：这里使用 BytePointer 是因为 JavaCPP 的 Mat 构造函数需要它来映射内存
        try (BytePointer bp = new BytePointer(bytes)) {

            // 2. 创建一个一维的“容器” Mat，存放原始编码数据
            // 参数说明：行数1, 列数bytes.length, 类型CV_8UC1
            try (Mat encodedMat = new Mat(1, bytes.length, org.bytedeco.opencv.global.opencv_core.CV_8UC1, bp)) {

                // 3. 使用 imdecode 进行解码（将 PNG/JPG 解压为像素矩阵）
                Mat decodedMat = opencv_imgcodecs.imdecode(encodedMat, flags);

                if (decodedMat.empty()) {
                    throw new RuntimeException("Mat 解码失败，请检查数据格式是否为有效的图像编码");
                }

                return decodedMat;
            }
        }
    }
}