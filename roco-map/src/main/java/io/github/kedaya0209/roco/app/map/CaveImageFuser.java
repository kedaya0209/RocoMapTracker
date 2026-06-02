package io.github.kedaya0209.roco.app.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 洞穴图融合工具 — 将洞穴透明图边缘外的透明区域填充暗化的大陆图特征，
 * 使 SIFT 在洞穴边缘附近有可匹配特征。
 *
 * <p>思路：二值化洞穴遮罩 → BFS 连通域发现 & 过滤（去孤立小点）→
 * BFS 波面膨胀（extendPx 层）→ 从大陆图抠出膨胀区域并暗化 → 洞穴内容叠加。
 *
 * <p>使用方式：
 * <pre>
 *     mvn compile exec:java -pl roco-map \
 *         -Dexec.mainClass="io.github.kedaya0209.roco.app.map.CaveImageFuser" \
 *         -Dexec.args="30 0.3"
 * </pre>
 * 注意：8192x8192 需要约 1GB 堆内存，建议加 MAVEN_OPTS：
 * <pre>
 *     MAVEN_OPTS=-Xmx1g mvn compile exec:java -pl roco-map \
 *         -Dexec.mainClass="io.github.kedaya0209.roco.app.map.CaveImageFuser" \
 *         -Dexec.args="30 0.3"
 * </pre>
 * 参数：extendPx（膨胀像素数）=30, darkFactor（暗化系数）=0.3
 */
public class CaveImageFuser {

    private static final String MAIN_MAP = "卡洛西亚大陆.png";
    private static final int BRIGHTNESS_THRESHOLD = 20;
    private static final int ALPHA_THRESHOLD = 50;
    private static final int MIN_COMPONENT_SIZE = 200;

    public static void main(String[] args) throws Exception {
        int extendPx = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        double darkFactor = args.length > 1 ? Double.parseDouble(args[1]) : 0.3;

        String baseDir = System.getProperty("cave-fuser.resources",
                "RocoMapTracker/roco-map/src/main/resources");
        Path mapsDir = Paths.get(baseDir, "source", "maps");
        if (!Files.isDirectory(mapsDir)) {
            System.err.println("目录不存在: " + mapsDir.toAbsolutePath());
            System.exit(1);
        }

        List<Path> cavePngs = new ArrayList<>();
        try (var stream = Files.list(mapsDir)) {
            stream.filter(p -> p.toString().endsWith(".png")
                            && !p.getFileName().toString().equals(MAIN_MAP)
                            && !p.getFileName().toString().contains("_大陆区域"))
                  .sorted().forEach(cavePngs::add);
        }

        if (cavePngs.isEmpty()) {
            System.out.println("未找到洞穴 PNG");
            return;
        }

        Path mainPath = mapsDir.resolve(MAIN_MAP);
        System.out.println("加载大陆图: " + MAIN_MAP + " (" + (mainPath.toFile().length() / 1024 / 1024) + " MB)");
        BufferedImage mainMap = ImageIO.read(mainPath.toFile());
        if (mainMap == null) {
            System.err.println("大陆图加载失败");
            System.exit(1);
        }
        System.out.println("大陆图: " + mainMap.getWidth() + "x" + mainMap.getHeight());

        for (Path cavePng : cavePngs) {
            processCave(cavePng.toFile(), mainMap, extendPx, darkFactor);
        }

        System.out.println("全部处理完成，共 " + cavePngs.size() + " 个洞穴");
    }

