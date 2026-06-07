package io.github.kedaya0209.roco.app.map;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 调试工具 — 把洞穴边缘外 extendPx 像素对应的大陆图区域抠出来保存，
 * 用来检查膨胀选区是否正确。
 *
 * <p>输出：在 maps 目录下生成 {洞穴名}_大陆区域.png，膨胀区域显示大陆图，
 * 其余透明。不修改原洞穴图。
 */
@Slf4j
public class CaveRegionExtractor {

    private static final int[][] DIRS = {{-1,0}, {1,0}, {0,-1}, {0,1}};

    public static void main(String[] args) throws Exception {
        int extendPx = args.length > 0 ? Integer.parseInt(args[0]) : 200;

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
        log.info("加载大陆图: {}", CaveUtils.MAIN_MAP);
        BufferedImage mainMap = ImageIO.read(mainPath.toFile());
        if (mainMap == null) {
            log.error("大陆图加载失败");
            System.exit(1);
        }

        for (Path cavePng : cavePngs) {
            extract(cavePng, mainMap, extendPx, mapsDir);
        }

        log.info("全部提取完成，共 {} 个洞穴", cavePngs.size());
    }

    private static void extract(Path cavePng, BufferedImage mainMap,
                                int extendPx, Path mapsDir) throws IOException {
        String name = cavePng.getFileName().toString();
        log.info("处理: {}", name);

        BufferedImage caveImg = ImageIO.read(cavePng.toFile());
        if (caveImg == null) {
            log.warn("  跳过: 读取失败");
            return;
        }

        int w = caveImg.getWidth();
        int h = caveImg.getHeight();

        // 1. 洞穴遮罩
        boolean[] caveMask = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((caveImg.getRGB(x, y) >> 24) & 0xFF) > 0) {
                    caveMask[y * w + x] = true;
                }
            }
        }

        int wm1 = w - 1, hm1 = h - 1;

        // 2. 膨胀遮罩
        boolean[] dilateMask = caveMask.clone();
        int[] qx = new int[w * h];
        int[] qy = new int[w * h];
        int head = 0, tail = 0;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (!caveMask[y * w + x]) continue;
                for (int[] d : DIRS) {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx < 0 || nx > wm1 || ny < 0 || ny > hm1) continue;
                    if (!caveMask[ny * w + nx]) {
                        qx[tail] = x; qy[tail] = y; tail++;
                        break;
                    }
                }
            }
        }

        for (int layer = 0; layer < extendPx && head < tail; layer++) {
            int layerEnd = tail;
            while (head < layerEnd) {
                int x = qx[head], y = qy[head]; head++;
                for (int[] dir : DIRS) {
                    int nx = x + dir[0], ny = y + dir[1];
                    if (nx < 0 || nx > wm1 || ny < 0 || ny > hm1) continue;
                    int nIdx = ny * w + nx;
                    if (!dilateMask[nIdx]) {
                        dilateMask[nIdx] = true;
                        qx[tail] = nx; qy[tail] = ny; tail++;
                    }
                }
            }
        }

        // 3. 输出：膨胀区域全部从大陆图抠出，暗化，其余透明
        //     用来预览遮罩层效果
        String outName = name.replace(".png", "_大陆区域.png");
        Path outPath = mapsDir.resolve(outName);
        BufferedImage outImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        int darkDiv = Math.max(1, (int) Math.round(1.0 / 0.3));
        int pixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (dilateMask[idx]) {
                    int mainRGB = mainMap.getRGB(x, y);
                    int r = ((mainRGB >> 16) & 0xFF) / darkDiv;
                    int g = ((mainRGB >> 8) & 0xFF) / darkDiv;
                    int b = (mainRGB & 0xFF) / darkDiv;
                    outImg.setRGB(x, y, (255 << 24) | (r << 16) | (g << 8) | b);
                    pixels++;
                }
            }
        }

        ImageIO.write(outImg, "png", outPath.toFile());
        log.info("  输出: {} ({} 像素, 暗化0.3)", outName, pixels);
    }
}
