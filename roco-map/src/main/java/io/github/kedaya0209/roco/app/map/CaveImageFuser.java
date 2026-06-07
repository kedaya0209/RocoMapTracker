package io.github.kedaya0209.roco.app.map;

import io.github.kedaya0209.roco.app.map.util.BrightnessExtractor;
import lombok.extern.slf4j.Slf4j;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
@Slf4j
public class CaveImageFuser {

    private static final int ALPHA_THRESHOLD = 50;
    private static final int MIN_COMPONENT_SIZE = 200;

    public static void main(String[] args) throws Exception {
        int extendPx = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        double darkFactor = args.length > 1 ? Double.parseDouble(args[1]) : 0.3;

        String baseDir = System.getProperty("cave-fuser.resources",
                "RocoMapTracker/roco-map/src/main/resources");
        Path mapsDir = Paths.get(baseDir, "source", "maps");
        if (!Files.isDirectory(mapsDir)) {
            log.error("目录不存在: {}", mapsDir.toAbsolutePath());
            System.exit(1);
        }

        List<Path> cavePngs = CaveUtils.findCavePngs(mapsDir);
        if (cavePngs.isEmpty()) {
            log.info("未找到洞穴 PNG");
            return;
        }

        Path mainPath = mapsDir.resolve(CaveUtils.MAIN_MAP);
        log.info("加载大陆图: {} ({} MB)", CaveUtils.MAIN_MAP, mainPath.toFile().length() / 1024 / 1024);
        BufferedImage mainMap = ImageIO.read(mainPath.toFile());
        if (mainMap == null) {
            log.error("大陆图加载失败");
            System.exit(1);
        }
        log.info("大陆图: {}x{}", mainMap.getWidth(), mainMap.getHeight());

        for (Path cavePng : cavePngs) {
            fuse(cavePng, mainMap, extendPx, darkFactor);
        }

        log.info("全部处理完成，共 {} 个洞穴", cavePngs.size());
    }

    /**
     * 对洞穴图执行大陆特征融合（直接写回原文件）。
     *
     * @param caveFile   洞穴 PNG 路径
     * @param mainMap    大陆图（全尺寸 BufferedImage）
     * @param extendPx   膨胀像素数
     * @param darkFactor 暗化系数 (0~1)
     * @throws IOException 读写失败时抛出
     */
    public static void fuse(Path caveFile, BufferedImage mainMap,
                            int extendPx, double darkFactor) throws IOException {
        String name = caveFile.getFileName().toString();
        log.info("融合洞穴: {}", name);

        BufferedImage caveImg = ImageIO.read(caveFile.toFile());
        if (caveImg == null) {
            log.warn("跳过: {} 读取失败", name);
            return;
        }

        int w = caveImg.getWidth();
        int h = caveImg.getHeight();
        if (w != mainMap.getWidth() || h != mainMap.getHeight()) {
            log.warn("尺寸不匹配: {} ({}x{}), 大陆图 ({}x{})",
                    name, w, h, mainMap.getWidth(), mainMap.getHeight());
            return;
        }

        log.info("  尺寸: {}x{}", w, h);

        int n = w * h;

        // 读取全图 ARGB
        int[] caveARGB = new int[n];
        caveImg.getRGB(0, 0, w, h, caveARGB, 0, w);

        // 亮度提取：将暗色背景/缝隙置为透明，分离各洞穴区域（对已有透明区域的图无影响）
        Path brightPath = caveFile.resolveSibling(name.replace(".png", "_亮度提取.png"));
        BrightnessExtractor.extractAndSave(caveFile, brightPath, 50);
        log.info("  亮度提取中间图已保存: {}", brightPath.getFileName());

        // 用提取后的透明图替换原图
        BufferedImage brightImg = ImageIO.read(brightPath.toFile());
        brightImg.getRGB(0, 0, w, h, caveARGB, 0, w);
        ImageIO.write(brightImg, "png", caveFile.toFile());
        brightImg.flush();

        // 对已分离透明背景的图做 CLAHE 增强
        CaveImageEnhancer.enhance(caveFile);

        // 重新读取 CLAHE 增强后的像素
        BufferedImage enhancedImg = ImageIO.read(caveFile.toFile());
        enhancedImg.getRGB(0, 0, w, h, caveARGB, 0, w);
        enhancedImg.flush();

        log.info("  使用 alpha 透明检测");

        // ============ 1. 二值化遮罩 ============
        boolean[] caveMask = new boolean[n];
        int cavePixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int argb = caveARGB[idx];
                if (((argb >> 24) & 0xFF) >= ALPHA_THRESHOLD) {
                    caveMask[idx] = true;
                    cavePixels++;
                }
            }
        }
        log.info("  洞穴像素: {} / {}", cavePixels, n);

        // ============ 2. BFS 连通域发现 + 波面膨胀 ============
        boolean[] dilated = new boolean[n];
        int[] bfsQueue = new int[Math.max(cavePixels, 1)];
        ArrayList<Integer> wavefront = new ArrayList<>();

        int[] dirs4 = {-1, 1, -w, w};

        for (int start = 0; start < n; start++) {
            if (!caveMask[start] || dilated[start]) continue;

            int qHead = 0, qTail = 0;
            bfsQueue[qTail++] = start;
            dilated[start] = true;

            while (qHead < qTail) {
                int idx = bfsQueue[qHead++];
                int x = idx % w;
                for (int d : dirs4) {
                    int ni = idx + d;
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
                for (int j = 0; j < compSize; j++) {
                    dilated[bfsQueue[j]] = false;
                }
                continue;
            }

            // 大连通域边界 → 初始波面
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

        int keptCavePixels = 0;
        for (int i = 0; i < n; i++) {
            if (dilated[i] && caveMask[i]) keptCavePixels++;
        }
        log.info("  过滤后洞穴像素: {}, 初始波面: {}", keptCavePixels, wavefront.size());

        // 继续膨胀
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
            ArrayList<Integer> tmp = wavefront;
            wavefront = nextWave;
            nextWave = tmp;
            nextWave.clear();
        }

        caveMask = null;

        // ============ 3. 填充 + 叠加 ============
        BufferedImage outImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int overlaid = 0;
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
            }
        }
        log.info("  洞穴叠加: {} (暗化系数 {})", overlaid, darkFactor);

        ImageIO.write(outImg, "png", caveFile.toFile());
        log.info("  已写回: {}", name);
    }
}
