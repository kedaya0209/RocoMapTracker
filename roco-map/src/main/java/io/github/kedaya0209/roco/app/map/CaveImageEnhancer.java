package io.github.kedaya0209.roco.app.map;

import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * 洞穴图像对比度增强工具 — 对洞穴源 PNG 应用 CLAHE（限制对比度自适应直方图均衡化），
 * 提升暗色区域的纹理可见度，使 SIFT 能提取更多特征。
 *
 * <p>使用方式（在项目根目录运行）：
 * <pre>
 *     mvn compile exec:java -pl roco-map \
 *         -Dexec.mainClass="io.github.kedaya0209.roco.app.map.CaveImageEnhancer"
 * </pre>
 *
 * <p>非洞穴像素（alpha=0）会被置 0（黑色），不影响 SIFT 特征提取。
 */
public class CaveImageEnhancer {

    private static final String METADATA_PATH = "/source/maps/MultiMap_metadata.json";
    private static final int TILE_GRID = 8;       // 8×8 tiles
    private static final int CLIP_LIMIT = 3;      // 对比度放大截断阈值
    private static final int BINS = 256;

    public static void main(String[] args) throws Exception {
        // 1. 加载元数据
        CompositeMapMetadata metadata;
        try (InputStream is = ResourceUtils.getResourceStream(METADATA_PATH)) {
            metadata = CompositeMapMetadata.load(is);
        }
        System.out.println("元数据加载完成: " + metadata.subImages().size() + " 个子图");

        // 定位资源目录
        String baseDir = System.getProperty("cave-enhancer.resources",
                "roco-map/src/main/resources");

        for (var sub : metadata.subImages()) {
            String srcPath = sub.sourcePath();
            if (srcPath == null || srcPath.isEmpty()) continue;
            if (!sub.isCave()) {
                System.out.println("跳过非洞穴: " + sub.name());
                continue;
            }

            // 定位源文件
            // sourcePath 如 "/source/maps/xxx.png"，去掉前导 "/" 后拼接
            String relPath = srcPath.startsWith("/") ? srcPath.substring(1) : srcPath;
            Path srcFile = Paths.get(baseDir, relPath);
            if (!Files.exists(srcFile)) {
                System.out.println("文件不存在，跳过: " + srcFile);
                continue;
            }

            System.out.println("处理: " + sub.name() + " (" + srcFile + ")");
            BufferedImage img = ImageIO.read(srcFile.toFile());
            if (img == null) {
                System.out.println("  读取失败，跳过");
                continue;
            }

            int w = img.getWidth();
            int h = img.getHeight();
            System.out.println("  尺寸: " + w + "x" + h);

            // 2. 提取 alpha + 原始 RGB + 灰度
            int[] argb = new int[w * h];
            img.getRGB(0, 0, w, h, argb, 0, w);

            byte[] alpha = new byte[w * h];
            byte[] origR = new byte[w * h];
            byte[] origG = new byte[w * h];
            byte[] origB = new byte[w * h];
            byte[] gray = new byte[w * h];
            int[] grayInt = new int[w * h]; // 0~255 灰度用于 CLAHE
            boolean[] mask = new boolean[w * h];
            for (int i = 0; i < argb.length; i++) {
                int a = (argb[i] >> 24) & 0xFF;
                alpha[i] = (byte) a;
                mask[i] = a > 0;
                if (mask[i]) {
                    int r = (argb[i] >> 16) & 0xFF;
                    int g = (argb[i] >> 8) & 0xFF;
                    int b = argb[i] & 0xFF;
                    origR[i] = (byte) r;
                    origG[i] = (byte) g;
                    origB[i] = (byte) b;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    gray[i] = (byte) lum;
                    grayInt[i] = lum;
                } else {
                    gray[i] = 0;
                    grayInt[i] = 0;
                }
            }

            // 3. CLAHE
            byte[] enhanced = applyCLAHE(gray, w, h, mask);

            // 4. 非洞穴像素置 0 + 彩色增强
            for (int i = 0; i < enhanced.length; i++) {
                if (!mask[i]) enhanced[i] = 0;
            }

            // 5. 写回彩色 ARGB PNG（CLAHE 亮度比例映射到原始 RGB）
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            int[] outPixels = new int[w * h];
            for (int i = 0; i < enhanced.length; i++) {
                int a = alpha[i] & 0xFF;
                if (!mask[i]) {
                    outPixels[i] = (a << 24) | 0; // 透明区域全 0
                } else {
                    int enhancedGray = enhanced[i] & 0xFF;
                    int origGray = grayInt[i];
                    // 防止除零：如果原图灰度为 0，用增强值作为灰度输出
                    int outR, outG, outB;
                    if (origGray > 0) {
                        float scale = (float) enhancedGray / origGray;
                        outR = Math.min(255, Math.round(((origR[i] & 0xFF) * scale)));
                        outG = Math.min(255, Math.round(((origG[i] & 0xFF) * scale)));
                        outB = Math.min(255, Math.round(((origB[i] & 0xFF) * scale)));
                    } else {
                        outR = outG = outB = enhancedGray;
                    }
                    outPixels[i] = (a << 24) | (outR << 16) | (outG << 8) | outB;
                }
            }
            out.setRGB(0, 0, w, h, outPixels, 0, w);
            ImageIO.write(out, "png", srcFile.toFile());
            System.out.println("  已写回: " + srcFile.getFileName());
        }

        System.out.println("=== 全部处理完成 ===");
    }

