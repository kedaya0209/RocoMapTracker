package io.github.kedaya0209.roco.app.map;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * 洞穴图像对比度增强工具 — 对洞穴源 PNG 应用 CLAHE。
 *
 * <p>内存优化（PNGJ 流式）：逐行读写，CDF 表仅 64×256=16KB。
 */
@Slf4j
public class CaveImageEnhancer {

    private static final String METADATA_PATH = "/source/maps/MultiMap_metadata.json";
    private static final int TILE_GRID = 8;
    private static final int CLIP_LIMIT = 3;
    private static final int BINS = 256;

    public static void main(String[] args) throws Exception {
        CompositeMapMetadata metadata;
        try (InputStream is = ResourceUtils.getResourceStream(METADATA_PATH)) {
            metadata = CompositeMapMetadata.load(is);
        }
        log.info("元数据加载完成: {} 个子图", metadata.subImages().size());

        String baseDir = System.getProperty("cave-enhancer.resources",
                "roco-map/src/main/resources");

        for (CompositeMapMetadata.SubImageInfo sub : metadata.subImages()) {
            String srcPath = sub.sourcePath();
            if (srcPath == null || srcPath.isEmpty()) continue;
            if (!sub.isCave()) {
                log.info("跳过非洞穴: {}", sub.name());
                continue;
            }
            String relPath = srcPath.startsWith("/") ? srcPath.substring(1) : srcPath;
            Path srcFile = Paths.get(baseDir, relPath);
            log.info("处理: {} ({})", sub.name(), srcFile);
            enhance(srcFile);
        }
        log.info("=== 全部处理完成 ===");
    }

