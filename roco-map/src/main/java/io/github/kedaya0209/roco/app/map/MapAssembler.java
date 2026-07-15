package io.github.kedaya0209.roco.app.map;

import io.github.kedaya0209.roco.app.map.util.PngImage;
import io.github.kedaya0209.roco.app.map.util.PngImageData;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

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

    private static final int SMOOTH_RADIUS = 30;

    // 虚线颜色 #6B2C25 — 地图上的 mask 边界线，精确颜色（直接从图片提取）
    // 核心思路：虚线围起来的内部是可视区域，外部加云雾
    private static final int LINE_R = 0x6B;
    private static final int LINE_G = 0x2C;
    private static final int LINE_B = 0x25;
    // 虚线膨胀半径 — 将断点虚线连成封闭 barrier
    private static final int DASHED_LINE_DILATE = 15;
    // 全局 fence 膨胀 — 闭合虚线之间、虚线与海岸之间的残留间隙
    private static final int FENCE_DILATE_RADIUS = 30;

    private static final int STACK_DENSITY = 16000;
    private static final float SCALE_MIN = 0.8f;
    private static final float SCALE_MAX = 1.5f;
    private static final float STACK_ALPHA = 0.6f;
    private static final float GLOBAL_FOG_ALPHA = 0.7f;
    private static final int FOG_BLUR_RADIUS = 30;

    public static void main(String[] args) {
        String basePath = "D:\\Documents\\unpack\\map\\";
        String mapDir = basePath + "bigmap\\";
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
                    PngImageData tile = PngImage.readPng(new File(mapDir + String.format("%02d.png", i + 1)));
                    copyTileToBuffer(tile, fullColorPixels, x, y);
                    buildFenceFromTile(tile, fence, x, y);
                } catch (IOException e) {
                    log.error("加载瓦片 {} 失败", i + 1, e);
                }
            });

            log.info("2. 膨胀 fence，闭合虚线间隙...");
            dilateFence(fence, FULL_SIZE, FULL_SIZE, FENCE_DILATE_RADIUS);

            log.info("3. 洪水填充：标记虚线外部为迷雾区域...");
            byte[] outerFogRegion = fastFloodFill(fence, FULL_SIZE, FULL_SIZE);

            log.info("4. 过滤孤立小区域（虚线在海洋中误形成的闭合环）...");
            filterSmallVisibleComponents(outerFogRegion, FULL_SIZE, FULL_SIZE, 0.003f);

            log.info("  保存 mask 图以供检查...");
            saveMaskImage(outerFogRegion, basePath + "WorldMap_Mask.png");

            float[] smoothAlphaMask = fastBoxBlur(outerFogRegion, FULL_SIZE, FULL_SIZE, SMOOTH_RADIUS);

            // --- 任务 A: 生成 SIFT 专用透明陆地图（边缘平滑过渡） ---
            log.info("5. 合成 SIFT 专用图 (保留陆地细节，边缘平滑)...");
            int[] siftPixels = new int[FULL_SIZE * FULL_SIZE];
            IntStream.range(0, FULL_SIZE * FULL_SIZE).parallel().forEach(i -> {
                float maskVal = smoothAlphaMask[i];
                // smoothAlphaMask: 0=陆地, 1=迷雾 → 翻转后做 alpha: 1=陆地完全不透明, 0=迷雾完全透明
                int a = (int) ((1.0f - maskVal) * 255);
                if (a > 1) {
                    int orig = fullColorPixels[i];
                    int r = (orig >> 16) & 0xFF;
                    int g = (orig >> 8) & 0xFF;
                    int b = orig & 0xFF;
                    siftPixels[i] = (Math.min(255, a) << 24) | (r << 16) | (g << 8) | b;
                } else {
                    siftPixels[i] = 0x00000000;
                }
            });
            saveImage(siftPixels, basePath + "WorldMap_Transparent_SIFT.png");

            // --- 任务 B: 生成带迷雾的展示图 ---
            log.info("6. 准备迷雾层纹理...");
            PngImageData texture = PngImage.readPng(new File(texturePath));
            int[] fogRawPixels = createDenseStackLayer(texture);
            log.info("  模糊迷雾层，消除颗粒感...");
            fastBoxBlurARGB(fogRawPixels, FULL_SIZE, FULL_SIZE, FOG_BLUR_RADIUS);

            log.info("7. 并行合成展示图：处理迷雾过渡...");
            int[] displayPixels = new int[FULL_SIZE * FULL_SIZE];

            IntStream.range(0, FULL_SIZE * FULL_SIZE).parallel().forEach(i -> {
                float maskVal = smoothAlphaMask[i];
                int cA = fullColorPixels[i];

                if (maskVal <= 0.001f) {
                    displayPixels[i] = cA;
                } else {
                    int cB = fogRawPixels[i];
                    int aB = (cB >> 24) & 0xFF;

                    float finalAlpha = maskVal * (aB / 255.0f) * GLOBAL_FOG_ALPHA;

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

    private static int[] createDenseStackLayer(PngImageData tex) {
        int[] layer = new int[FULL_SIZE * FULL_SIZE];
        int[] texPixels = tex.pixels();
        int tw = tex.w(), th = tex.h();
        Random r = new Random();
        for (int i = 0; i < STACK_DENSITY; i++) {
            int x = r.nextInt(FULL_SIZE + 400) - 200;
            int y = r.nextInt(FULL_SIZE + 400) - 200;
            float scale = SCALE_MIN + r.nextFloat() * (SCALE_MAX - SCALE_MIN);
            int alpha = (int) (255 * STACK_ALPHA * (0.4f + r.nextFloat() * 0.6f));
            int dw = (int) (tw * scale), dh = (int) (th * scale);
            PngImage.blitScaledAlpha(texPixels, tw, th, layer, FULL_SIZE, FULL_SIZE,
                    x - dw / 2, y - dh / 2, dw, dh, alpha);
        }
        return layer;
    }

    private static void copyTileToBuffer(PngImageData src, int[] dest, int ox, int oy) {
        int[] srcPixels = src.pixels();
        int sw = src.w(), sh = src.h();
        for (int y = 0; y < sh; y++) {
            System.arraycopy(srcPixels, y * sw, dest, (oy + y) * FULL_SIZE + ox, sw);
        }
    }

    /**
     * 从地图图块中检测 #6B2C25 虚线（mask 边界），标记到 fence 数组。
     * 虚线在地图上圈出所有可视区域；虚线内部保留，外部加云雾。
     * 匹配像素做十字膨胀将断点连成连续 barrier，后续全局膨胀进一步闭合间隙。
     */
    private static void buildFenceFromTile(PngImageData tile, byte[] fence, int ox, int oy) {
        int[] pix = tile.pixels();
        int tw = tile.w(), th = tile.h();
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int argb = pix[y * tw + x];
                int a = (argb >> 24) & 0xFF;
                if (a < 10) continue;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                if (r == LINE_R && g == LINE_G && b == LINE_B) {
                    // 十字膨胀，半径 DASHED_LINE_DILATE
                    int fx = x + ox, fy = y + oy;
                    for (int i = -DASHED_LINE_DILATE; i <= DASHED_LINE_DILATE; i++) {
                        int tx = fx + i;
                        if (tx >= 0 && tx < FULL_SIZE) fence[fy * FULL_SIZE + tx] = 1;
                        int ty = fy + i;
                        if (ty >= 0 && ty < FULL_SIZE) fence[ty * FULL_SIZE + fx] = 1;
                    }
                }
            }
        }
    }

    /**
     * 高效二值膨胀（矩形结构元素，O(w*h) 与半径无关）。
     * 先水平方向左右传播，再垂直方向上下传播，闭合 fence 中的小间隙。
     */
    private static void dilateFence(byte[] fence, int w, int h, int r) {
        byte[] temp = new byte[w * h];
        // 水平膨胀（读 fence，写 temp）
        for (int y = 0; y < h; y++) {
            int off = y * w;
            int dist = r + 1;
            for (int x = 0; x < w; x++) {
                if (fence[off + x] == 1) dist = 0;
                if (dist <= r) temp[off + x] = 1;
                dist++;
            }
            dist = r + 1;
            for (int x = w - 1; x >= 0; x--) {
                if (fence[off + x] == 1) dist = 0;
                if (dist <= r) temp[off + x] = 1;
                dist++;
            }
        }
        // 垂直膨胀（读 temp，写回 fence）
        byte[] result = new byte[w * h];
        for (int x = 0; x < w; x++) {
            int dist = r + 1;
            for (int y = 0; y < h; y++) {
                if (temp[y * w + x] == 1) dist = 0;
                if (dist <= r) result[y * w + x] = 1;
                dist++;
            }
            dist = r + 1;
            for (int y = h - 1; y >= 0; y--) {
                if (temp[y * w + x] == 1) dist = 0;
                if (dist <= r) result[y * w + x] = 1;
                dist++;
            }
        }
        System.arraycopy(result, 0, fence, 0, fence.length);
    }

    /**
     * 过滤 outerFogRegion 中孤立的小可视区域（虚线在海洋中误形成的闭合环）。
     * <p>
     * 虚线 fence 可能在海洋中形成多个小型封闭区域，洪水填充无法进入。
     * 此方法对每个 outerFogRegion==0 的连通分量做大小检查，
     * 小于 ratio×total 的标记为 outerFogRegion=1（迷雾）。
     */
    private static void filterSmallVisibleComponents(byte[] fog, int w, int h, float ratio) {
        int total = w * h;
        int minSize = Math.max(1000, (int) (total * ratio));
        boolean[] visited = new boolean[total];
        int[] queue = new int[Math.min(total, 1 << 20)]; // 栈上分配 1M ints 避免 Queue 开销

        for (int i = 0; i < total; i++) {
            if (fog[i] != 0 || visited[i]) continue;

            // BFS 找连通分量
            int head = 0, tail = 0;
            queue[tail++] = i;
            visited[i] = true;
            int start = head; // save for possible rollback

            while (head < tail) {
                int p = queue[head++];
                int px = p % w, py = p / w;
                if (px > 0 && fog[p - 1] == 0 && !visited[p - 1]) { visited[p - 1] = true; queue[tail++] = p - 1; }
                if (px < w - 1 && fog[p + 1] == 0 && !visited[p + 1]) { visited[p + 1] = true; queue[tail++] = p + 1; }
                if (py > 0 && fog[p - w] == 0 && !visited[p - w]) { visited[p - w] = true; queue[tail++] = p - w; }
                if (py < h - 1 && fog[p + w] == 0 && !visited[p + w]) { visited[p + w] = true; queue[tail++] = p + w; }
                if (tail == queue.length) {
                    // 队列满了 — 这个分量太大，不可能小于阈值，直接放弃 BFS 但保留 visited
                    head = tail; // break out
                }
            }

            int size = tail;
            if (size < minSize && tail < queue.length) {
                // 小分量：标记为迷雾
                for (int j = start; j < tail; j++) {
                    fog[queue[j]] = 1;
                }
            }
            // 大分量：已 visited 保留，避免重复处理
        }
    }

    private static byte[] fastFloodFill(byte[] fence, int w, int h) {
        byte[] filled = new byte[w * h];
        Queue<Integer> q = new ArrayDeque<>(w * 4);
        // 种子：整条边缘的所有像素（四角也包含在内）
        // 确保即使是虚线 fence 在边缘处形成闭环，闭环外也能被正确标记为迷雾
        for (int x = 0; x < w; x++) {
            if (fence[x] == 0) { filled[x] = 1; q.add(x); }
            int bottom = (h - 1) * w + x;
            if (fence[bottom] == 0) { filled[bottom] = 1; q.add(bottom); }
        }
        for (int y = 1; y < h - 1; y++) {
            int left = y * w;
            if (fence[left] == 0) { filled[left] = 1; q.add(left); }
            int right = y * w + w - 1;
            if (fence[right] == 0) { filled[right] = 1; q.add(right); }
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

    /**
     * 对 ARGB 像素数组做 fast box blur（各通道独立模糊），用于平滑迷雾层。
     * 直接修改传入数组。
     */
    private static void fastBoxBlurARGB(int[] src, int w, int h, int r) {
        int[] temp = new int[w * h];
        int len = w * h;
        int win = 2 * r + 1;

        // 分离通道
        int[] rBuf = new int[len];
        int[] gBuf = new int[len];
        int[] bBuf = new int[len];
        int[] aBuf = new int[len];
        for (int i = 0; i < len; i++) {
            aBuf[i] = (src[i] >> 24) & 0xFF;
            rBuf[i] = (src[i] >> 16) & 0xFF;
            gBuf[i] = (src[i] >> 8) & 0xFF;
            bBuf[i] = src[i] & 0xFF;
        }

        // 模糊各通道
        blurChannel(aBuf, temp, w, h, r);
        blurChannel(rBuf, temp, w, h, r);
        blurChannel(gBuf, temp, w, h, r);
        blurChannel(bBuf, temp, w, h, r);

        // 合并
        for (int i = 0; i < len; i++) {
            src[i] = (clamp(aBuf[i]) << 24) | (clamp(rBuf[i]) << 16)
                   | (clamp(gBuf[i]) << 8) | clamp(bBuf[i]);
        }
    }

    private static void blurChannel(int[] ch, int[] temp, int w, int h, int r) {
        int win = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int off = y * w;
            long sum = 0;
            for (int i = -r; i <= r; i++) sum += getI(ch, i, y, w, h);
            for (int x = 0; x < w; x++) {
                temp[off + x] = (int)(sum / win);
                sum += getI(ch, x + r + 1, y, w, h) - getI(ch, x - r, y, w, h);
            }
        }
        for (int x = 0; x < w; x++) {
            long sum = 0;
            for (int i = -r; i <= r; i++) sum += getT(temp, x, i, w);
            for (int y = 0; y < h; y++) {
                ch[y * w + x] = (int)(sum / win);
                sum += getT(temp, x, y + r + 1, w) - getT(temp, x, y - r, w);
            }
        }
    }

    private static int getI(int[] a, int x, int y, int w, int h) {
        return (x < 0 || x >= w || y < 0 || y >= h) ? 0 : a[y * w + x];
    }

    private static int getT(int[] a, int x, int y, int w) {
        return (y < 0 || y >= a.length / w) ? 0 : a[y * w + x];
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static byte getB(byte[] a, int x, int y, int w, int h) {
        return (x < 0 || x >= w || y < 0 || y >= h) ? 0 : a[y * w + x];
    }

    private static float getF(float[] a, int x, int y, int w, int h) {
        return (x < 0 || x >= w || y < 0 || y >= h) ? 0 : a[y * w + x];
    }

    private static void saveImage(int[] pix, String path) throws IOException {
        PngImage.writePng(pix, FULL_SIZE, FULL_SIZE, new File(path));
    }

    /** 将 outerFogRegion 保存为黑白 PNG：白=可视区域，黑=迷雾。 */
    private static void saveMaskImage(byte[] fog, String path) throws IOException {
        int[] pix = new int[FULL_SIZE * FULL_SIZE];
        for (int i = 0; i < pix.length; i++) {
            int v = fog[i] == 0 ? 255 : 0;
            pix[i] = (255 << 24) | (v << 16) | (v << 8) | v;
        }
        PngImage.writePng(pix, FULL_SIZE, FULL_SIZE, new File(path));
    }
}