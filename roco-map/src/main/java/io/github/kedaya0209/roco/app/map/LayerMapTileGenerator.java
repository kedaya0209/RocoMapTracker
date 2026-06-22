package io.github.kedaya0209.roco.app.map;

import io.github.kedaya0209.roco.app.map.util.PngImage;
import io.github.kedaya0209.roco.app.map.util.PngImageData;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 遮罩层瓦片生成工具 — 将 8192×8192 遮罩 PNG 切割为 5 级瓦片金字塔。
 * 参考 TileGeneratorService 的多分辨率瓦片逻辑。
 * <p>
 * 使用示例：
 * <pre>{@code
 *   // 单个生成
 *   new LayerMapTileGenerator().generateTiles(
 *       "D:\\unpack\\map\\layermap_output\\信仰者村落_遮罩.png",
 *       "D:\\unpack\\map\\tiles\\信仰者村落");
 *
 *   // 批量生成 layermap_output 下所有 _遮罩.png
 *   LayerMapTileGenerator.main(new String[]{
 *       "D:\\Documents\\unpack\\map\\layermap_output",
 *       "D:\\Documents\\unpack\\map\\tiles"
 *   });
 * }</pre>
 */
@Slf4j
public class LayerMapTileGenerator {

    /** 默认瓦片尺寸 */
    private static final int DEFAULT_TILE_SIZE = 256;
    /** 默认金字塔层级数（0~4，共 5 级） */
    private static final int DEFAULT_MAX_LEVEL = 4;

    private final int tileSize;
    private final int maxLevel;

    public LayerMapTileGenerator() {
        this(DEFAULT_TILE_SIZE, DEFAULT_MAX_LEVEL);
    }

    public LayerMapTileGenerator(int tileSize, int maxLevel) {
        this.tileSize = tileSize;
        this.maxLevel = maxLevel;
    }

    // ===================== 单个生成 =====================

    /**
     * 对单张源 PNG 生成多分辨率瓦片。
     *
     * @param sourcePng  源图路径（如 信仰者村落_遮罩.png）
     * @param outputDir  瓦片输出目录（如 tiles/信仰者村落）
     */
    public void generateTiles(String sourcePng, String outputDir) throws IOException {
        File srcFile = new File(sourcePng);
        if (!srcFile.exists()) {
            log.error("源图不存在: {}", sourcePng);
            return;
        }

        PngImageData src = PngImage.readPng(srcFile);
        int srcW = src.w();
        int srcH = src.h();
        int[] srcPixels = src.pixels();

        List<LevelInfo> levels = new ArrayList<>();
        for (int lv = 0; lv <= maxLevel; lv++) {
            int cols = (int) Math.ceil((double) srcW / (tileSize * (1 << lv)));
            int rows = (int) Math.ceil((double) srcH / (tileSize * (1 << lv)));
            levels.add(new LevelInfo(lv, cols, rows, cols * rows));
        }

        log.info("生成瓦片: {} ({}×{}, {} 级)", srcFile.getName(), srcW, srcH, levels.size());

        int threads = Runtime.getRuntime().availableProcessors();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();

            // 级联缩放：每级从前一级缩放到当前级，避免每次都从原始大图缩放
            int[] prevPixels = srcPixels;
            int prevW = srcW;
            int prevH = srcH;

            for (LevelInfo li : levels) {
                int[] levelPixels;
                int lw, lh;
                if (li.level == 0) {
                    lw = srcW;
                    lh = srcH;
                    levelPixels = srcPixels;
                } else {
                    lw = prevW / 2;
                    lh = prevH / 2;
                    if (lw <= 0 || lh <= 0) break;
                    levelPixels = new int[lw * lh];
                    PngImage.blitScaled(prevPixels, prevW, prevH, levelPixels, lw, lh, 0, 0, lw, lh);
                }

                File levelDir = new File(outputDir, String.valueOf(li.level));
                levelDir.mkdirs();

                for (int row = 0; row < li.rows; row++) {
                    for (int col = 0; col < li.cols; col++) {
                        File tileFile = new File(levelDir, row + "_" + col + ".png");
                        if (tileFile.exists()) continue;

                        int x = col * tileSize;
                        int y = row * tileSize;
                        int w = Math.min(tileSize, lw - x);
                        int h = Math.min(tileSize, lh - y);
                        if (w <= 0 || h <= 0) continue;

                        int[] tile = PngImage.extractSubImage(levelPixels, lw, x, y, w, h);
                        futures.add(executor.submit(() -> {
                            try {
                                PngImage.writePng(tile, w, h, tileFile);
                            } catch (IOException e) {
                                log.warn("瓦片保存失败: {}", tileFile, e);
                            }
                        }));
                    }
                }

                // 更新级联状态，供下一级缩放使用
                if (li.level > 0) {
                    prevPixels = levelPixels;
                    prevW = lw;
                    prevH = lh;
                }
            }

            for (Future<?> f : futures) {
                try { f.get(); } catch (InterruptedException | ExecutionException ignored) {}
            }
        }

