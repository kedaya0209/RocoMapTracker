package io.github.kedaya0209.roco.app.map;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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
 * <p>思路：把洞穴区域膨胀 extendPx 像素得到遮罩 → 从大陆图裁出该区域并暗化 →
 * 原洞穴图盖在上面。三步完成，不需要距离变换。
 *
 * <p>使用方式：
 * <pre>
 *     mvn compile exec:java -pl roco-map \
 *         -Dexec.mainClass="io.github.kedaya0209.roco.app.map.CaveImageFuser" \
 *         -Dexec.args="200 0.7"
 * </pre>
 * 参数：extendPx（延伸像素数）=200, darkFactor（暗化系数）=0.7
 */
public class CaveImageFuser {

    private static final String MAIN_MAP = "卡洛西亚大陆.png";
    private static final int[][] DIRS = {{-1,0}, {1,0}, {0,-1}, {0,1}};
    private static final int BRIGHTNESS_THRESHOLD = 20; // 无 alpha 透明时用亮度检测

    public static void main(String[] args) throws Exception {
        int extendPx = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        double darkFactor = args.length > 1 ? Double.parseDouble(args[1]) : 0.7;

        // 定位 resources 目录
        String baseDir = System.getProperty("cave-fuser.resources",
                "roco-map/src/main/resources");
        Path mapsDir = Paths.get(baseDir, "source", "maps");
        if (!Files.isDirectory(mapsDir)) {
            System.err.println("目录不存在: " + mapsDir.toAbsolutePath());
            System.exit(1);
        }

        // 收集洞穴 PNG（排除主地图）
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

        // 加载大陆图
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

        // 读取全图 ARGB
        int[] caveARGB = new int[w * h];
        caveImg.getRGB(0, 0, w, h, caveARGB, 0, w);

        // 检查是否有 alpha 透明像素
        boolean hasAlphaGap = false;
        for (int i = 0; i < Math.min(caveARGB.length, 100000); i++) {
            if (((caveARGB[i] >> 24) & 0xFF) == 0) { hasAlphaGap = true; break; }
        }
        System.out.println("  使用 " + (hasAlphaGap ? "alpha 透明检测" : "亮度检测 (黑底)"));

        // 1. 洞穴遮罩：alpha>0 或 亮度>阈值（无透明时）
        boolean[] caveMask = new boolean[w * h];
        int cavePixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int argb = caveARGB[idx];
                boolean isCave;
                if (hasAlphaGap) {
                    isCave = ((argb >> 24) & 0xFF) > 0;
                } else {
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int lum = (r * 299 + g * 587 + b * 114) / 1000;
                    isCave = lum > BRIGHTNESS_THRESHOLD;
                }
                caveMask[idx] = isCave;
                if (isCave) cavePixels++;
            }
        }
        System.out.println("  洞穴像素: " + cavePixels + " / " + (w * h));

        int wm1 = w - 1, hm1 = h - 1;

        // 2. 膨胀遮罩 — BFS 从洞穴边界向外延伸 extendPx 层
        boolean[] dilateMask = caveMask.clone();
        int[] qx = new int[w * h];
        int[] qy = new int[w * h];
        int head = 0, tail = 0;

        // 种子：洞穴边界像素（邻接透明区域的洞穴像素）
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
        System.out.println("  边界种子: " + tail);

        // 逐层膨胀
        int expanded = 0;
        for (int layer = 0; layer < extendPx && head < tail; layer++) {
            int layerEnd = tail;
            int layerAdded = 0;
            while (head < layerEnd) {
                int x = qx[head], y = qy[head]; head++;
                for (int[] dir : DIRS) {
                    int nx = x + dir[0], ny = y + dir[1];
                    if (nx < 0 || nx > wm1 || ny < 0 || ny > hm1) continue;
                    int nIdx = ny * w + nx;
                    if (!dilateMask[nIdx]) {
                        dilateMask[nIdx] = true;
                        qx[tail] = nx; qy[tail] = ny; tail++;
                        layerAdded++;
                    }
                }
            }
            expanded += layerAdded;
        }
        System.out.println("  延伸像素: " + expanded + " (延伸 " + extendPx + "px)");

        // 3. 合成：新建输出图，膨胀区域铺暗化大陆图底，再盖原洞穴图
        BufferedImage outImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        int basePixels = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (dilateMask[idx]) {
                    int mainRGB = mainMap.getRGB(x, y);
                    double darkMul = 1.0 - darkFactor; // 0.5→50%亮度, 0.7→30%亮度
                    int r = (int) (((mainRGB >> 16) & 0xFF) * darkMul);
                    int g = (int) (((mainRGB >> 8) & 0xFF) * darkMul);
                    int b = (int) ((mainRGB & 0xFF) * darkMul);
                    outImg.setRGB(x, y, (255 << 24) | (r << 16) | (g << 8) | b);
                    basePixels++;
                }
            }
        }

        int overlaid = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                if (caveMask[idx]) {
                    int alpha = (caveARGB[idx] >> 24) & 0xFF;
                    int caveR = (caveARGB[idx] >> 16) & 0xFF;
                    int caveG = (caveARGB[idx] >> 8) & 0xFF;
                    int caveB = caveARGB[idx] & 0xFF;
                    int mainARGB = outImg.getRGB(x, y);
                    int mainR = (mainARGB >> 16) & 0xFF;
                    int mainG = (mainARGB >> 8) & 0xFF;
                    int mainB = mainARGB & 0xFF;
                    int outR = (caveR * alpha + mainR * (255 - alpha)) / 255;
                    int outG = (caveG * alpha + mainG * (255 - alpha)) / 255;
                    int outB = (caveB * alpha + mainB * (255 - alpha)) / 255;
                    outImg.setRGB(x, y, (255 << 24) | (outR << 16) | (outG << 8) | outB);
                    overlaid++;
                }
            }
        }
        System.out.println("  底部像素: " + basePixels + ", 洞穴叠加: " + overlaid);

        // 4. 写回
        ImageIO.write(outImg, "png", caveFile);
        System.out.println("  已写回: " + caveFile.getName());
    }
}