    /**
     * 对洞穴 PNG 执行 CLAHE 增强（直接写回原文件）。
     *
     * <p>Pass 1：逐行读取，为每个 tile 累加直方图；Pass 2：逐行读取 → 映射 → 写入临时文件。
     */
    public static void enhance(Path srcFile) throws IOException {
        if (!Files.exists(srcFile)) {
            log.info("文件不存在，跳过: {}", srcFile);
            return;
        }

        int w, h;
        int tilesX = TILE_GRID;
        int tilesY = TILE_GRID;
        int[][] cdfs;

        // ===== Pass 1: 读取尺寸 =====
        PngReader reader = null;
        try {
            reader = new PngReader(srcFile.toFile());
            w = reader.imgInfo.cols;
            h = reader.imgInfo.rows;
        } finally {
            if (reader != null) reader.end();
        }

        int tileW = (int) Math.ceil((double) w / tilesX);
        int tileH = (int) Math.ceil((double) h / tilesY);

        // 直方图累加
        int[][] hists = new int[tilesX * tilesY][BINS];
        int[] counts = new int[tilesX * tilesY];

        try {
            reader = new PngReader(srcFile.toFile());
            for (int y = 0; y < h; y++) {
                int[] rgba = readRowRgba(reader.readRow(), w);
                int ty = Math.min(y / tileH, tilesY - 1);
                for (int x = 0; x < w; x++) {
                    int off = x * 4;
                    int a = rgba[off + 3];
                    if (a == 0) continue;
                    int r = rgba[off];
                    int g = rgba[off + 1];
                    int b = rgba[off + 2];
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    int tx = Math.min(x / tileW, tilesX - 1);
                    hists[ty * tilesX + tx][lum]++;
                    counts[ty * tilesX + tx]++;
                }
            }
        } finally {
            if (reader != null) { reader.end(); reader = null; }
        }

        // clip + CDF
        cdfs = new int[tilesX * tilesY][BINS];
        for (int i = 0; i < tilesX * tilesY; i++) {
            int count = counts[i];
            int[] hist = hists[i];
            int[] cdf = cdfs[i];

            int clip = Math.max(1, (count / BINS) * CLIP_LIMIT);
            int clipped = 0;
            for (int j = 0; j < BINS; j++) {
                if (hist[j] > clip) {
                    clipped += hist[j] - clip;
                    hist[j] = clip;
                }
            }
            int redist = clipped / BINS;
            int remainder = clipped % BINS;
            for (int j = 0; j < BINS; j++) hist[j] += redist;
            for (int j = 0; j < remainder; j++) hist[j]++;

            int sum = 0;
            for (int j = 0; j < BINS; j++) {
                sum += hist[j];
                cdf[j] = sum;
            }

            if (count > 0) {
                float scale = 255.0f / count;
                for (int j = 0; j < BINS; j++) cdf[j] = Math.round(cdf[j] * scale);
            }
        }

        // ===== Pass 2: CLAHE 映射 → 写入临时文件 =====
        String tempName = srcFile.getFileName().toString().replace(".png", "_clahe.tmp");
        Path tempFile = srcFile.resolveSibling(tempName);

        PngWriter writer = null;
        try {
            reader = new PngReader(srcFile.toFile());
            writer = new PngWriter(tempFile.toFile(),
                    new ImageInfo(w, h, 8, true, false, false));

            ImageLineInt outLine = new ImageLineInt(writer.imgInfo);
            for (int y = 0; y < h; y++) {
                int[] rgba = readRowRgba(reader.readRow(), w);
                int[] outScan = outLine.getScanline();

                for (int x = 0; x < w; x++) {
                    int off = x * 4;
                    int a = rgba[off + 3];
                    if (a == 0) {
                        outScan[off] = 0;
                        outScan[off + 1] = 0;
                        outScan[off + 2] = 0;
                        outScan[off + 3] = 0;
                        continue;
                    }

                    int r = rgba[off];
                    int g = rgba[off + 1];
                    int b = rgba[off + 2];
                    int origGray = (r * 299 + g * 587 + b * 114) / 1000;

                    float fx = (float) x / tileW - 0.5f;
                    float fy = (float) y / tileH - 0.5f;

                    int tx1 = Math.max(0, (int) Math.floor(fx));
                    int tx2 = Math.min(tilesX - 1, tx1 + 1);
                    int ty1 = Math.max(0, (int) Math.floor(fy));
                    int ty2 = Math.min(tilesY - 1, ty1 + 1);

                    float wx = fx - tx1;
                    float wy = fy - ty1;
                    if (tx1 == tx2) wx = 0;
                    if (ty1 == ty2) wy = 0;

                    int v00 = cdfs[ty1 * tilesX + tx1][origGray];
                    int v10 = cdfs[ty1 * tilesX + tx2][origGray];
                    int v01 = cdfs[ty2 * tilesX + tx1][origGray];
                    int v11 = cdfs[ty2 * tilesX + tx2][origGray];

                    int enhancedGray = Math.clamp(Math.round(
                            (1 - wy) * ((1 - wx) * v00 + wx * v10)
                                    + wy * ((1 - wx) * v01 + wx * v11)
                    ), 0, 255);

                    int outR, outG, outB;
                    if (origGray > 0) {
                        float scale = (float) enhancedGray / origGray;
                        outR = Math.min(255, Math.round(r * scale));
                        outG = Math.min(255, Math.round(g * scale));
                        outB = Math.min(255, Math.round(b * scale));
                    } else {
                        outR = outG = outB = enhancedGray;
                    }

                    outScan[x * 4] = outR;
                    outScan[x * 4 + 1] = outG;
                    outScan[x * 4 + 2] = outB;
                    outScan[x * 4 + 3] = a;
                }
                writer.writeRow(outLine, y);
            }
        } finally {
            if (reader != null) reader.end();
            if (writer != null) writer.end();
        }

        Files.move(tempFile, srcFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("  CLAHE 增强完成: {}", srcFile.getFileName());
    }

    /**
     * 对灰度图应用 CLAHE（保留向后兼容）。
     */
    public static byte[] applyCLAHE(byte[] src, int w, int h, boolean[] mask) {
        int tilesX = TILE_GRID;
        int tilesY = TILE_GRID;
        int tileW = (int) Math.ceil((double) w / tilesX);
        int tileH = (int) Math.ceil((double) h / tilesY);

        int[][] cdfs = new int[tilesX * tilesY][BINS];
        int[] totalPixels = new int[tilesX * tilesY];

        int[] pixels = new int[src.length];
        for (int i = 0; i < src.length; i++) {
            pixels[i] = src[i] & 0xFF;
        }

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int idx = ty * tilesX + tx;
                int[] hist = new int[BINS];
                int count = 0;

                int yStart = ty * tileH;
                int yEnd = Math.min(yStart + tileH, h);
                int xStart = tx * tileW;
                int xEnd = Math.min(xStart + tileW, w);

                for (int y = yStart; y < yEnd; y++) {
                    for (int x = xStart; x < xEnd; x++) {
                        int pi = y * w + x;
                        if (mask[pi]) {
                            hist[pixels[pi]]++;
                            count++;
                        }
                    }
                }

                totalPixels[idx] = count;

                int clip = Math.max(1, (count / BINS) * CLIP_LIMIT);
                int clipped = 0;
                for (int i = 0; i < BINS; i++) {
                    if (hist[i] > clip) {
                        clipped += hist[i] - clip;
                        hist[i] = clip;
                    }
                }
                int redist = clipped / BINS;
                int remainder = clipped % BINS;
                for (int i = 0; i < BINS; i++) hist[i] += redist;
                for (int i = 0; i < remainder; i++) hist[i]++;

                int[] cdf = cdfs[idx];
                int sum = 0;
                for (int i = 0; i < BINS; i++) {
                    sum += hist[i];
                    cdf[i] = sum;
                }

                if (count > 0) {
                    float scale = 255.0f / count;
                    for (int i = 0; i < BINS; i++) cdf[i] = Math.round(cdf[i] * scale);
                }
            }
        }

        byte[] result = new byte[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pi = y * w + x;
                if (!mask[pi]) {
                    result[pi] = 0;
                    continue;
                }

                int v = pixels[pi];

                float fx = (float) x / tileW - 0.5f;
                float fy = (float) y / tileH - 0.5f;

                int tx1 = Math.max(0, (int) Math.floor(fx));
                int tx2 = Math.min(tilesX - 1, tx1 + 1);
                int ty1 = Math.max(0, (int) Math.floor(fy));
                int ty2 = Math.min(tilesY - 1, ty1 + 1);

                float wx = fx - tx1;
                float wy = fy - ty1;

                if (tx1 == tx2) wx = 0;
                if (ty1 == ty2) wy = 0;

                int v00 = cdfs[ty1 * tilesX + tx1][v];
                int v10 = cdfs[ty1 * tilesX + tx2][v];
                int v01 = cdfs[ty2 * tilesX + tx1][v];
                int v11 = cdfs[ty2 * tilesX + tx2][v];

                float mapped = (1 - wy) * ((1 - wx) * v00 + wx * v10)
                             + wy * ((1 - wx) * v01 + wx * v11);

                result[pi] = (byte) Math.clamp(Math.round(mapped), 0, 255);
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
            Arrays.fill(rgba, 0);
        }
        return rgba;
    }
}
