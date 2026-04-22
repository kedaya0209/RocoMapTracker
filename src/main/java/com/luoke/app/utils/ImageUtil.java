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
        wimg.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getByteRgbInstance(), sourcePixels, 0, width * channels);
        return wimg;
    }

    /**
     * 将 JavaFX Image 转换为 OpenCV Mat (BGRA 格式)
     */
    public static Mat imageToMat(Image img) {
        if (img == null) return null;

        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        byte[] buffer = new byte[w * h * 4];
        PixelReader pr = img.getPixelReader();
        pr.getPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), buffer, 0, w * 4);

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
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return new Rectangle2D(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    public static Image trimEmptyPixels(Image img) {
        Rectangle2D rect = calculateTrimRect(img);
        return new WritableImage(img.getPixelReader(),
                (int) rect.getMinX(), (int) rect.getMinY(),
                (int) rect.getWidth(), (int) rect.getHeight());
    }

    public static Mat convertToMat(CaptureFrameRecord frame) {
        if (frame == null || frame.bytes() == null) return null;
        return new Mat(frame.height(), frame.width(), CV_8UC4, new BytePointer(frame.bytes()));
    }

    public static byte[] matToBytes(Mat mat) {
        if (mat == null || mat.empty()) return null;
        int size = (int) (mat.total() * mat.elemSize());
        byte[] bytes = new byte[size];
        mat.data().get(bytes);
        return bytes;
    }

    /**
     * 从资源路径加载 Mat（旧方法，兼容）
     */
    public static Mat loadResourceToMat(String resourcePath, int flags) {
        try (InputStream is = ImageUtil.class.getResourceAsStream(resourcePath)) {
            if (is == null) throw new RuntimeException("找不到资源: " + resourcePath);
            byte[] bytes = is.readAllBytes();
            return bytesToMat(bytes, flags);
        } catch (Exception e) {
            log.error("加载资源失败: {}", resourcePath, e);
            throw new RuntimeException(e);
        }
    }

    // ====================== 【你要的新方法】 ======================
    public static Mat loadToMat(InputStream is, int flags) {
        try {
            byte[] bytes = is.readAllBytes();
            return bytesToMat(bytes, flags);
        } catch (Exception e) {
            log.error("从 InputStream 加载 Mat 失败", e);
            throw new RuntimeException(e);
        }
    }

    public static Mat readFileToMat(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        return bytesToMat(bytes, IMREAD_UNCHANGED);
    }

    public static Mat bytesToMat(byte[] bytes, int flags) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("字节数组为空");
        }

        try (BytePointer bp = new BytePointer(bytes)) {
            try (Mat encodedMat = new Mat(1, bytes.length, org.bytedeco.opencv.global.opencv_core.CV_8UC1, bp)) {
                Mat decodedMat = opencv_imgcodecs.imdecode(encodedMat, flags);
                if (decodedMat.empty()) {
                    throw new RuntimeException("图像解码失败");
                }
                return decodedMat;
            }
        }
    }
}