    private static void processCave(File caveFile, BufferedImage mainMap,
                                    int extendPx, double darkFactor) throws IOException {
        String name = caveFile.getName();
        System.out.println("处理洞穴: " + name);

        BufferedImage caveImg = ImageIO.read(caveFile);
        if (caveImg == null) {
            System.out.println("跳过: " + name + " 读取失败");
            return;
        }

        int w = caveImg.getWidth();
        int h = caveImg.getHeight();
        if (w != mainMap.getWidth() || h != mainMap.getHeight()) {
            System.out.println("尺寸不匹配: " + name + " (" + w + "x" + h + "), 大陆图 (" +
                    mainMap.getWidth() + "x" + mainMap.getHeight() + ")");
            return;
        }

        System.out.println("  尺寸: " + w + "x" + h);

        int n = w * h;

        // 读取全图 ARGB
        int[] caveARGB = new int[n];
        caveImg.getRGB(0, 0, w, h, caveARGB, 0, w);

        boolean hasAlphaGap = false;
        for (int i = 0; i < Math.min(n, 100000); i++) {
            if (((caveARGB[i] >> 24) & 0xFF) == 0) { hasAlphaGap = true; break; }
        }
        System.out.println("  使用 " + (hasAlphaGap ? "alpha 透明检测" : "亮度检测 (黑底)"));

        // ============ 1. 二值化遮罩 ============
        boolean[] caveMask = new boolean[n];
        int cavePixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int argb = caveARGB[idx];
                boolean isCave;
                if (hasAlphaGap) {
                    isCave = ((argb >> 24) & 0xFF) >= ALPHA_THRESHOLD;
                } else {
                    isCave = ((argb >> 16) & 0xFF) * 299 + ((argb >> 8) & 0xFF) * 587 + (argb & 0xFF) * 114 > BRIGHTNESS_THRESHOLD * 1000;
                }
                caveMask[idx] = isCave;
                if (isCave) cavePixels++;
            }
        }
        System.out.println("  洞穴像素: " + cavePixels + " / " + n);

        // ============ 2. BFS 连通域发现 + 波面膨胀 ============
        // dilated[] 输出：true = 在膨胀区内（含洞穴本身）
        boolean[] dilated = new boolean[n];
        // BFS 队列：最大洞穴像素数，只需 tiny 分配
        int[] bfsQueue = new int[Math.max(cavePixels, 1)];
        // 当前层波面
        ArrayList<Integer> wavefront = new ArrayList<>();

        int[] dirs4 = {-1, 1, -w, w}; // 左、右、上、下

        for (int start = 0; start < n; start++) {
            if (!caveMask[start] || dilated[start]) continue;

            // BFS 发现连通分量
            int qHead = 0, qTail = 0;
            bfsQueue[qTail++] = start;
            dilated[start] = true;

            while (qHead < qTail) {
                int idx = bfsQueue[qHead++];
                int x = idx % w;
                for (int d : dirs4) {
                    int ni = idx + d;
                    // 边界检查
                    if (d == -1 && x == 0) continue;
                    if (d == 1 && x == w - 1) continue;
                    if (ni < 0 || ni >= n) continue;
                    if (caveMask[ni] && !dilated[ni]) {
                        dilated[ni] = true;
                        bfsQueue[qTail++] = ni;
                    }
                }
            }

            int compSize = qTail;
            if (compSize < MIN_COMPONENT_SIZE) {
                // 小连通域：回滚 dilated，丢弃
                for (int j = 0; j < compSize; j++) {
                    dilated[bfsQueue[j]] = false;
                }
                continue;
            }

            // 大连通域：从所有边界像素开始波面，计入第 1 层
            for (int j = 0; j < compSize; j++) {
                int idx = bfsQueue[j];
                int x = idx % w;
                for (int d : dirs4) {
                    int ni = idx + d;
                    if (d == -1 && x == 0) continue;
                    if (d == 1 && x == w - 1) continue;
                    if (ni < 0 || ni >= n) continue;
                    if (!caveMask[ni] && !dilated[ni]) {
                        dilated[ni] = true;
                        wavefront.add(ni);
                    }
                }
            }
        }

        int afterFilter = 0;
        for (int i = 0; i < n; i++) {
            if (dilated[i] && !caveMask[i]) afterFilter++; // 非洞穴的膨胀像素
        }
        int keptCavePixels = 0;
        for (int i = 0; i < n; i++) {
            if (dilated[i] && caveMask[i]) keptCavePixels++;
        }
        System.out.println("  过滤后洞穴像素: " + keptCavePixels + ", 初始波面: " + wavefront.size());

        // 继续膨胀 extendPx-1 层
        int totalDilated = keptCavePixels + wavefront.size();
        ArrayList<Integer> nextWave = new ArrayList<>();
        for (int layer = 1; layer < extendPx && !wavefront.isEmpty(); layer++) {
            for (int q : wavefront) {
                int x = q % w;
                for (int d : dirs4) {
                    int ni = q + d;
                    if (d == -1 && x == 0) continue;
                    if (d == 1 && x == w - 1) continue;
                    if (ni < 0 || ni >= n) continue;
                    if (!dilated[ni]) {
                        dilated[ni] = true;
                        nextWave.add(ni);
                    }
                }
            }
            totalDilated += nextWave.size();
            // swap
            ArrayList<Integer> tmp = wavefront;
            wavefront = nextWave;
            nextWave = tmp;
            nextWave.clear();
        }
        System.out.println("  膨胀后总像素 (extendPx=" + extendPx + "): " + totalDilated + " / " + n);

        // 释放 caveMask 内存，为 outImg 腾空间
        caveMask = null;

        // ============ 3. 填充 + 叠加（单遍） ============
        BufferedImage outImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int basePixels = 0, overlaid = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (!dilated[idx]) continue;

                int mainRGB = mainMap.getRGB(x, y);
                int mr = (int) (((mainRGB >> 16) & 0xFF) * darkFactor);
                int mg = (int) (((mainRGB >> 8) & 0xFF) * darkFactor);
                int mb = (int) ((mainRGB & 0xFF) * darkFactor);

                int alpha = (caveARGB[idx] >> 24) & 0xFF;
                if (alpha >= ALPHA_THRESHOLD) {
                    int cr = (caveARGB[idx] >> 16) & 0xFF;
                    int cg = (caveARGB[idx] >> 8) & 0xFF;
                    int cb = caveARGB[idx] & 0xFF;
                    int or = (cr * alpha + mr * (255 - alpha)) / 255;
                    int og = (cg * alpha + mg * (255 - alpha)) / 255;
                    int ob = (cb * alpha + mb * (255 - alpha)) / 255;
                    outImg.setRGB(x, y, (255 << 24) | (or << 16) | (og << 8) | ob);
                    overlaid++;
                } else {
                    outImg.setRGB(x, y, (255 << 24) | (mr << 16) | (mg << 8) | mb);
                }
                basePixels++;
            }
        }
        System.out.println("  填充像素: " + basePixels + ", 洞穴叠加: " + overlaid + " (暗化系数 " + darkFactor + ")");

        ImageIO.write(outImg, "png", caveFile);
        System.out.println("  已写回: " + caveFile.getName());
    }
}
