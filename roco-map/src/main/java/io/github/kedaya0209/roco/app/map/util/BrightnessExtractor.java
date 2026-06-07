package io.github.kedaya0209.roco.app.map.util;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.IOException;
import java.nio.file.Path;
import java.util.BitSet;

/**
 * 亮度提取工具 — 将图像中亮度超过阈值的区域抠出并保存为透明背景 PNG。
 * 使用 PNGJ 逐行读写，不将全图加载到内存。
 */
@Slf4j
@ThreadSafe
public final class BrightnessExtractor {

    private static final int DEFAULT_THRESHOLD = 50;
    private static final int KERNEL_SIZE = 3;

    private BrightnessExtractor() {
    }

    public static void extractAndSave(Path inputPath, Path outputPath) throws IOException {
        extractAndSave(inputPath, outputPath, DEFAULT_THRESHOLD);
    }

    /**
     * 读取图片 → 亮度阈值提取高亮区域 → 保存为透明背景 PNG。
     *
     * <p>内存优化：
     * <ol>
     *   <li>逐行读取源图，二值掩码存储在 BitSet（~8MB vs boolean[] 的 67MB）</li>
     *   <li>BitSet 上实现膨胀</li>
     *   <li>逐行写出结果</li>
     * </ol>
     */
    public static void extractAndSave(Path inputPath, Path outputPath, int threshold) throws IOException {
        int w, h;
        BitSet mask;

        // ===== Pass 1: 逐行读取，构建 BitSet 掩码 =====
        PngReader reader1 = null;
        try {
            reader1 = new PngReader(inputPath.toFile());
            w = reader1.imgInfo.cols;
            h = reader1.imgInfo.rows;
            int n = w * h;
            mask = new BitSet(n);

            for (int y = 0; y < h; y++) {
                int[] rgba = readRowRgba(reader1.readRow(), w);
                for (int x = 0; x < w; x++) {
                    int off = x * 4;
                    int a = rgba[off + 3];
                    if (a == 0) continue;
                    int r = rgba[off];
                    int g = rgba[off + 1];
                    int b = rgba[off + 2];
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    if (lum > threshold) {
                        mask.set(y * w + x);
                    }
                }
            }
        } finally {
            if (reader1 != null) reader1.end();
        }

        // ===== Pass 2: BitSet 膨胀 =====
        mask = dilateBitSet(mask, w, h, KERNEL_SIZE);

        // ===== Pass 3: 逐行写出 =====
        PngReader reader2 = null;
        PngWriter writer = null;
        try {
            reader2 = new PngReader(inputPath.toFile());
            writer = new PngWriter(outputPath.toFile(),
                    new ImageInfo(w, h, 8, true, false, false));

            ImageLineInt outLine = new ImageLineInt(writer.imgInfo);
            for (int y = 0; y < h; y++) {
                int[] rgba = readRowRgba(reader2.readRow(), w);
                int[] outScan = outLine.getScanline();
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    int off = x * 4;
                    if (mask.get(idx)) {
                        outScan[off] = rgba[off];
                        outScan[off + 1] = rgba[off + 1];
                        outScan[off + 2] = rgba[off + 2];
                        outScan[off + 3] = rgba[off + 3];
                    } else {
                        outScan[off] = 0;
                        outScan[off + 1] = 0;
                        outScan[off + 2] = 0;
                        outScan[off + 3] = 0;
                    }
                }
                writer.writeRow(outLine, y);
            }
        } finally {
            if (reader2 != null) reader2.end();
            if (writer != null) writer.end();
        }

        log.info("亮度提取完成: {} → {} ({}x{})", inputPath.getFileName(), outputPath.getFileName(), w, h);
    }

    private static BitSet dilateBitSet(BitSet mask, int w, int h, int ksize) {
        int half = ksize / 2;
        BitSet result = new BitSet(mask.size());
        for (int idx = mask.nextSetBit(0); idx >= 0; idx = mask.nextSetBit(idx + 1)) {
            int y = idx / w, x = idx % w;
            int y0 = Math.max(0, y - half);
            int y1 = Math.min(h - 1, y + half);
            int x0 = Math.max(0, x - half);
            int x1 = Math.min(w - 1, x + half);
            for (int dy = y0; dy <= y1; dy++) {
                int rowOffset = dy * w;
                for (int dx = x0; dx <= x1; dx++) {
                    result.set(rowOffset + dx);
                }
            }
        }
        return result;
    }

    /**
     * 将 PNGJ 行数据统一转为 RGBA int 数组，兼容 ImageLineByte / ImageLineInt。
     */
    private static int[] readRowRgba(ar.com.hjg.pngj.IImageLine line, int w) {
        int[] rgba = new int[w * 4];
        if (line instanceof ImageLineByte byteLine) {
            byte[] src = byteLine.getScanlineByte();
            for (int i = 0; i < w * 4; i++) {
                rgba[i] = src[i] & 0xFF;
            }
        } else if (line instanceof ImageLineInt intLine) {
            int[] src = intLine.getScanline();
            System.arraycopy(src, 0, rgba, 0, w * 4);
        } else {
            // 未知行类型，全透明
            java.util.Arrays.fill(rgba, 0);
        }
        return rgba;
    }
}
