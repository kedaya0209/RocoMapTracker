package com.luoke.app.utils;

import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
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
     * 首次加载：ImageIO 解码 PNG → 写出 raw → mmap 加载。
     * ImageIO 的 BufferedImage 在方法返回后即可被 GC，不会持久占用堆。
     */
    private static MappedImage decodeAndSaveCache(InputStream pngStream, File cacheFile) throws IOException {
        BufferedImage bi = ImageIO.read(pngStream);
        if (bi == null) {
            throw new IOException("无法解码 PNG 图片");
        }
        int width = bi.getWidth();
        int height = bi.getHeight();

        // 提取 ARGB 像素（非 premultiplied）
        int[] pixels = new int[width * height];
        bi.getRGB(0, 0, width, height, pixels, 0, width);

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

            ByteBuffer pixelBuf = ByteBuffer.allocate(width * height * 4).order(ByteOrder.nativeOrder());
            IntBuffer intBuf = pixelBuf.asIntBuffer();
            intBuf.put(pixels);
            fc.write(pixelBuf);
        }

        // 释放 BufferedImage 引用，建议 GC
        bi = null;
        System.gc();

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
