package com.luoke.app.utils;

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
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

/**
 * 多分辨率 mmap 地图缓存：全分辨率、1/2、1/4 三级 raw 文件，
 * 按视口实时裁剪 MappedByteBuffer 生成 viewport 尺寸 WritableImage，
 * 永不超过 GPU 纹理上限（4096），极低内存/显存占用。
 */
@Slf4j
public class MultiResMapCache {

    private static final int MAX_LEVELS = 3;

    private final MappedByteBuffer[] buffers = new MappedByteBuffer[MAX_LEVELS];
    private final int[] widths = new int[MAX_LEVELS];
    private final int[] heights = new int[MAX_LEVELS];
    private boolean initialized;

    private MultiResMapCache() {
    }

    public static MultiResMapCache getInstance() {
        return Holder.INSTANCE;
    }

    private static String levelPath(String basePath, int level) {
        return switch (level) {
            case 0 -> basePath;
            case 1 -> basePath.replace(".raw", "_half.raw");
            case 2 -> basePath.replace(".raw", "_quarter.raw");
            default -> throw new IllegalArgumentException("无效级别: " + level);
        };
    }

    private static MappedByteBuffer writeRaw(String path, int w, int h, byte[] pixels) throws IOException {
        File file = ResourceUtils.getExternalFile(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (FileChannel fc = FileChannel.open(file.toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            header.putInt(w);
            header.putInt(h);
            header.flip();
            fc.write(header);
            fc.write(ByteBuffer.wrap(pixels));
        }

        // mmap 刚写入的文件
        try (FileChannel fc = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            long size = (long) w * h * 4;
            return fc.map(FileChannel.MapMode.READ_ONLY, 8, size);
        }
    }

    /**
     * 2×2 块均值降采样，BGRA 4 通道独立平均
     */
    private static byte[] downsample2x(byte[] src, int srcW, int srcH) {
        int dstW = (srcW + 1) / 2;
        int dstH = (srcH + 1) / 2;
        byte[] dst = new byte[dstW * dstH * 4];
        for (int y = 0; y < dstH; y++) {
            for (int x = 0; x < dstW; x++) {
                int sumB = 0, sumG = 0, sumR = 0, sumA = 0, count = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = x * 2 + dx;
                        int sy = y * 2 + dy;
                        if (sx < srcW && sy < srcH) {
                            int idx = (sy * srcW + sx) * 4;
                            sumB += src[idx] & 0xFF;
                            sumG += src[idx + 1] & 0xFF;
                            sumR += src[idx + 2] & 0xFF;
                            sumA += src[idx + 3] & 0xFF;
                            count++;
                        }
                    }
                }
                int di = (y * dstW + x) * 4;
                dst[di] = (byte) (sumB / count);
                dst[di + 1] = (byte) (sumG / count);
                dst[di + 2] = (byte) (sumR / count);
                dst[di + 3] = (byte) (sumA / count);
            }
        }
        return dst;
    }

    // ==================== 初始化 ====================

    /**
     * 按缩放比例选择最合适的 LOD 级别
     */
    private static int selectLevel(double scale) {
        if (scale >= 0.5) return 0;  // 1x — 放大/1:1
        if (scale >= 0.25) return 1; // 0.5x — 中距
        return 2;                     // 0.25x — 远距
    }

    public int getFullWidth() {
        return widths[0];
    }