        writeMetaFile(new File(outputDir, "tiles_meta.json"), srcW, srcH, levels);
        log.info("瓦片生成完成: {}", outputDir);
    }

    // ===================== 批量生成 =====================

    /**
     * 批量处理目录下所有 _遮罩.png 文件。
     *
     * @param inputDir    输入目录（存放 _遮罩.png 文件）
     * @param tileBaseDir 瓦片根目录（每个源图一个子目录）
     */
    public void batchGenerate(String inputDir, String tileBaseDir) throws IOException {
        File inDir = new File(inputDir);
        if (!inDir.isDirectory()) {
            log.error("输入目录不存在: {}", inputDir);
            return;
        }

        File[] files = inDir.listFiles((_, name) -> name.endsWith("_遮罩.png"));
        if (files == null || files.length == 0) {
            log.warn("未找到 _遮罩.png 文件: {}", inputDir);
            return;
        }

        for (File f : files) {
            String name = f.getName().replace("_遮罩.png", "");
            String outDir = tileBaseDir + File.separator + name;
            generateTiles(f.getAbsolutePath(), outDir);
        }

        log.info("批量瓦片生成全部完成，共 {} 个", files.length);
    }

    // ===================== 元数据 =====================

    private void writeMetaFile(File metaFile, int mapW, int mapH,
                               List<LevelInfo> levels) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"mapWidth\": ").append(mapW).append(",\n");
        sb.append("  \"mapHeight\": ").append(mapH).append(",\n");
        sb.append("  \"tileSize\": ").append(tileSize).append(",\n");
        sb.append("  \"levels\": [\n");
        for (int i = 0; i < levels.size(); i++) {
            LevelInfo li = levels.get(i);
            sb.append("    {\"level\": ").append(li.level)
                    .append(", \"cols\": ").append(li.cols)
                    .append(", \"rows\": ").append(li.rows)
                    .append(", \"total\": ").append(li.total).append("}");
            if (i < levels.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.writeString(metaFile.toPath(), sb.toString());
        log.info("元数据已写入: {}", metaFile);
    }

    // ===================== 数据类 =====================

    private record LevelInfo(int level, int cols, int rows, int total) {}

    // ===================== 入口 =====================

    public static void main(String[] args) throws IOException {
        if (args.length >= 1 && args[0].endsWith(".png")) {
            // 单文件模式
            String src = args[0];
            String out = args.length > 1 ? args[1]
                    : src.replace(".png", "_tiles");
            new LayerMapTileGenerator().generateTiles(src, out);
        } else {
            // 批量模式：处理目录下所有 _遮罩.png
            String inputDir = args.length > 0 ? args[0]
                    : "D:\\Documents\\unpack\\map\\layermap_output";
            String tileBaseDir = args.length > 1 ? args[1]
                    : "D:\\Documents\\unpack\\map\\tiles";
            new LayerMapTileGenerator().batchGenerate(inputDir, tileBaseDir);
        }
    }
}
