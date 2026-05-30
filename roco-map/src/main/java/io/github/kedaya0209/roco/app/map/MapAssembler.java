package io.github.kedaya0209.roco.app.map;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * 洛克王国：世界 - 高性能地图合成工具
 * 修复：确保 SIFT 图包含陆地细节，展示图边缘浓郁
 */
@Slf4j
@NotThreadSafe
public class MapAssembler {
    private static final int TILE_SIZE = 2048;
    private static final int GRID_COUNT = 4;
    private static final int FULL_SIZE = TILE_SIZE * GRID_COUNT;

    private static final int DILATION_RADIUS = 15;
    private static final int SMOOTH_RADIUS = 15;

    private static final int STACK_DENSITY = 16000;
    private static final float SCALE_MIN = 0.3f;
    private static final float SCALE_MAX = 0.7f;
    private static final float STACK_ALPHA = 1.0f;
    private static final float GLOBAL_FOG_ALPHA = 0.8f;

    public static void main(String[] args) {
        String basePath = "C:\\Users\\tangh\\Desktop\\map\\";
        String mapDir = basePath + "bigmap\\";
        String maskDir = basePath + "mask\\";
        String texturePath = basePath + "cloud_material.png";

        try {
            long startTime = System.currentTimeMillis();

            int[] fullColorPixels = new int[FULL_SIZE * FULL_SIZE];
            byte[] fence = new byte[FULL_SIZE * FULL_SIZE];

            log.info("1. 加载资源 (并行)...");
            IntStream.range(0, 16).parallel().forEach(i -> {
                int x = (i % 4) * TILE_SIZE;
                int y = (i / 4) * TILE_SIZE;
                try {
                    BufferedImage tile = ImageIO.read(new File(mapDir + String.format("%02d.png", i + 1)));
                    copyTileToBuffer(tile, fullColorPixels, x, y);
                    BufferedImage mask = ImageIO.read(new File(maskDir + String.format("T_BigMap_Mask_%02d.png", i + 1)));
                    generateHardFence(mask, fence, x, y);
                } catch (IOException e) {
                    log.error("加载瓦片 {} 失败", i + 1, e);
                }
            });

            log.info("2. 计算海陆分界与渐变权重...");
            byte[] outerFogRegion = fastFloodFill(fence, FULL_SIZE, FULL_SIZE);
            float[] smoothAlphaMask = fastBoxBlur(outerFogRegion, FULL_SIZE, FULL_SIZE, SMOOTH_RADIUS);

            // --- 任务 A: 生成 SIFT 专用透明陆地图 ---
            log.info("3. 合成 SIFT 专用图 (保留陆地细节)...");
            int[] siftPixels = new int[FULL_SIZE * FULL_SIZE];
            IntStream.range(0, FULL_SIZE * FULL_SIZE).parallel().forEach(i -> {
                // outerFogRegion 为 0 代表是陆地或靠近陆地的硬围栏内区域
                if (outerFogRegion[i] == 0) {
                    siftPixels[i] = fullColorPixels[i]; // 保留原始像素
                } else {
                    siftPixels[i] = 0x00000000; // 海洋区域完全透明
                }
            });
            saveImage(siftPixels, basePath + "WorldMap_Transparent_SIFT.png");

            // --- 任务 B: 生成带迷雾的展示图 ---
            log.info("4. 准备迷雾层纹理...");
            BufferedImage texture = ImageIO.read(new File(texturePath));
            BufferedImage fogContent = createDenseStackLayer(texture);
            int[] fogRawPixels = ((DataBufferInt) fogContent.getRaster().getDataBuffer()).getData();

            log.info("5. 并行合成展示图：处理迷雾过渡...");
            int[] displayPixels = new int[FULL_SIZE * FULL_SIZE];

            IntStream.range(0, FULL_SIZE * FULL_SIZE).parallel().forEach(i -> {
                float maskVal = smoothAlphaMask[i];
                int cA = fullColorPixels[i];

                if (maskVal <= 0.001f) {
                    displayPixels[i] = cA;
                } else {
                    // 强化边缘逻辑：使用开方让迷雾在边缘迅速变浓
                    float strongMask = (float) Math.sqrt(maskVal);
                    int cB = fogRawPixels[i];
                    int aB = (cB >> 24) & 0xFF;

                    float finalAlpha = strongMask * (aB / 255.0f) * GLOBAL_FOG_ALPHA;

                    int rA = (cA >> 16) & 0xFF, gA = (cA >> 8) & 0xFF, bA = cA & 0xFF;
                    int rB = (cB >> 16) & 0xFF, gB = (cB >> 8) & 0xFF, bB = cB & 0xFF;

                    int rOut = (int) (rA * (1 - finalAlpha) + rB * finalAlpha);
                    int gOut = (int) (gA * (1 - finalAlpha) + gB * finalAlpha);
                    int bOut = (int) (bA * (1 - finalAlpha) + bB * finalAlpha);

                    displayPixels[i] = (255 << 24) | (rOut << 16) | (gOut << 8) | bOut;
                }
            });

            saveImage(displayPixels, basePath + "Final_WorldMap_Cloudy_Show.png");
            log.info("全部完成！耗时: {}s", (System.currentTimeMillis() - startTime) / 1000.0);

        } catch (IOException e) {
            log.error("地图合成失败", e);
        }
    }

