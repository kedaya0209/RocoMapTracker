package io.github.kedaya0209.roco.app.map.util;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 亮度提取工具 — 将图像中亮度超过阈值的区域抠出并保存为透明背景 PNG。
 * 用于 WIKI 资源洞穴图下载后的后处理，提取高亮区域作为 SIFT 可匹配特征。
 */
@Slf4j
@ThreadSafe
public final class BrightnessExtractor {

    /**
     * 默认亮度阈值（0~255）：像素亮度大于此值视为"有内容"
     */
    private static final int DEFAULT_THRESHOLD = 50;

    /**
     * 膨胀核大小
     */
    private static final int KERNEL_SIZE = 3;

    private BrightnessExtractor() {
    }

    /**
     * 读取图片 → 亮度阈值提取高亮区域 → 保存为透明背景 PNG。
     *
     * @param inputPath  输入图片路径
     * @param outputPath 输出 PNG 路径（含透明度）
     * @throws IOException 读写失败时抛出
     */
    public static void extractAndSave(Path inputPath, Path outputPath) throws IOException {
        extractAndSave(inputPath, outputPath, DEFAULT_THRESHOLD);
    }

    /**
     * 读取图片 → 亮度阈值提取高亮区域 → 保存为透明背景 PNG。
     *
     * @param inputPath  输入图片路径
     * @param outputPath 输出 PNG 路径（含透明度）
     * @param threshold  亮度阈值（0~255）
     * @throws IOException 读写失败时抛出
     */
    public static void extractAndSave(Path inputPath, Path outputPath, int threshold) throws IOException {
        BufferedImage src = ImageIO.read(inputPath.toFile());
        if (src == null) {
            throw new IOException("无法读取图片: " + inputPath);
        }

        int w = src.getWidth();
        int h = src.getHeight();

        // 1. 读取所有像素 ARGB
        int[] argb = new int[w * h];
        src.getRGB(0, 0, w, h, argb, 0, w);

        // 2. 计算灰度 + 二值化掩码
        boolean[] mask = new boolean[w * h];
        int[] gray = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int r = (argb[i] >> 16) & 0xFF;
            int g = (argb[i] >> 8) & 0xFF;
            int b = argb[i] & 0xFF;
            // 亮度公式：0.299R + 0.587G + 0.114B
            int lum = (r * 299 + g * 587 + b * 114) / 1000;
            gray[i] = lum;
            mask[i] = lum > threshold;
        }

        // 3. 膨胀掩码（3×3 核）
        mask = dilate(mask, w, h, KERNEL_SIZE);

        // 4. 输出 ARGB：掩码为 True 的区域保留原色，其余透明
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] outPixels = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            if (mask[i]) {
                outPixels[i] = argb[i]; // 保留原 ARGB（含原始 alpha）
            } else {
                outPixels[i] = 0; // 完全透明
            }
        }
        out.setRGB(0, 0, w, h, outPixels, 0, w);

        // 5. 保存
        ImageIO.write(out, "png", outputPath.toFile());
        log.info("亮度提取完成: {} → {} ({}x{})", inputPath.getFileName(), outputPath.getFileName(), w, h);
    }

    /**
     * 对二值掩码做形态学膨胀。
     *
     * @param mask   输入掩码
     * @param w      宽度
     * @param h      高度
     * @param ksize  核大小（奇数）
     * @return 膨胀后的掩码
     */
    private static boolean[] dilate(boolean[] mask, int w, int h, int ksize) {
        int half = ksize / 2;
        boolean[] result = new boolean[mask.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int pi = y * w + x;
                if (mask[pi]) {
                    // 将核范围内的所有像素置为 true
                    int y0 = Math.max(0, y - half);
                    int y1 = Math.min(h - 1, y + half);
                    int x0 = Math.max(0, x - half);
                    int x1 = Math.min(w - 1, x + half);
                    for (int dy = y0; dy <= y1; dy++) {
                        int rowOffset = dy * w;
                        for (int dx = x0; dx <= x1; dx++) {
                            result[rowOffset + dx] = true;
                        }
                    }
                }
            }
        }
        return result;
    }
}
