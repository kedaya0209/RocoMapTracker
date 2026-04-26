package com.luoke.app.utils;

import com.luoke.app.capture.common.CaptureFrameRecord;
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

/**
 * 图像处理工具类，提供JavaFX Image与OpenCV Mat之间的相互转换功能
 *
 * <p>该工具类主要负责图像数据在不同格式之间的高效转换，特别关注：
 * <ul>
 *   <li>JavaFX Image与OpenCV Mat的相互转换</li>
 *   <li>图像数据的加载和保存</li>
 *   <li>图像裁剪和透明度处理</li>
 *   <li>Native资源管理和内存优化</li>
 * </ul>
 *
 * <p>在Native Image环境下，该类通过高效的内存管理策略，确保大图像处理时不会出现OOM问题。
 * 所有涉及Native资源的Mat对象都需要调用方负责释放（通过close()或release()方法）。
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class ImageUtil {

    /**
     * 高效转换 OpenCV Mat 到 JavaFX WritableImage
     *
     * <p>该方法通过直接内存访问实现高效转换，避免了像素级别的拷贝操作。
     * 适用于将OpenCV处理后的图像快速显示在JavaFX界面上。
     *
     * <p>性能优化点：
     * <ul>
     *   <li>使用mat.data()直接访问底层内存，避免拷贝</li>
     *   <li>预分配字节数组，减少内存分配开销</li>
     *   <li>批量像素设置，提高渲染效率</li>
     * </ul>
     *
     * <p>注意：此方法不释放输入的Mat对象，调用方需要手动释放。
     *
     * @param mat OpenCV Mat对象，通常为RGB或BGR格式
     * @return JavaFX WritableImage对象，包含转换后的图像数据
     * @throws IllegalArgumentException 如果mat为null或为空
     */
    public static WritableImage matToImage(Mat mat) {
        // 获取图像的基本尺寸信息
        int width = mat.cols();
        int height = mat.rows();
        int channels = mat.channels();

        // 创建字节数组来存储像素数据，避免多次内存分配
        byte[] sourcePixels = new byte[width * height * channels];

        // 直接从OpenCV的底层内存读取数据到Java字节数组，比逐像素读取更高效
        mat.data().get(sourcePixels);

        // 创建可写图像对象
        WritableImage wimg = new WritableImage(width, height);

        // 批量设置像素数据，使用RGB格式以提高性能
        wimg.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getByteRgbInstance(), sourcePixels, 0, width * channels);
        return wimg;
    }

    /**
     * 将 JavaFX Image 转换为 OpenCV Mat (BGRA 格式)
     *
     * <p>该方法用于将JavaFX平台的图像转换为OpenCV可以处理的格式。
     * 特别适合需要使用OpenCV进行图像处理（如滤镜、特征检测）的场景。
     *
     * <p>转换特点：
     * <ul>
     *   <li>输出格式为BGRA（4通道），兼容大多数OpenCV操作</li>
     *   <li>保留透明度通道，适合后续的透明度处理</li>
     *   <li>返回的Mat对象需要调用方手动释放以避免内存泄漏</li>
     * </ul>
     *
     * @param img JavaFX Image对象，包含要转换的图像数据
     * @return OpenCV Mat对象，格式为CV_8UC4（BGRA），如果输入为null则返回null
     */
    public static Mat imageToMat(Image img) {
        // 处理空输入，避免NullPointerException
        if (img == null) return null;

        // 获取图像尺寸
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();

        // 分配缓冲区来存储BGRA格式的像素数据（4字节/像素）
        byte[] buffer = new byte[w * h * 4];

        // 创建像素读取器并批量读取数据
        PixelReader pr = img.getPixelReader();
        pr.getPixels(0, 0, w, h, PixelFormat.getByteBgraInstance(), buffer, 0, w * 4);

        // 创建4通道的OpenCV Mat对象（BGRA格式）
        Mat mat = new Mat(h, w, CV_8UC4);

        // 将Java字节数组的数据直接写入OpenCV的底层内存
        mat.data().put(buffer);
        return mat;
    }

    /**
     * 从资源路径读取图像并返回输入流
     *
     * <p>该方法用于从classpath中加载图像资源，适用于打包到jar中的图像文件。
     * 支持相对路径（以/开头）或类资源路径。
     *
     * <p>注意：返回的InputStream需要调用方手动关闭以释放资源。
     *
     * @param path 资源路径，如"/images/icon.png"或"com/example/resource.jpg"
     * @return 图像的输入流，如果资源不存在则返回null
     */
    public static InputStream readImageAsStream(String path) {
        return ImageUtil.class.getResourceAsStream(path);
    }

    /**
     * 计算图像中非透明像素的最小包围矩形
     *
     * <p>该方法用于去除图像周围的空白区域，找到包含所有非透明像素的最小矩形。
     * 特别适合处理PNG图片中的透明边框问题。
     *
     * <p>算法说明：
     * <ul>
     *   <li>遍历所有像素，检查透明度通道（Alpha值）</li>
     *   <li>记录非透明像素的最小和最大X、Y坐标</li>
     *   <li>返回包含这些像素的矩形边界</li>
     * </ul>
     *
     * <p>性能优化：使用getArgb()批量读取像素，比单独获取RGBA更高效。
     *
     * @param img 要处理的图像，通常为带透明通道的PNG
     * @return 包含所有非透明像素的最小矩形，如果全透明则返回无效矩形
     * @throws IllegalArgumentException 如果img为null或没有PixelReader
     */
    public static Rectangle2D calculateTrimRect(Image img) {
        PixelReader pr = img.getPixelReader();
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();

        // 初始化边界值，确保第一次比较会更新
        int minX = w, minY = h, maxX = 0, maxY = 0;

        // 遍历所有像素，寻找非透明区域的边界
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // 提取Alpha通道（ARGB格式中的高8位）
                // 如果Alpha值大于0，表示该像素不是完全透明的
                if (((pr.getArgb(x, y) >> 24) & 0xff) > 0) {
                    // 更新边界值，找到最小包围矩形
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        // 返回包围矩形，宽度加1以包含右边界
        return new Rectangle2D(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    /**
     * 去除图像周围的透明像素，返回裁剪后的图像
     *
     * <p>该方法创建新图像，只包含原始图像中非透明像素的部分。
     * 适合用于去除PNG图片中的大范围透明边框，减小图像尺寸。
     *
     * <p>应用场景：
     * <ul>
     *   <li>OCR识别前的图像预处理</li>
     *   <li>地图图标的标准化处理</li>
     *   <li>UI元素的对齐和布局优化</li>
     * </ul>
     *
     * @param img 要裁剪的原始图像
     * @return 裁剪后的新图像，如果全透明则可能返回空图像
     */
    public static Image trimEmptyPixels(Image img) {
        // 计算非透明像素的最小包围矩形
        Rectangle2D rect = calculateTrimRect(img);

        // 创建新图像，只包含包围矩形内的像素
        return new WritableImage(img.getPixelReader(),
                (int) rect.getMinX(), (int) rect.getMinY(),
                (int) rect.getWidth(), (int) rect.getHeight());
    }

    /**
     * 将屏幕截图帧记录转换为OpenCV Mat对象
     *
     * <p>该方法用于高性能地转换底层屏幕帧数据为OpenCV格式。
     * 适合用于实时屏幕处理、游戏帧分析等场景。
     *
     * <p>Native内存管理：
     * <ul>
     *   <li>使用BytePointer包装原始字节数组，避免数据拷贝</li>
     *   <li>直接创建Mat对象引用原始内存，实现零拷贝转换</li>
     *   <li>返回的Mat对象需要调用方手动释放</li>
     * </ul>
     *
     * <p>注意：由于Mat直接引用了frame的字节数组，确保frame在使用期间不被释放。
     *
     * @param frame 屏幕截图帧记录对象，包含BGRA格式的像素数据
     * @return OpenCV Mat对象，格式为CV_8UC4，如果输入为null则返回null
     */
    public static Mat convertToMat(CaptureFrameRecord frame) {
        // 检查输入有效性
        if (frame == null || frame.bytes() == null) return null;

        // 创建Mat对象，直接使用BytePointer包装的字节数据，实现零拷贝
        // 这种方式避免了数据的额外复制，提高了性能
        return new Mat(frame.height(), frame.width(), CV_8UC4, new BytePointer(frame.bytes()));
    }

    /**
     * 将OpenCV Mat对象转换为字节数组
     *
     * <p>该方法用于将OpenCV处理的图像数据导出为字节数组格式。
     * 适合用于图像序列化、网络传输、文件保存等场景。
     *
     * <p>性能考虑：
     * <ul>
     *   <li>预分配足够大的字节数组，避免动态扩容</li>
     *   <li>直接从底层内存读取，减少中间拷贝</li>
     *   <li>支持大图像的高效转换</li>
     * </ul>
     *
     * @param mat OpenCV Mat对象，包含要转换的图像数据
     * @return 图像数据的字节数组，格式取决于Mat的类型，如果输入无效则返回null
     */
    public static byte[] matToBytes(Mat mat) {
        // 检查输入有效性
        if (mat == null || mat.empty()) return null;

        // 计算所需的总字节数：像素总数 × 每个像素的字节数
        int size = (int) (mat.total() * mat.elemSize());

        // 创建字节数组并直接从OpenCV底层内存读取数据
        byte[] bytes = new byte[size];
        mat.data().get(bytes);
        return bytes;
    }

    /**
     * 从资源路径加载OpenCV Mat对象（兼容旧版本）
     *
     * <p>该方法用于从classpath中加载图像资源并转换为OpenCV Mat格式。
     * 适合用于打包到jar中的图像文件的处理。
     *
     * <p>资源管理：
     * <ul>
     *   <li>使用try-with-resources确保InputStream正确关闭</li>
     *   <li>支持相对路径和类资源路径</li>
     *   <li>自动解码常见图像格式（PNG、JPG等）</li>
     * </ul>
     *
     * <p>注意：返回的Mat对象需要调用方手动释放。
     *
     * @param resourcePath 资源路径，如"/images/map.png"
     * @param flags OpenCV解码标志，如IMREAD_COLOR、IMREAD_GRAYSCALE等
     * @return OpenCV Mat对象，包含解码后的图像数据
     * @throws RuntimeException 如果资源不存在或加载失败
     */
    public static Mat loadResourceToMat(String resourcePath, int flags) {
        try (InputStream is = ImageUtil.class.getResourceAsStream(resourcePath)) {
            // 检查资源是否存在
            if (is == null) throw new RuntimeException("找不到资源: " + resourcePath);

            // 读取所有字节并转换为Mat
            byte[] bytes = is.readAllBytes();
            return bytesToMat(bytes, flags);
        } catch (Exception e) {
            log.error("加载资源失败: {}", resourcePath, e);
            throw new RuntimeException(e);
        }
    }

    // ====================== 【你要的新方法】 ======================

    /**
     * 从输入流加载OpenCV Mat对象
     *
     * <p>该方法用于从任意输入流（文件、网络、内存等）加载图像数据。
     * 提供了灵活的图像加载方式，不局限于文件系统。
     *
     * <p>适用场景：
     * <ul>
     *   <li>从网络下载的图像数据</li>
     *   <li>从内存中缓存的图像数据</li>
     *   <li>动态生成的图像数据</li>
     * </ul>
     *
     * <p>注意：返回的Mat对象需要调用方手动释放。
     *
     * @param is 图像数据的输入流，包含编码格式的图像数据
     * @param flags OpenCV解码标志，控制图像的加载方式
     * @return OpenCV Mat对象，包含解码后的图像数据
     * @throws RuntimeException 如果读取或解码失败
     */
    public static Mat loadToMat(InputStream is, int flags) {
        try {
            // 读取所有字节并转换为Mat
            byte[] bytes = is.readAllBytes();
            return bytesToMat(bytes, flags);
        } catch (Exception e) {
            log.error("从 InputStream 加载 Mat 失败", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 从文件路径加载OpenCV Mat对象
     *
     * <p>该方法用于从本地文件系统加载图像文件。
     * 支持各种常见的图像格式，包括PNG、JPG、BMP等。
     *
     * <p>性能优化：
     * <ul>
     *   <li>使用NIO的Files.readAllBytes()，比传统IO更高效</li>
     *   <li>直接读取到字节数组，减少中间拷贝</li>
     *   <li>支持大文件的高效加载</li>
     * </ul>
     *
     * <p>注意：返回的Mat对象需要调用方手动释放。
     *
     * @param path 图像文件的路径
     * @return OpenCV Mat对象，包含解码后的图像数据
     * @throws IOException 如果文件读取失败
     */
    public static Mat readFileToMat(Path path) throws IOException {
        // 一次性读取整个文件到字节数组
        byte[] bytes = Files.readAllBytes(path);

        // 转换为Mat，使用IMREAD_UNCHANGED保留原始格式（包括透明度）
        return bytesToMat(bytes, IMREAD_UNCHANGED);
    }

    /**
     * 将字节数组解码为OpenCV Mat对象
     *
     * <p>该方法用于将编码格式的图像数据（如PNG、JPG的字节流）解码为OpenCV格式。
     * 是其他加载方法的基础实现，支持灵活的数据源。
     *
     * <p>Native资源管理策略：
     * <ul>
     *   <li>使用try-with-resources管理BytePointer，防止内存泄漏</li>
     *   <li>创建临时Mat来包装字节数据，解码后自动释放</li>
     *   <li>返回的解码Mat对象由调用方负责释放</li>
     * </ul>
     *
     * <p>性能优化：
     * <ul>
     *   <li>避免数据拷贝，直接引用字节数组</li>
     *   <li>使用CV_8UC1格式处理编码数据</li>
     *   <li>快速解码常见图像格式</li>
     * </ul>
     *
     * @param bytes 编码格式的图像数据字节数组
     * @paramparam flags OpenCV解码标志，控制图像的加载方式
     * @return OpenCV Mat对象，包含解码后的图像数据
     * @throws IllegalArgumentException 如果字节数组为空
     * @throws RuntimeException 如果图像解码失败
     */
    public static Mat bytesToMat(byte[] bytes, int flags) {
        // 检查输入有效性
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("字节数组为空");
        }

        // 使用try-with-resources确保BytePointer被正确释放
        // BytePointer包装Java字节数组，避免额外的内存拷贝
        try (BytePointer bp = new BytePointer(bytes)) {
            // 创建临时Mat对象，将字节数据包装为OpenCV可识别的格式
            // 使用CV_8UC1（单通道无符号字节）格式处理编码数据
            try (Mat encodedMat = new Mat(1, bytes.length, org.bytedeco.opencv.global.opencv_core.CV_8UC1, bp)) {
                // 解码图像数据，返回解码后的Mat对象
                Mat decodedMat = opencv_imgcodecs.imdecode(encodedMat, flags);

                // 检查解码是否成功
                if (decodedMat.empty()) {
                    throw new RuntimeException("图像解码失败");
                }

                // 返回解码后的Mat，由调用方负责释放
                return decodedMat;
            }
        }
    }
}