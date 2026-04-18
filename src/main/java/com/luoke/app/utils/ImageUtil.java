package com.luoke.app.utils;

import com.luoke.capture.CaptureFrameRecord;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.InputStream;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;

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

        // 如果你的 Matcher 接受的是编码后的图片（如 PNG/JPG）
        /*
        BytePointer buf = new BytePointer();
        imencode(".png", mat, buf);
        byte[] bytes = new byte[(int)buf.limit()];
        buf.get(bytes);
        return bytes;
        */

        // 如果你的 Matcher 接受的是原始 BGR 像素流（通常性能更高）
        int size = (int) (mat.total() * mat.elemSize());
        byte[] bytes = new byte[size];
        mat.data().get(bytes);
        return bytes;
    }
}