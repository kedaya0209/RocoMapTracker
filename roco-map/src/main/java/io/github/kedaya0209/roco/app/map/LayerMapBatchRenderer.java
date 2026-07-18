package io.github.kedaya0209.roco.app.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.map.model.LayerMapLayer;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import io.github.kedaya0209.roco.app.map.util.PngImage;
import io.github.kedaya0209.roco.app.map.util.PngImageData;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 批量渲染 10 组 LayerMap：每组生成 8192×8192 透明图 + 遮罩图。
 * <pre>
 * 分组：
 *   信仰者村落一层 — id=4
 *   信仰者村落二层 — id=5
 *   拾荒者港口    — id=7,8
 *   月兔暗港      — id=12,16
 *   二叠山丘一层  — id=10
 *   下水管道口    — id=2
 *   火巨人洞窟   — id=17,18,19
 *   森巨人洞窟   — id=21,22
 *   雪巨人洞窟   — id=24
 *   光王祭坛     — id=26
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
    private static final int OVERLAY_ALPHA = 153;

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
        this.siftPath = base + "WorldMap_SIFT.png";
        this.outputDir = outputDir.endsWith("\\") || outputDir.endsWith("/") ? outputDir : outputDir + File.separator;
    }

    public void render() throws IOException {
        new File(outputDir).mkdirs();

        // 1. 加载所有图层
        List<LayerMapLayer> allLayers = loadAllLayers();

        // 2. 构建 10 个分组
        List<LayerGroup> groups = Arrays.asList(
                group("信仰者村落一层", allLayers, 4),
                group("信仰者村落二层", allLayers, 5),
                group("拾荒者港口", allLayers, 7, 8),
                group("月兔暗港", allLayers, 12, 16),
                group("二叠山丘一层", allLayers, 10),
                group("下水管道口", allLayers, 2),
                group("火巨人洞窟一层", allLayers, 17),
                group("火巨人洞窟二层", allLayers, 18),
                group("火巨人洞窟三层", allLayers, 19),
                group("森巨人洞窟一层", allLayers, 21),
                group("森巨人洞窟二层", allLayers, 22),
                group("雪巨人洞窟", allLayers, 24),
                group("光王祭坛", allLayers, 26)
        );

        // 3. 构建共享遮罩
        log.info("构建陆地半透明遮罩...");
        PngImageData maskOverlay = buildMaskOverlay();
        int[] maskPixels = maskOverlay.pixels();

        // 4. 逐组渲染
        for (LayerGroup g : groups) {
            log.info("渲染: {} ({} 个图层)", g.name, g.layers.size());

            // 透明图
            int[] transparent = new int[MAP_SIZE * MAP_SIZE];
            drawLayers(transparent, MAP_SIZE, g.layers);
            PngImage.writePng(transparent, MAP_SIZE, MAP_SIZE, new File(outputDir + g.name + "_透明.png"));
            log.info("  透明图已保存");

            // 遮罩图
            int[] masked = copyPixels(maskPixels);
            drawLayers(masked, MAP_SIZE, g.layers);
            PngImage.writePng(masked, MAP_SIZE, MAP_SIZE, new File(outputDir + g.name + "_遮罩.png"));
            log.info("  遮罩图已保存");
        }

        // 5. 额外生成合并图（同坐标区域合并），用于瓦片生成
        renderMerged("信仰者村落", allLayers, maskPixels, outputDir, 4, 5);
        renderMerged("火巨人洞窟一层", allLayers, maskPixels, outputDir, 18, 19, 17);
        renderMerged("火巨人洞窟二层", allLayers, maskPixels, outputDir, 17, 19, 18);
        renderMerged("火巨人洞窟三层", allLayers, maskPixels, outputDir, 17, 18, 19);
        renderMerged("森巨人洞窟一层", allLayers, maskPixels, outputDir,  22, 21);
        renderMerged("森巨人洞窟二层", allLayers, maskPixels, outputDir, 21, 22);

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

    private PngImageData buildMaskOverlay() throws IOException {
        File siftFile = new File(siftPath);
        if (!siftFile.exists()) {
            throw new IOException("SIFT 地图不存在: " + siftPath);
        }
        PngImageData sift = PngImage.readPng(siftFile);
        int[] siftPixels = sift.pixels();

        int[] outPixels = new int[MAP_SIZE * MAP_SIZE];
        for (int i = 0; i < siftPixels.length; i++) {
            int a = (siftPixels[i] >> 24) & 0xFF;
            int overlayA = a * OVERLAY_ALPHA / 255;
            if (overlayA > 1) {
                outPixels[i] = (Math.min(overlayA, 255) << 24);
            }
        }
        return new PngImageData(MAP_SIZE, MAP_SIZE, outPixels);
    }

    // ===================== 图层绘制 =====================

    private void drawLayers(int[] basePixels, int dstW, List<LayerMapLayer> layers) throws IOException {
        for (LayerMapLayer layer : layers) {
            File f = new File(layermapDir + layer.getFile());
            if (!f.exists()) {
                log.warn("  图层缺失: {}", layer.getFile());
                continue;
            }
            PngImageData layerImg = PngImage.readPng(f);

            double centerPx = (layer.getCameraCenterX() - ORIGIN_X) / SCALE;
            double centerPy = (layer.getCameraCenterY() - ORIGIN_Y) / SCALE;
            double pixelSize = layer.getOrthoWidth() / SCALE;

            int dx = (int) Math.round(centerPx - pixelSize / 2);
            int dy = (int) Math.round(centerPy - pixelSize / 2);
            int dw = (int) Math.round(pixelSize);

            PngImage.blitScaled(layerImg.pixels(), layerImg.w(), layerImg.h(),
                    basePixels, dstW, dstW, dx, dy, dw, dw);
        }
    }

    // ===================== 合并渲染工具 =====================

    private void renderMerged(String name, List<LayerMapLayer> allLayers,
                               int[] maskPixels, String outputDir, int... ids) throws IOException {
        LayerGroup merged = group(name, allLayers, ids);
        if (merged.layers.isEmpty()) return;
        log.info("渲染合并: {} (用于瓦片生成)", name);

        int[] transparent = new int[MAP_SIZE * MAP_SIZE];
        drawLayers(transparent, MAP_SIZE, merged.layers);
        PngImage.writePng(transparent, MAP_SIZE, MAP_SIZE, new File(outputDir + merged.name + "_透明.png"));

        int[] masked = copyPixels(maskPixels);
        drawLayers(masked, MAP_SIZE, merged.layers);
        PngImage.writePng(masked, MAP_SIZE, MAP_SIZE, new File(outputDir + merged.name + "_遮罩.png"));
        log.info("  合并图已保存");
    }

    // ===================== 图像工具 =====================

    private static int[] copyPixels(int[] src) {
        int[] copy = new int[src.length];
        System.arraycopy(src, 0, copy, 0, src.length);
        return copy;
    }

    // ===================== 入口 =====================

    public static void main(String[] args) throws IOException {
        String mapDir = args.length > 0 ? args[0] : "D:\\Documents\\unpack\\map";
        String outDir = args.length > 1 ? args[1] : mapDir + "\\layermap_output";
        new LayerMapBatchRenderer(mapDir, outDir).render();
    }
}
