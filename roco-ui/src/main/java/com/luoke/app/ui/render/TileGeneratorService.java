package com.luoke.app.ui.render;

import com.luoke.app.context.MapContext;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 瓦片生成服务 — 多分辨率金字塔瓦片的校验与生成。
 * 无状态设计，所有输入通过参数传递。
 * 从 ModernCanvasApp 拆分，遵循单一职责原则。
 */
@Slf4j
public class TileGeneratorService {

    /** 瓦片层级元数据 */
    private record LevelInfo(int level, int cols, int rows, int total) {}

    /**
     * 检查各层级瓦片完整性，缺失的从源 PNG 多线程生成。
     * 通过 tiles_meta.json 元数据快速校验，避免逐层 list 文件。
     */
    public void validateAndGenerateTiles() throws IOException {
        String externalPath = ResourceUtils.getExternalPath(ResourceConfigContext.getShowMap(), false);
        File sourceFile = new File(externalPath);

        int mapW = (int) MapContext.getInstance().getMapWidth();
        int mapH = (int) MapContext.getInstance().getMapHeight();
        int tileSize = 256;

        List<LevelInfo> levels = new ArrayList<>();
        for (int lv = 0; lv < 5; lv++) {
            int cols = (int) Math.ceil((double) mapW / (tileSize * (1 << lv)));
            int rows = (int) Math.ceil((double) mapH / (tileSize * (1 << lv)));
            levels.add(new LevelInfo(lv, cols, rows, cols * rows));
        }
        File metaFile = ResourceUtils.getExternalFile(ResourceConfigContext.getTilesDir() + "/tiles_meta.json");
        if (metaFile.exists() && quickValidate(levels)) {
            log.info("瓦片元数据校验通过，跳过生成");
            return;
        }
        if (!sourceFile.exists()) {
            log.error("源 PNG 不存在: {}", sourceFile.getAbsolutePath());
            return;
        }

        log.info("开始生成瓦片金字塔...");

        // 1. 加载源图一次
        BufferedImage sourceImage = ImageIO.read(sourceFile);
        int srcW = sourceImage.getWidth();
        int srcH = sourceImage.getHeight();

        int threads = Runtime.getRuntime().availableProcessors();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            for (LevelInfo li : levels) {
                // 2. 对该级别缩放一次
                double factor = 1.0 / (1 << li.level);
                BufferedImage levelImage;
                if (li.level == 0) {
                    levelImage = sourceImage;
                } else {
                    int lw = (int) Math.ceil(srcW * factor);
                    int lh = (int) Math.ceil(srcH * factor);
                    levelImage = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = levelImage.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(sourceImage, 0, 0, lw, lh, null);
                    g.dispose();
                }

                File levelDir = ResourceUtils.getExternalFile(
                        ResourceConfigContext.getTilesDir() + "/" + li.level);
                levelDir.mkdirs();

                // 3. 从缩放图裁剪子图，多线程保存
                int tileWorldSize = tileSize * (1 << li.level);
                for (int row = 0; row < li.rows; row++) {
                    for (int col = 0; col < li.cols; col++) {
                        File tileFile = new File(levelDir, row + "_" + col + ".png");
                        if (tileFile.exists()) continue;

                        int x = col * tileSize;
                        int y = row * tileSize;
                        int w = Math.min(tileSize, levelImage.getWidth() - x);
                        int h = Math.min(tileSize, levelImage.getHeight() - y);
                        if (w <= 0 || h <= 0) continue;

                        BufferedImage tile = levelImage.getSubimage(x, y, w, h);
                        futures.add(executor.submit(() -> {
                            try {
                                ImageIO.write(tile, "png", tileFile);
                            } catch (IOException e) {
                                log.warn("瓦片保存失败: {}", tileFile, e);
                            }
                        }));
                    }
                }

                if (li.level > 0) {
                    levelImage.flush();
                }
            }

            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        }

        log.info("瓦片生成完成");
        writeMetaFile(metaFile, mapW, mapH, tileSize, levels);
    }

    /** 快速校验：比对元数据中各级别瓦片数与实际文件数 */
    private boolean quickValidate(List<LevelInfo> levels) {
        for (LevelInfo li : levels) {
            File levelDir = ResourceUtils.getExternalFile(
                    Path.of(ResourceConfigContext.getTilesDir(), String.valueOf(li.level)).toString());
            if (!levelDir.isDirectory()) return false;
            int actual = levelDir.list((d, n) -> n.endsWith(".png")).length;
            if (actual < li.total) {
                log.warn("瓦片 Level {} 不完整: {}/{}", li.level, actual, li.total);
                return false;
            }
        }
        return true;
    }

    /** 写入瓦片元数据 JSON */
    private void writeMetaFile(File metaFile, int mapW, int mapH, int tileSize,
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
        java.nio.file.Files.writeString(metaFile.toPath(), sb.toString());
        log.info("瓦片元数据已写入: {}", metaFile);
    }
}
