package io.github.kedaya0209.roco.app.map;

import ar.com.hjg.pngj.ImageInfo;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.ImageLineInt;
import ar.com.hjg.pngj.PngReader;
import ar.com.hjg.pngj.PngWriter;
import io.github.kedaya0209.roco.app.map.util.BrightnessExtractor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
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
        log.info("大陆图路径: {} ({} MB)", mainPath, mainPath.toFile().length() / 1024 / 1024);

        for (Path cavePng : cavePngs) {
            fuse(cavePng, mainPath, extendPx, darkFactor);
        }

        log.info("全部处理完成，共 {} 个洞穴", cavePngs.size());
    }

    /**
     * 对洞穴图执行大陆特征融合（直接写回原文件）。
     *
     * <p>内存优化（PNGJ 流式）：
     * <ul>
     *   <li>亮度提取 + CLAHE 均使用 PNGJ 逐行处理</li>
     *   <li>BFS 遮罩使用 BitSet（8MB 而非 boolean[] 的 67MB）</li>
     *   <li>融合阶段双 PngReader + PngWriter 逐行处理，峰值仅 ~8MB</li>
     *   <li>大陆图逐行读取，无需全尺寸加载</li>
     * </ul>
     *
     * @param caveFile   洞穴 PNG 路径
     * @param mainlandPath 大陆图 PNG 路径
     * @param extendPx   膨胀像素数
     * @param darkFactor 暗化系数 (0~1)
     * @throws IOException 读写失败时抛出
     */
    public static void fuse(Path caveFile, Path mainlandPath,
                            int extendPx, double darkFactor) throws IOException {
        String name = caveFile.getFileName().toString();
        log.info("融合洞穴: {}", name);

        // ===== Phase 1: 亮度提取 → 覆盖 caveFile =====
        Path brightPath = caveFile.resolveSibling(name.replace(".png", "_亮度提取.png"));
        BrightnessExtractor.extractAndSave(caveFile, brightPath, 50);
        log.info("  亮度提取中间图已保存: {}", brightPath.getFileName());

        // 文件拷贝代替 ImageIO 解码+编码
        Files.copy(brightPath, caveFile, StandardCopyOption.REPLACE_EXISTING);

        // ===== Phase 2: 逐行读取，构建 alpha BitSet =====
        int w, h, n;
        BitSet cavePixels;

        PngReader reader = null;
        try {
            reader = new PngReader(caveFile.toFile());
            w = reader.imgInfo.cols;
            h = reader.imgInfo.rows;
            n = w * h;
            cavePixels = new BitSet(n);

            for (int y = 0; y < h; y++) {
                int[] rgba = readRowRgba(reader.readRow(), w);
                for (int x = 0; x < w; x++) {
                    if ((rgba[x * 4 + 3] & 0xFF) >= ALPHA_THRESHOLD) {
                        cavePixels.set(y * w + x);
                    }
                }
            }
        } finally {
            if (reader != null) reader.end();
        }

        log.info("  尺寸: {}x{}", w, h);

        // ===== BFS 连通域发现 + 过滤 + 波面膨胀 =====
        BitSet dilated = bfsDilate(cavePixels, w, h, MIN_COMPONENT_SIZE, extendPx);

        int keptCavePixels = 0;
        for (int i = cavePixels.nextSetBit(0); i >= 0; i = cavePixels.nextSetBit(i + 1)) {
            if (dilated.get(i)) keptCavePixels++;
        }
        log.info("  过滤后洞穴像素: {}", keptCavePixels);

        // ===== Phase 3: 大陆特征融合（逐行流式）=====
        String tempName = name.replace(".png", "_fuse.tmp");
        Path tempFile = caveFile.resolveSibling(tempName);

        PngReader caveReader = null;
        PngReader mainlandReader = null;
        PngWriter caveWriter = null;
        try {
            caveReader = new PngReader(caveFile.toFile());
            mainlandReader = new PngReader(mainlandPath.toFile());
            caveWriter = new PngWriter(tempFile.toFile(),
                    new ImageInfo(w, h, 8, true, false, false));

            ImageLineInt outLine = new ImageLineInt(caveWriter.imgInfo);
            int overlaid = 0;

            for (int y = 0; y < h; y++) {
                int[] caveRgba = readRowRgba(caveReader.readRow(), w);
                int[] mainRgba = readRowRgba(mainlandReader.readRow(), w);
                int[] outScan = outLine.getScanline();

                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    if (!dilated.get(idx)) {
                        outScan[x * 4] = caveRgba[x * 4];
                        outScan[x * 4 + 1] = caveRgba[x * 4 + 1];
                        outScan[x * 4 + 2] = caveRgba[x * 4 + 2];
                        outScan[x * 4 + 3] = caveRgba[x * 4 + 3];
                        continue;
                    }

                    int mr = (int) (mainRgba[x * 4] * darkFactor);
                    int mg = (int) (mainRgba[x * 4 + 1] * darkFactor);
                    int mb = (int) (mainRgba[x * 4 + 2] * darkFactor);

                    int caveAlpha = caveRgba[x * 4 + 3];
                    int outR, outG, outB;
                    if (caveAlpha >= ALPHA_THRESHOLD) {
                        int cr = caveRgba[x * 4];
                        int cg = caveRgba[x * 4 + 1];
                        int cb = caveRgba[x * 4 + 2];
                        outR = Math.min(255, (cr * caveAlpha + mr * (255 - caveAlpha)) / 255);
                        outG = Math.min(255, (cg * caveAlpha + mg * (255 - caveAlpha)) / 255);
                        outB = Math.min(255, (cb * caveAlpha + mb * (255 - caveAlpha)) / 255);
                        overlaid++;
                    } else {
                        outR = Math.min(255, mr);
                        outG = Math.min(255, mg);
                        outB = Math.min(255, mb);
                    }
                    outScan[x * 4] = outR;
                    outScan[x * 4 + 1] = outG;
                    outScan[x * 4 + 2] = outB;
                    outScan[x * 4 + 3] = 255;
                }
                caveWriter.writeRow(outLine, y);
            }
            log.info("  洞穴叠加: {} (暗化系数 {})", overlaid, darkFactor);
        } finally {
            if (caveReader != null) caveReader.end();
            if (mainlandReader != null) mainlandReader.end();
            if (caveWriter != null) caveWriter.end();
        }

        // 替换原文件
        Files.move(tempFile, caveFile, StandardCopyOption.REPLACE_EXISTING);
        log.info("  已写回: {}", name);
    }

    /**
     * BFS 连通域发现 + 过滤 + 波面膨胀（在 BitSet 上操作）。
     */
    private static BitSet bfsDilate(BitSet cavePixels, int w, int h,
                                    int minComponentSize, int extendPx) {
        int n = w * h;
        BitSet dilated = new BitSet(n);
        int[] bfsQueue = new int[Math.max(n / 10, 1)];
        ArrayList<Integer> wavefront = new ArrayList<>();
        int[] dirs4 = {-1, 1, -w, w};

        for (int start = 0; start < n; start++) {
            if (!cavePixels.get(start) || dilated.get(start)) continue;

            // BFS 发现连通域
            int qHead = 0, qTail = 0;
            bfsQueue[qTail++] = start;
            dilated.set(start);

            while (qHead < qTail) {
                int idx = bfsQueue[qHead++];
                int x = idx % w;
                for (int d : dirs4) {
                    int ni = idx + d;
                    if (d == -1 && x == 0) continue;
                    if (d == 1 && x == w - 1) continue;
                    if (ni < 0 || ni >= n) continue;
                    if (!dilated.get(ni) && cavePixels.get(ni)) {
                        dilated.set(ni);
                        bfsQueue[qTail++] = ni;
                    }
                }
            }

            int compSize = qTail;
            if (compSize < minComponentSize) {
                for (int j = 0; j < compSize; j++) {
                    dilated.clear(bfsQueue[j]);
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
                    if (!dilated.get(ni)) {
                        dilated.set(ni);
                        wavefront.add(ni);
                    }
                }
            }
        }

        // 波面膨胀 extendPx 层
        ArrayList<Integer> nextWave = new ArrayList<>();
        for (int layer = 1; layer < extendPx && !wavefront.isEmpty(); layer++) {
            for (int q : wavefront) {
                int x = q % w;
                for (int d : dirs4) {
                    int ni = q + d;
                    if (d == -1 && x == 0) continue;
                    if (d == 1 && x == w - 1) continue;
                    if (ni < 0 || ni >= n) continue;
                    if (!dilated.get(ni)) {
                        dilated.set(ni);
                        nextWave.add(ni);
                    }
                }
            }
            ArrayList<Integer> tmp = wavefront;
            wavefront = nextWave;
            nextWave = tmp;
            nextWave.clear();
        }

        return dilated;
    }

    private static int[] readRowRgba(ar.com.hjg.pngj.IImageLine line, int w) {
        int[] rgba = new int[w * 4];
        if (line instanceof ImageLineByte byteLine) {
            byte[] src = byteLine.getScanlineByte();
            for (int i = 0; i < w * 4; i++) rgba[i] = src[i] & 0xFF;
        } else if (line instanceof ImageLineInt intLine) {
            int[] src = intLine.getScanline();
            System.arraycopy(src, 0, rgba, 0, w * 4);
        } else {
            Arrays.fill(rgba, 0);
        }
        return rgba;
    }
}
