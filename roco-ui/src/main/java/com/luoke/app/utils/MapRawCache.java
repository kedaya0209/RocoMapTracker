package com.luoke.app.utils;

import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.StandardOpenOption;

/**
 * 大地图 mmap 缓存工具 — 首帧解码 PNG 存为 .raw，后续直接内存映射，避免 256MB 堆占用。
 * 缓存模式与 SIFT .feat 一致：有则读，无则初始化。
 */
@Slf4j
public class MapRawCache {

    private MapRawCache() {
    }

    /**
     * 加载或创建 mmap 缓存，返回 JavaFX 可用的 Image。
     *
     * @param pngStream PNG 输入流（仅缓存不存在时使用，用完即关）
     * @param cachePath 缓存文件路径（如 "map_G.png.raw"）
     * @return PixelBuffer 包装的 WritableImage + 元数据
     */
    public static MappedImage loadOrCreate(InputStream pngStream, String cachePath) throws IOException {
        File cacheFile = ResourceUtils.getExternalFile(cachePath);
        if (cacheFile.exists()) {
            log.info("从 mmap 缓存加载地图: {}", cacheFile.getAbsolutePath());
            return loadFromCache(cacheFile);
        }
        log.info("首次加载，解码 PNG 并创建 mmap 缓存: {}", cacheFile.getAbsolutePath());
        return decodeAndSaveCache(pngStream, cacheFile);
    }

    /**
     * 从 .raw 文件 mmap 加载。
     * 格式: [int width][int height][int... pixels] (native byte order)
     */
    private static MappedImage loadFromCache(File cacheFile) throws IOException {
        FileChannel fc = FileChannel.open(cacheFile.toPath(), StandardOpenOption.READ);

        // 读取 8 字节头 (width, height)
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
        fc.read(header);
        header.flip();
        int width = header.getInt();
        int height = header.getInt();

        // mmap 像素数据区域（read-only，不占用堆内存）
        long pixelDataSize = (long) width * height * 4;
        MappedByteBuffer mappedBuf = fc.map(MapMode.READ_ONLY, 8, pixelDataSize);
        fc.close();

        IntBuffer intBuf = mappedBuf.asIntBuffer();
        PixelBuffer<IntBuffer> pixelBuf = new PixelBuffer<>(width, height, intBuf,
                PixelFormat.getIntArgbPreInstance());
        WritableImage image = new WritableImage(pixelBuf);

        log.info("mmap 地图加载完成: {}x{}, {} bytes off-heap", width, height, pixelDataSize);
        return new MappedImage(image, width, height, mappedBuf);
    }

    /**
     * 首次加载：OpenCV 解码 PNG → 写出 raw → mmap 加载。
     * 使用 OpenCV imdecode 替代 ImageIO，避免 Native Image 下 ServiceLoader 找不到 PNG reader 的问题。
     *
     * 像素格式: BGRA 字节序 (小端) 与 ARGB int (0xAARRGGBB) 的内存布局完全一致：
     *   ARGB int → 内存 [B][G][R][A] = BGRA Mat 内存 [B][G][R][A]
     * 因此可以直接将 BGRA Mat 的数据写入 .raw，读出时作为 IntBuffer 使用。
     */
    private static MappedImage decodeAndSaveCache(InputStream pngStream, File cacheFile) throws IOException {
        byte[] bytes = pngStream.readAllBytes();

        int width, height;
        ByteBuffer pixelData;

        try (PointerScope scope = new PointerScope()) {
            // OpenCV imdecode 解码 PNG (与 SiftMapMatcher 中解码地图图片的方式一致)
            Mat rawData = new Mat(bytes.length, 1, opencv_core.CV_8UC1);
            rawData.data().put(bytes);
            Mat image = opencv_imgcodecs.imdecode(rawData, opencv_imgcodecs.IMREAD_UNCHANGED);

            if (image.empty()) {
                throw new IOException("OpenCV 无法解码 PNG 图片");
            }

            width = image.cols();
            height = image.rows();
            int channels = image.channels();

            // 统一转换为 BGRA
            Mat bgra;
            if (channels == 3) {
                bgra = new Mat();
                opencv_imgproc.cvtColor(image, bgra, opencv_imgproc.COLOR_BGR2BGRA);
            } else if (channels == 4) {
                bgra = image;
            } else {
                throw new IOException("不支持的通道数: " + channels);
            }

            // 提取像素数据：BGRA 内存布局 = ARGB int 的小端表示
            int dataSize = width * height * 4;
            byte[] pixelBytes = new byte[dataSize];
            bgra.data().get(pixelBytes);
            pixelData = ByteBuffer.wrap(pixelBytes).order(ByteOrder.nativeOrder());
        }

        // 确保父目录存在
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }

        // 写入 .raw: 8 字节头 + 像素数据
        try (FileChannel fc = FileChannel.open(cacheFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            header.putInt(width);
            header.putInt(height);
            header.flip();
            fc.write(header);

            pixelData.position(0);
            fc.write(pixelData);
        }

        log.info("raw 缓存写入完成: {} ({}x{})", cacheFile.getAbsolutePath(), width, height);
        return loadFromCache(cacheFile);
    }

    /**
     * mmap 包装的结果对象，持有映射缓冲区的强引用。
     */
    public record MappedImage(WritableImage image, int width, int height,
                              MappedByteBuffer mappedBuffer) {
    }
}
