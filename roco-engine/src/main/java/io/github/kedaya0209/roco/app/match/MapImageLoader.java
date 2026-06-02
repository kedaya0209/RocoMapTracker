package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 地图资源加载器 — 从 classpath 加载地图图片并流式转换为灰度像素。
 *
 * <p>职责单一：纯 I/O + 图像处理，不含协议编解码或匹配逻辑。
 * <p>解码为全图 BufferedImage 后逐行转灰度直写，避免额外 int[] + byte[] 大缓冲。
 */
@ThreadSafe
@Slf4j
public final class MapImageLoader {

    /** 每个子图的标准高度（像素），用于多子图 MAP_DATA 协议 */
    public static final int SUB_IMAGE_HEIGHT = 8192;

    private MapImageLoader() {
    }

    /**
     * 加载地图图片，解码为 BufferedImage。
     */
    public static ImageInfo loadImage() throws Exception {
        String mapPath = ResourceConfigContext.getSiftMap();
        BufferedImage img;
        try (InputStream is = ResourceUtils.getResourceStream(mapPath)) {
            img = ImageIO.read(is);
        }
        if (img == null || img.getWidth() <= 0) {
            throw new IOException("地图图片解码失败");
        }
        return new ImageInfo(img, img.getWidth(), img.getHeight());
    }

    /**
     * 根据总高度计算子图数量（每子图 8192px）。
     */
    public static int getSubImageCount(int totalHeight) {
        return totalHeight / SUB_IMAGE_HEIGHT;
    }

    /**
     * 流式写入协议头 + 灰度像素到 OutputStream（逐行，无全图大缓冲）。
     *
     * <p>协议格式：[w(4B)][h(4B)][pixelsLen(4B)][gray8...]
     *
     * @param info 由 {@link #loadImage()} 返回的图片元信息
     * @param out  目标输出流（通常是 Socket OutputStream）
     */
    public static void writeStreaming(ImageInfo info, OutputStream out) throws IOException {
        int w = info.width();
        int h = info.height();

        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        header.putInt(w).putInt(h).putInt(w * h);
        out.write(header.array());

        writeGrayscaleRows(info.image(), w, h, out);
    }

    /**
     * 流式写入多子图协议头 + 灰度像素。
     *
     * <p>多子图格式：[subImageCount(4B)][w(4B)][totalH(4B)][subH_0(4B)]...[subH_{N-1}(4B)][pixelsLen(4B)][gray8...]
     *
     * @param info          整张拼接图元信息
     * @param subImageCount 子图数量（用于计算 equal-height 子图）
     * @param out           目标输出流
     */
    public static void writeStreamingMulti(ImageInfo info, int subImageCount, OutputStream out) throws IOException {
        int w = info.width();
        int h = info.height();
        int subH = subImageCount > 0 ? h / subImageCount : h;

        // Multi-subimage header
        ByteBuffer header = ByteBuffer.allocate(16 + subImageCount * 4).order(ByteOrder.BIG_ENDIAN);
        header.putInt(subImageCount);
        header.putInt(w);
        header.putInt(h);
        for (int i = 0; i < subImageCount; i++) {
            header.putInt(subH);
        }
        header.putInt(w * h); // pixelsLen
        out.write(header.array());

        writeGrayscaleRows(info.image(), w, h, out);
    }

    /**
     * 从 MultiMap 元数据流式写入多子图协议数据。
     * 逐个加载子图 PNG → 灰度转换 → 流式输出，避免加载完整 8192x49152 组合图。
     */
    public static void writeStreamingMultiFromMetadata(CompositeMapMetadata metadata, OutputStream out) throws IOException {
        int w = metadata.width();
        int totalH = metadata.totalHeight();
        var subs = metadata.subImages();
        int subCount = subs.size();

        // 协议头: [subImageCount(4B)][w(4B)][totalH(4B)]
        //         [subH_0(4B)]...[subH_{N-1}(4B)][pixelsLen(4B)]
        ByteBuffer header = ByteBuffer.allocate(16 + subCount * 4).order(ByteOrder.BIG_ENDIAN);
        header.putInt(subCount);
        header.putInt(w);
        header.putInt(totalH);
        for (var sub : subs) {
            header.putInt(sub.height());
        }
        header.putInt(w * totalH);
        out.write(header.array());

        // 逐子图加载并写出灰度行
        int[] rowArgb = new int[w];
        byte[] rowGray = new byte[w];
        for (var sub : subs) {
            String srcPath = sub.sourcePath();
            if (srcPath == null || srcPath.isEmpty()) {
                log.warn("子图 {} sourcePath 为空，写入空白占位", sub.name());
                for (int y = 0; y < sub.height(); y++) {
                    out.write(rowGray);
                }
                continue;
            }
            try (InputStream is = ResourceUtils.getResourceStream(srcPath)) {
                BufferedImage img = ImageIO.read(is);
                if (img == null) {
                    log.warn("子图加载失败: {}, 写入空白占位", sub.name());
                    for (int y = 0; y < sub.height(); y++) {
                        out.write(rowGray);
                    }
                    continue;
                }
                for (int y = 0; y < sub.height() && y < img.getHeight(); y++) {
                    img.getRGB(0, y, w, 1, rowArgb, 0, w);
                    for (int x = 0; x < w; x++) {
                        int rgb = rowArgb[x];
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        rowGray[x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                    }
                    out.write(rowGray);
                }
            }
        }
    }

    /** 逐行将 BufferedImage 转为灰度写入 OutputStream */
    private static void writeGrayscaleRows(BufferedImage image, int w, int h, OutputStream out) throws IOException {
        int[] rowArgb = new int[w];
        byte[] rowGray = new byte[w];
        for (int y = 0; y < h; y++) {
            image.getRGB(0, y, w, 1, rowArgb, 0, w);
            for (int x = 0; x < w; x++) {
                int rgb = rowArgb[x];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                rowGray[x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
            }
            out.write(rowGray);
        }
    }

    /**
     * 地图图片元信息。
     */
    public record ImageInfo(BufferedImage image, int width, int height) {
    }
}