    /**
     * 对灰度图应用 CLAHE。
     *
     * @param src  输入灰度像素（0~255）
     * @param w    宽度
     * @param h    高度
     * @param mask 有效像素标记（true=处理，false=跳过）
     * @return CLAHE 增强后的像素
     */
    public static byte[] applyCLAHE(byte[] src, int w, int h, boolean[] mask) {
        int tilesX = TILE_GRID;
        int tilesY = TILE_GRID;
        int tileW = (int) Math.ceil((double) w / tilesX);
        int tileH = (int) Math.ceil((double) h / tilesY);

        // 为每个 tile 计算 CDF（已 clip）
        int[][] cdfs = new int[tilesX * tilesY][BINS];
        int[] totalPixels = new int[tilesX * tilesY];

        byte[] unsigned = new byte[src.length]; // reuse as int[] for pixel values
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

                // clip histogram
                int clip = Math.max(1, (count / BINS) * CLIP_LIMIT);
                int clipped = 0;
                for (int i = 0; i < BINS; i++) {
                    if (hist[i] > clip) {
                        clipped += hist[i] - clip;
                        hist[i] = clip;
                    }
                }
                // redistribute clipped pixels
                int redist = clipped / BINS;
                int remainder = clipped % BINS;
                for (int i = 0; i < BINS; i++) {
                    hist[i] += redist;
                }
                for (int i = 0; i < remainder; i++) {
                    hist[i]++;
                }

                // build CDF
                int[] cdf = cdfs[idx];
                int sum = 0;
                for (int i = 0; i < BINS; i++) {
                    sum += hist[i];
                    cdf[i] = sum;
                }

                // normalize CDF to [0, 255]
                if (count > 0) {
                    float scale = 255.0f / count;
                    for (int i = 0; i < BINS; i++) {
                        cdf[i] = Math.round(cdf[i] * scale);
                    }
                }
            }
        }

        // 双线性插值：对每个像素，用周围 4 个 tile 的 CDF 做插值
        byte[] result = new byte[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pi = y * w + x;
                if (!mask[pi]) {
                    result[pi] = 0;
                    continue;
                }

                int v = pixels[pi];

                // 计算 tile 坐标（浮点）
                float fx = (float) x / tileW - 0.5f;
                float fy = (float) y / tileH - 0.5f;

                int tx1 = Math.max(0, (int) Math.floor(fx));
                int tx2 = Math.min(tilesX - 1, tx1 + 1);
                int ty1 = Math.max(0, (int) Math.floor(fy));
                int ty2 = Math.min(tilesY - 1, ty1 + 1);

                // 插值权重
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
}
