package io.github.kedaya0209.roco.app.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.map.model.LayerMapLayer;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批量渲染 6 组 LayerMap：每组生成 8192×8192 透明图 + 遮罩图。
 * <pre>
 * 分组：
 *   信仰者村落一层 — id=4
 *   信仰者村落二层 — id=5
 *   拾荒者港口    — id=7,8
 *   月兔暗港      — id=12,16
 *   二叠山丘一层  — id=10
 *   下水管道口    — id=2
 * </pre>
 */
@Slf4j
public class LayerMapBatchRenderer {

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    private static final int MAP_SIZE = 8192;
    private static final double MAP_CENTER_X = 510000;
    private static final double MAP_CENTER_Y = 612000;
    private static final double SIDE_LENGTH = 408000;
    private static final double ORIGIN_X = MAP_CENTER_X - SIDE_LENGTH / 2;
    private static final double ORIGIN_Y = MAP_CENTER_Y - SIDE_LENGTH / 2;
    private static final double SCALE = SIDE_LENGTH / MAP_SIZE;
    private static final int OVERLAY_ALPHA = 90;

    private final String configPath;
    private final String layermapDir;
    private final String siftPath;
    private final String outputDir;

    @Data
    private static class LayerGroup {
        private final String name;
        private final List<LayerMapLayer> layers;
    }

    public LayerMapBatchRenderer(String mapDir, String outputDir) {
        String base = mapDir.endsWith("\\") || mapDir.endsWith("/") ? mapDir : mapDir + File.separator;
        this.configPath = base + "layermap_config.json";
        this.layermapDir = base + "layermap" + File.separator;
        this.siftPath = base + "卡洛西亚大陆.png";
        this.outputDir = outputDir.endsWith("\\") || outputDir.endsWith("/") ? outputDir : outputDir + File.separator;
    }

    public void render() throws IOException {
        new File(outputDir).mkdirs();

        // 1. 加载所有图层
        List<LayerMapLayer> allLayers = loadAllLayers();

        // 2. 构建 6 个分组（信仰者村落拆为一层/二层）
        List<LayerGroup> groups = Arrays.asList(
                group("信仰者村落一层", allLayers, 4),
                group("信仰者村落二层", allLayers, 5),
                group("拾荒者港口", allLayers, 7, 8),
                group("月兔暗港", allLayers, 12, 16),
                group("二叠山丘一层", allLayers, 10),
                group("下水管道口", allLayers, 2)
        );

        // 3. 构建共享遮罩
        log.info("构建陆地半透明遮罩...");
        BufferedImage maskOverlay = buildMaskOverlay();

        // 4. 逐组渲染
        for (LayerGroup g : groups) {
            log.info("渲染: {} ({} 个图层)", g.name, g.layers.size());

            // 透明图
            BufferedImage transparent = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
            drawLayers(transparent, g.layers);
            ImageIO.write(transparent, "PNG", new File(outputDir + g.name + "_透明.png"));
            log.info("  透明图已保存");

            // 遮罩图
            BufferedImage masked = copyImage(maskOverlay);
            drawLayers(masked, g.layers);
            ImageIO.write(masked, "PNG", new File(outputDir + g.name + "_遮罩.png"));
            log.info("  遮罩图已保存");
        }

        // 5. 额外生成合并的 信仰者村落（层4+5），仅用于瓦片生成
        //    单独的一层/二层图保留作为 SIFT 训练源图
        LayerGroup merged = group("信仰者村落", allLayers, 4, 5);
        if (!merged.layers.isEmpty()) {
            log.info("渲染合并: 信仰者村落 (用于瓦片生成)");

            BufferedImage transparent = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
            drawLayers(transparent, merged.layers);
            ImageIO.write(transparent, "PNG", new File(outputDir + merged.name + "_透明.png"));

            BufferedImage masked = copyImage(maskOverlay);
            drawLayers(masked, merged.layers);
            ImageIO.write(masked, "PNG", new File(outputDir + merged.name + "_遮罩.png"));
            log.info("  合并图已保存");
        }

        log.info("全部完成！输出目录: {}", outputDir);
    }

    // ===================== 图层加载 =====================

    private List<LayerMapLayer> loadAllLayers() throws IOException {
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            throw new IOException("配置不存在: " + configPath);
        }
        Map<String, Object> root = MAPPER.readValue(configFile,
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layersRaw = (List<Map<String, Object>>) root.get("layers");
        return MAPPER.convertValue(layersRaw, new TypeReference<List<LayerMapLayer>>() {});
    }

    private static LayerGroup group(String name, List<LayerMapLayer> all, int... ids) {
        List<LayerMapLayer> matched = Arrays.stream(ids)
                .mapToObj(id -> all.stream().filter(l -> l.getId() == id).findFirst().orElse(null))
                .filter(l -> l != null)
                .collect(Collectors.toList());
        return new LayerGroup(name, matched);
    }

    // ===================== 遮罩 =====================

    private BufferedImage buildMaskOverlay() throws IOException {
        File siftFile = new File(siftPath);
        if (!siftFile.exists()) {
            throw new IOException("SIFT 地图不存在: " + siftPath);
        }
        BufferedImage sift = ImageIO.read(siftFile);
        int[] siftPixels = sift.getRGB(0, 0, MAP_SIZE, MAP_SIZE, null, 0, MAP_SIZE);

        BufferedImage result = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] outPixels = new int[MAP_SIZE * MAP_SIZE];
        for (int i = 0; i < siftPixels.length; i++) {
            int a = (siftPixels[i] >> 24) & 0xFF;
            int overlayA = a * OVERLAY_ALPHA / 255;
            if (overlayA > 1) {
                outPixels[i] = (Math.min(overlayA, 255) << 24);
            }
        }
        result.setRGB(0, 0, MAP_SIZE, MAP_SIZE, outPixels, 0, MAP_SIZE);
        return result;
    }

    // ===================== 图层绘制 =====================

    private void drawLayers(BufferedImage base, List<LayerMapLayer> layers) throws IOException {
        Graphics2D g2d = base.createGraphics();
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);

        for (LayerMapLayer layer : layers) {
            File f = new File(layermapDir + layer.getFile());
            if (!f.exists()) {
                log.warn("  图层缺失: {}", layer.getFile());
                continue;
            }
            BufferedImage layerImg = ImageIO.read(f);
            if (layerImg == null) continue;

            double centerPx = (layer.getCameraCenterX() - ORIGIN_X) / SCALE;
            double centerPy = (layer.getCameraCenterY() - ORIGIN_Y) / SCALE;
            double pixelSize = layer.getOrthoWidth() / SCALE;

            int dx = (int) Math.round(centerPx - pixelSize / 2);
            int dy = (int) Math.round(centerPy - pixelSize / 2);
            int dw = (int) Math.round(pixelSize);

            g2d.drawImage(layerImg, dx, dy, dw, dw, null);
        }
        g2d.dispose();
    }

    // ===================== 图像工具 =====================

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    // ===================== 入口 =====================

    public static void main(String[] args) throws IOException {
        String mapDir = args.length > 0 ? args[0] : "D:\\Documents\\unpack\\map";
        String outDir = args.length > 1 ? args[1] : mapDir + "\\layermap_output";
        new LayerMapBatchRenderer(mapDir, outDir).render();
    }
}