    public int getFullHeight() {
        return heights[0];
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 确保三级 raw 就绪：
     * 1. 若全分辨率 raw 不存在 → PNG 解码生成三级 raw
     * 2. 若全分辨率存在但 1/2、1/4 缺失 → 从全分辨率生成补全
     * 3. mmap 全部三级
     */
    public void ensureLevels(InputStream pngStream, String basePath) throws IOException {
        File fullFile = ResourceUtils.getExternalFile(basePath);
        if (!fullFile.exists()) {
            // 全分辨率不存在，从 PNG 一次性生成三级
            decodeAndCreateAllLevels(pngStream, basePath);
            initialized = true;
            return;
        }

        // 全分辨率存在，检查并补全缺失级别
        boolean needHalf = !ResourceUtils.getExternalFile(levelPath(basePath, 1)).exists();
        boolean needQuarter = !ResourceUtils.getExternalFile(levelPath(basePath, 2)).exists();

        if (needHalf || needQuarter) {
            log.info("补全缺失的多分辨率 raw: half={}, quarter={}", needHalf, needQuarter);
            byte[] fullBytes = readFullRaw(basePath);
            int fullW = widths[0];
            int fullH = heights[0];

            if (needHalf) {
                int hw = (fullW + 1) / 2, hh = (fullH + 1) / 2;
                byte[] half = downsample2x(fullBytes, fullW, fullH);
                buffers[1] = writeRaw(levelPath(basePath, 1), hw, hh, half);
                widths[1] = hw;
                heights[1] = hh;
                // 继续生成 1/4
                int qw = (hw + 1) / 2, qh = (hh + 1) / 2;
                byte[] quarter = downsample2x(half, hw, hh);
                buffers[2] = writeRaw(levelPath(basePath, 2), qw, qh, quarter);
                widths[2] = qw;
                heights[2] = qh;
            } else {
                // 半级已存在，从半级生成四级
                byte[] halfBytes = readRawBytes(levelPath(basePath, 1), widths[1], heights[1]);
                int qw = (widths[1] + 1) / 2, qh = (heights[1] + 1) / 2;
                byte[] quarter = downsample2x(halfBytes, widths[1], heights[1]);
                buffers[2] = writeRaw(levelPath(basePath, 2), qw, qh, quarter);
                widths[2] = qw;
                heights[2] = qh;
            }
        }

        // mmap 所有级别（已有 buffer 的跳过）
        for (int i = 0; i < MAX_LEVELS; i++) {
            if (buffers[i] == null) {
                mmapLevel(basePath, i);
            }
        }
        initialized = true;
    }

    private void mmapLevel(String basePath, int i) throws IOException {
        String path = levelPath(basePath, i);
        try (FileChannel fc = FileChannel.open(
                ResourceUtils.getExternalFile(path).toPath(), StandardOpenOption.READ)) {
            ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
            fc.read(header);
            header.flip();
            widths[i] = header.getInt();
            heights[i] = header.getInt();
            long size = (long) widths[i] * heights[i] * 4;
            buffers[i] = fc.map(FileChannel.MapMode.READ_ONLY, 8, size);
        }
        log.info("mmap 加载: {} ({}x{})", path, widths[i], heights[i]);
    }

    /**
     * 读取全分辨率 raw 的全部字节
     */
    private byte[] readFullRaw(String basePath) throws IOException {
        mmapLevel(basePath, 0); // 确保 buffers[0] 就绪
        int w = widths[0], h = heights[0];
        return readRawBytes(levelPath(basePath, 0), w, h);
    }

    private byte[] readRawBytes(String path, int w, int h) throws IOException {
        MappedByteBuffer buf;
        try (FileChannel fc = FileChannel.open(
                ResourceUtils.getExternalFile(path).toPath(), StandardOpenOption.READ)) {
            fc.position(8); // 跳过 header
            buf = fc.map(FileChannel.MapMode.READ_ONLY, 8, (long) w * h * 4);
        }
        byte[] bytes = new byte[w * h * 4];
        buf.get(bytes);
        return bytes;
    }

    // ==================== 视口裁剪 ====================

    private void decodeAndCreateAllLevels(InputStream pngStream, String basePath) throws IOException {
        byte[] bytes = pngStream.readAllBytes();

        // 1. OpenCV 解码 PNG
        byte[] fullPixelBytes;
        int fullW, fullH;
        try (PointerScope scope = new PointerScope()) {
            Mat rawData = new Mat(bytes.length, 1, opencv_core.CV_8UC1);
            rawData.data().put(bytes);
            Mat image = opencv_imgcodecs.imdecode(rawData, opencv_imgcodecs.IMREAD_UNCHANGED);
            if (image.empty()) throw new IOException("OpenCV 无法解码 PNG");

            fullW = image.cols();
            fullH = image.rows();

            Mat bgra;
            if (image.channels() == 3) {
                bgra = new Mat();
                opencv_imgproc.cvtColor(image, bgra, opencv_imgproc.COLOR_BGR2BGRA);
            } else {
                bgra = image;
            }
            fullPixelBytes = new byte[fullW * fullH * 4];
            bgra.data().get(fullPixelBytes);
        }

        // 2. 写全分辨率 raw
        widths[0] = fullW;
        heights[0] = fullH;
        buffers[0] = writeRaw(levelPath(basePath, 0), fullW, fullH, fullPixelBytes);

        // 3. 生成并写 1/2 raw (2x2 均值)
        int halfW = (fullW + 1) / 2;
        int halfH = (fullH + 1) / 2;
        byte[] halfBytes = downsample2x(fullPixelBytes, fullW, fullH);
        widths[1] = halfW;
        heights[1] = halfH;
        buffers[1] = writeRaw(levelPath(basePath, 1), halfW, halfH, halfBytes);

        // 4. 生成并写 1/4 raw
        int quarterW = (halfW + 1) / 2;
        int quarterH = (halfH + 1) / 2;
        byte[] quarterBytes = downsample2x(halfBytes, halfW, halfH);
        widths[2] = quarterW;
        heights[2] = quarterH;
        buffers[2] = writeRaw(levelPath(basePath, 2), quarterW, quarterH, quarterBytes);

        log.info("三级 raw 缓存创建完成: {}x{} / {}x{} / {}x{}",
                fullW, fullH, halfW, halfH, quarterW, quarterH);
    }

    /**
     * 根据当前视口（offset/scale/viewSize）从最匹配的分辨率级中裁剪出 viewport 尺寸的 WritableImage。
     * 仅当 bgDirty 时调用。
     */
    public WritableImage cropViewport(double offsetX, double offsetY, double scale,
                                      double mapW, double mapH,
                                      double viewW, double viewH) {
        if (!initialized) return null;

        int level = selectLevel(scale);
        double levelScale = 1.0 / (1 << level); // 1.0, 0.5, 0.25
        MappedByteBuffer buf = buffers[level];
        int rawW = widths[level];
        int rawH = heights[level];

        // 视口覆盖的世界区域
        double worldX0 = -offsetX / scale;
        double worldY0 = -offsetY / scale;
        double worldW = viewW / scale;
        double worldH = viewH / scale;

        // 映射到 raw 坐标系（当前 level）
        double rawX0 = worldX0 * levelScale;
        double rawY0 = worldY0 * levelScale;
        double rawCropW = worldW * levelScale;
        double rawCropH = worldH * levelScale;

        int outW = (int) viewW;
        int outH = (int) viewH;
        WritableImage result = new WritableImage(outW, outH);
        var writer = result.getPixelWriter();

        double xStep = rawCropW / outW;
        double yStep = rawCropH / outH;
        int[] rowBuf = new int[outW];
        int maxByteIdx = rawW * rawH * 4 - 4;

        for (int y = 0; y < outH; y++) {
            for (int x = 0; x < outW; x++) {
                int rawCol = (int) (rawX0 + x * xStep);
                int rawRow = (int) (rawY0 + y * yStep);

                // 越界裁剪为透明
                if (rawCol < 0 || rawCol >= rawW || rawRow < 0 || rawRow >= rawH) {
                    rowBuf[x] = 0;
                    continue;
                }

                int idx = (rawRow * rawW + rawCol) * 4;
                if (idx > maxByteIdx) {
                    rowBuf[x] = 0;
                    continue;
                }

                // BGRA 字节 → ARGB int
                int b = buf.get(idx) & 0xFF;
                int g = buf.get(idx + 1) & 0xFF;
                int r = buf.get(idx + 2) & 0xFF;
                int a = buf.get(idx + 3) & 0xFF;
                rowBuf[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            writer.setPixels(0, y, outW, 1,
                    PixelFormat.getIntArgbPreInstance(), rowBuf, 0, outW);
        }
        return result;
    }

    // ==================== PixelBuffer 直接填充 ====================

    /**
     * 将 mmap 中对应视口的像素直接写入 int[] 的指定矩形区域（ARGB 格式）。
     * 使用 LITTLE_ENDIAN ByteBuffer.getInt() 一次读取 4 字节，减少 JNI 调用。
     */
    public void fillPixels(int[] pixels, int bufWidth,
                           int dstX, int dstY, int fillW, int fillH,
                           double offsetX, double offsetY, double scale,
                           int mapW, int mapH) {
        if (!initialized) return;

        int level = selectLevel(scale);
        double levelScale = 1.0 / (1 << level);
        MappedByteBuffer buf = buffers[level];
        int rawW = widths[level];
        int rawH = heights[level];

        // LITTLE_ENDIAN 视图：BGRA 字节 → getInt() 直接返回 ARGB int
        java.nio.ByteBuffer leBuf = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);

        double step = levelScale / scale;
        double rawX0 = -offsetX * step;
        double rawY0 = -offsetY * step;

        for (int y = 0; y < fillH; y++) {
            int screenY = dstY + y;
            int rawRow = (int) (rawY0 + screenY * step);
            int dstRowStart = screenY * bufWidth + dstX;

            if (rawRow < 0 || rawRow >= rawH) {
                for (int x = 0; x < fillW; x++) {
                    pixels[dstRowStart + x] = 0;
                }
                continue;
            }

            int rawRowBase = rawRow * rawW;

            for (int x = 0; x < fillW; x++) {
                int screenX = dstX + x;
                int rawCol = (int) (rawX0 + screenX * step);

                if (rawCol < 0 || rawCol >= rawW) {
                    pixels[dstRowStart + x] = 0;
                    continue;
                }

                // 一次 getInt() 读取 BGRA 四字节 → ARGB int（little-endian）
                pixels[dstRowStart + x] = leBuf.getInt((rawRowBase + rawCol) * 4);
            }
        }
    }

    // ==================== 单例 ====================

    private static class Holder {
        private static final MultiResMapCache INSTANCE = new MultiResMapCache();
    }
}
