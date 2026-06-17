package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import ar.com.hjg.pngj.IImageLine;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/**
 * 地图资源加载器 — 从 classpath 加载地图图片并流式转换为灰度像素。
 *
 * <p>使用 PNGJ 逐行流式读取，避免 BufferedImage 全尺寸解码（8192×8192 ~256MB）。
 * 峰值内存约为一行的缓冲区（~32KB）。
 */
@ThreadSafe
@Slf4j
public final class MapImageLoader {

    /** 每个子图的标准高度（像素），用于多子图 MAP_DATA 协议 */
    public static final int SUB_IMAGE_HEIGHT = 8192;

    private MapImageLoader() {
    }

    /**
     * 获取地图图片尺寸（不解码像素）。
     */
    public static ImageInfo loadImage() throws Exception {
        String mapPath = ResourceConfigContext.getSiftMap();
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            PngReader reader = null;
            try {
                reader = new PngReader(is);
                return new ImageInfo(reader.imgInfo.cols, reader.imgInfo.rows);
            } finally {
                if (reader != null) reader.end();
            }
        }
    }

    /**
     * 根据总高度计算子图数量（每子图 8192px）。
     */
    public static int getSubImageCount(int totalHeight) {
        return totalHeight / SUB_IMAGE_HEIGHT;
    }

    /**
     * 流式写入协议头 + 灰度像素到 OutputStream（逐行，无全图缓冲）。
     *
     * <p>协议格式：[w(4B)][h(4B)][pixelsLen(4B)][gray8...]
     */
    public static void writeStreaming(OutputStream out) throws IOException {
        String mapPath = ResourceConfigContext.getSiftMap();
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            PngReader reader = null;
            try {
                reader = new PngReader(is);
                int w = reader.imgInfo.cols;
                int h = reader.imgInfo.rows;

                ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
                header.putInt(w).putInt(h).putInt(w * h);
                out.write(header.array());

                writeGrayRows(reader, w, h, out);
            } finally {
                if (reader != null) reader.end();
            }
        }
    }

    /**
     * 流式写入多子图协议头 + 灰度像素。
     *
     * <p>多子图格式：[subImageCount(4B)][w(4B)][totalH(4B)][subH_0(4B)]...[subH_{N-1}(4B)][pixelsLen(4B)][gray8...]
     *
     * @param out 目标输出流（通常是 Socket OutputStream）
     */
    public static void writeStreamingMulti(OutputStream out) throws IOException {
        String mapPath = ResourceConfigContext.getSiftMap();
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            PngReader reader = null;
            try {
                reader = new PngReader(is);
                int w = reader.imgInfo.cols;
                int h = reader.imgInfo.rows;
                int subImageCount = h / SUB_IMAGE_HEIGHT;

                ByteBuffer header = ByteBuffer.allocate(16 + subImageCount * 4).order(ByteOrder.BIG_ENDIAN);
                header.putInt(subImageCount);
                header.putInt(w);
                header.putInt(h);
                for (int i = 0; i < subImageCount; i++) {
                    header.putInt(SUB_IMAGE_HEIGHT);
                }
                header.putInt(w * h);
                out.write(header.array());

                writeGrayRows(reader, w, h, out);
            } finally {
                if (reader != null) reader.end();
            }
        }
    }

    /**
     * 从 MultiMap 元数据流式写入多子图协议数据。
     * 逐个加载子图 PNG → PngReader 逐行灰度转换 → 流式输出，避免加载完整 8192x49152 组合图。
     */
    public static void writeStreamingMultiFromMetadata(CompositeMapMetadata metadata, OutputStream out) throws IOException {
        int w = metadata.width();
        int totalH = metadata.totalHeight();
        List<CompositeMapMetadata.SubImageInfo> subs = metadata.subImages();

        // 写入多子图协议头: [subImageCount(4B)][w(4B)][totalH(4B)][subH_0(4B)]...[subH_{N-1}(4B)][pixelsLen(4B)]
        ByteBuffer header = ByteBuffer.allocate(16 + subs.size() * 4).order(ByteOrder.BIG_ENDIAN);
        header.putInt(subs.size());
        header.putInt(w);
        header.putInt(totalH);
        for (CompositeMapMetadata.SubImageInfo sub : subs) {
            header.putInt(sub.height());
        }
        header.putInt(w * totalH);
        out.write(header.array());

        byte[] rowGray = new byte[w];
        for (CompositeMapMetadata.SubImageInfo sub : subs) {
            String srcPath = sub.sourcePath();
            if (srcPath == null || srcPath.isEmpty()) {
                log.warn("子图 {} sourcePath 为空，写入空白占位", sub.name());
                byte[] blankRow = new byte[w];
                for (int y = 0; y < sub.height(); y++) {
                    out.write(blankRow);
                }
                continue;
            }

            long t0 = System.currentTimeMillis();
            try (InputStream is = ResourceUtils.getResourceStream(srcPath)) {
                PngReader reader = null;
                try {
                    reader = new PngReader(is);
                    int imgH = reader.imgInfo.rows;
                    int imgW = reader.imgInfo.cols;
                    int channels = reader.imgInfo.channels;
                    int bitDepth = reader.imgInfo.bitDepth;
                    int rowsToWrite = Math.min(imgH, sub.height());

                    log.info("子图 {} 加载: PNG={}x{} ch={} bit={}, metadata.h={}",
                            sub.name(), imgW, imgH, channels, bitDepth, sub.height());

                    for (int y = 0; y < rowsToWrite; y++) {
                        IImageLine line = reader.readRow();
                        writeGrayLine(line, reader, w, rowGray);
                        out.write(rowGray);
                    }
                    // 不足 sub.height 时补空白
                    for (int y = rowsToWrite; y < sub.height(); y++) {
                        out.write(rowGray);
                    }
                    log.info("子图 {} 写入完成: {} 行 ({}ms)", sub.name(), rowsToWrite,
                            System.currentTimeMillis() - t0);
                } finally {
                    if (reader != null) reader.end();
                }
            } catch (Exception e) {
                log.warn("子图加载失败: {}, 写入空白占位", sub.name(), e);
                byte[] blankRow = new byte[w];
                for (int y = 0; y < sub.height(); y++) {
                    out.write(blankRow);
                }
            }
        }
    }

    /** 逐行将 PngReader 数据转为灰度写入 OutputStream */
    private static void writeGrayRows(PngReader reader, int w, int h, OutputStream out) throws IOException {
        byte[] rowGray = new byte[w];
        for (int y = 0; y < h; y++) {
            IImageLine line = reader.readRow();
            writeGrayLine(line, reader, w, rowGray);
            out.write(rowGray);
        }
    }

    /**
     * 将一行 PNG 像素转为 8-bit 灰度，兼容 ImageLineByte / ImageLineInt、
     * 灰度 / RGB / RGBA / 16-bit 等各种格式。
     */
    private static void writeGrayLine(IImageLine line, PngReader reader, int w, byte[] rowGray) {
        int channels = reader.imgInfo.channels;
        int shift = reader.imgInfo.bitDepth > 8 ? reader.imgInfo.bitDepth - 8 : 0;
        boolean isGray = reader.imgInfo.greyscale;

        if (line instanceof ImageLineByte byteLine) {
            byte[] bytes = byteLine.getScanlineByte();
            for (int x = 0; x < w; x++) {
                int off = x * channels;
                if (isGray) {
                    rowGray[x] = bytes[off];
                } else {
                    int r = bytes[off] & 0xFF;
                    int g = bytes[off + 1] & 0xFF;
                    int b = bytes[off + 2] & 0xFF;
                    rowGray[x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                }
            }
        } else if (line instanceof ImageLineInt intLine) {
            int[] ints = intLine.getScanline();
            for (int x = 0; x < w; x++) {
                int off = x * channels;
                if (isGray) {
                    rowGray[x] = (byte) (ints[off] >> shift);
                } else {
                    int r = ints[off] >> shift;
                    int g = ints[off + 1] >> shift;
                    int b = ints[off + 2] >> shift;
                    rowGray[x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                }
            }
        } else {
            // 未知行类型，写空白
            Arrays.fill(rowGray, 0, w, (byte) 0);
        }
    }

    /**
     * 地图图片元信息（不含像素数据）。
     */
    public record ImageInfo(int width, int height) {
    }
}