    // --- 以下为保留的原始工具方法 ---

    private static BufferedImage createDenseStackLayer(BufferedImage tex) {
        BufferedImage layer = new BufferedImage(FULL_SIZE, FULL_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = layer.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        int tw = tex.getWidth(), th = tex.getHeight();
        Random r = new Random();
        for (int i = 0; i < STACK_DENSITY; i++) {
            int x = r.nextInt(FULL_SIZE + 400) - 200;
            int y = r.nextInt(FULL_SIZE + 400) - 200;
            float scale = SCALE_MIN + r.nextFloat() * (SCALE_MAX - SCALE_MIN);
            float alpha = STACK_ALPHA * (0.4f + r.nextFloat() * 0.6f);
            int dw = (int) (tw * scale), dh = (int) (th * scale);
            g2d.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2d.translate(x, y);
            g2d.drawImage(tex, -dw / 2, -dh / 2, dw, dh, null);
            g2d.translate(-x, -y);
        }
        g2d.dispose();
        return layer;
    }

    private static void copyTileToBuffer(BufferedImage src, int[] dest, int ox, int oy) {
        src.getRGB(0, 0, src.getWidth(), src.getHeight(), dest, oy * FULL_SIZE + ox, FULL_SIZE);
    }

    private static void generateHardFence(BufferedImage mask, byte[] fence, int ox, int oy) {
        int w = mask.getWidth(), h = mask.getHeight();
        int[] pix = mask.getRGB(0, 0, w, h, null, 0, w);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // 原逻辑：Alpha > 160 判定为陆地，设置围栏
                if (((pix[y * w + x] >> 24) & 0xFF) > 160) {
                    for (int i = -DILATION_RADIUS; i <= DILATION_RADIUS; i++) {
                        int tx = x + ox + i;
                        if (tx >= 0 && tx < FULL_SIZE) fence[(y + oy) * FULL_SIZE + tx] = 1;
                        int ty = y + oy + i;
                        if (ty >= 0 && ty < FULL_SIZE) fence[ty * FULL_SIZE + (x + ox)] = 1;
                    }
                }
            }
        }
    }

    private static byte[] fastFloodFill(byte[] fence, int w, int h) {
        byte[] filled = new byte[w * h];
        Queue<Integer> q = new ArrayDeque<>(w * 4);
        int[] seeds = {0, w - 1, w * h - w, w * h - 1};
        for (int s : seeds) {
            if (fence[s] == 0) {
                filled[s] = 1;
                q.add(s);
            }
        }
        while (!q.isEmpty()) {
            int p = q.poll();
            int px = p % w, py = p / w;
            if (px > 0 && filled[p - 1] == 0 && fence[p - 1] == 0) {
                filled[p - 1] = 1;
                q.add(p - 1);
            }
            if (px < w - 1 && filled[p + 1] == 0 && fence[p + 1] == 0) {
                filled[p + 1] = 1;
                q.add(p + 1);
            }
            if (py > 0 && filled[p - w] == 0 && fence[p - w] == 0) {
                filled[p - w] = 1;
                q.add(p - w);
            }
            if (py < h - 1 && filled[p + w] == 0 && fence[p + w] == 0) {
                filled[p + w] = 1;
                q.add(p + w);
            }
        }
        return filled;
    }

    private static float[] fastBoxBlur(byte[] src, int w, int h, int r) {
        float[] temp = new float[w * h], dest = new float[w * h];
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += getB(src, i, y, w, h);
            for (int x = 0; x < w; x++) {
                temp[y * w + x] = sum / win;
                sum += (getB(src, x + r + 1, y, w, h) - getB(src, x - r, y, w, h));
            }
        }
        for (int x = 0; x < w; x++) {
            float sum = 0;
            for (int i = -r; i <= r; i++) sum += getF(temp, x, i, w, h);
            for (int y = 0; y < h; y++) {
                dest[y * w + x] = sum / win;
                sum += (getF(temp, x, y + r + 1, w, h) - getF(temp, x, y - r, w, h));
            }
        }
        return dest;
    }

    private static byte getB(byte[] a, int x, int y, int w, int h) {
        return (x < 0 || x >= w || y < 0 || y >= h) ? 0 : a[y * w + x];
    }

    private static float getF(float[] a, int x, int y, int w, int h) {
        return (x < 0 || x >= w || y < 0 || y >= h) ? 0 : a[y * w + x];
    }

    private static void saveImage(int[] pix, String path) throws IOException {
        BufferedImage out = new BufferedImage(FULL_SIZE, FULL_SIZE, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, FULL_SIZE, FULL_SIZE, pix, 0, FULL_SIZE);
        ImageIO.write(out, "PNG", new File(path));
    }
}