package io.github.kedaya0209.roco.app.map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.map.model.LayerMapLayer;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import io.github.kedaya0209.roco.app.map.util.PngImage;
import io.github.kedaya0209.roco.app.map.util.PngImageData;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * LayerMap 叠加合成器 — 直接基于 mask 生成黑色遮罩层，再叠加 LayerMap。
 * <p>
 * 管线：加载 16 张 mask → 陆地填半透明黑色 → 叠加洞穴 LayerMap。
 * 不需要原始地图瓦片或 SIFT 底图。
 * <p>
 * 使用示例：
 * <pre>{@code
 *   LayerMapCompositor compositor = new LayerMapCompositor("D:\\Documents\\unpack\\map");
 *   compositor.composite("output.png");
 * }</pre>
 */
@Slf4j
@NotThreadSafe
public class LayerMapCompositor {

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    private static final int MAP_SIZE = 8192;
    private static final int TILE_SIZE = 2048;
    private static final int GRID_COUNT = 4;

    /** 坐标换算 */
    private static final double MAP_CENTER_X = 510000;
    private static final double MAP_CENTER_Y = 612000;
    private static final double SIDE_LENGTH = 408000;
    private static final double ORIGIN_X = MAP_CENTER_X - SIDE_LENGTH / 2;
    private static final double ORIGIN_Y = MAP_CENTER_Y - SIDE_LENGTH / 2;
    private static final double SCALE = SIDE_LENGTH / MAP_SIZE;

    /** 遮罩透明度 (0~255，值越大越黑) */
    /** 遮罩透明度 (0~255，越大越黑) */
    private static final int OVERLAY_ALPHA = 153;

    private final String mapDir;
    private final String configPath;
    private final String layermapDir;
    private final String siftPath;

    public LayerMapCompositor(String mapDir) {
        this.mapDir = mapDir.endsWith("\\") || mapDir.endsWith("/") ? mapDir : mapDir + File.separator;
        this.configPath = this.mapDir + "layermap_config.json";
        this.layermapDir = this.mapDir + "layermap" + File.separator;
        this.siftPath = this.mapDir + "WorldMap_SIFT.png";
    }

    /**
     * 执行合成：加载 mask → 陆地半透明黑色遮罩 → 叠加 LayerMap → 保存
     *
     * @param outputPath 输出 PNG 路径
     * @return 合成后的 PngImageData
     */
    public PngImageData composite(String outputPath) throws IOException {
        long start = System.currentTimeMillis();

        log.info("1/3 从 mask 生成黑色遮罩...");
        PngImageData result = buildMaskOverlay();

        log.info("2/3 叠加 LayerMap 洞穴图层...");
        compositeLayerMaps(result);

        if (outputPath != null) {
            PngImage.writePng(result.pixels(), result.w(), result.h(), new File(outputPath));
            log.info("3/3 完成！耗时 {}s 输出: {}",
                    (System.currentTimeMillis() - start) / 1000.0, outputPath);
        }
        return result;
    }

    // ===================== Mask 处理 =====================

    /**
     * 利用 SIFT 的 alpha 通道识别陆地，线性映射到遮罩透明度。
     * SIFT 本身已由 MapAssembler 做海岸平滑，此处不做二次模糊。
     */
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
            // SIFT alpha 255 → OVERLAY_ALPHA, alpha 0 → 0，线性过渡
            int overlayA = a * OVERLAY_ALPHA / 255;
            if (overlayA > 1) {
                outPixels[i] = (Math.min(overlayA, 255) << 24);
            }
        }
        return new PngImageData(MAP_SIZE, MAP_SIZE, outPixels);
    }

    // ===================== LayerMap 叠加 =====================

    private void compositeLayerMaps(PngImageData base) throws IOException {
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            log.warn("LayerMap 配置不存在，跳过: {}", configPath);
            return;
        }

        Map<String, Object> root = MAPPER.readValue(configFile,
                new TypeReference<Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> layersRaw = (List<Map<String, Object>>) root.get("layers");
        List<LayerMapLayer> layers = MAPPER.convertValue(layersRaw,
                new TypeReference<List<LayerMapLayer>>() {});

        int[] basePixels = base.pixels();
        int baseW = base.w();

        int count = 0;
        for (LayerMapLayer layer : layers) {
            File f = new File(layermapDir + layer.getFile());
            if (!f.exists()) {
                log.warn("  LayerMap 缺失: {}", layer.getFile());
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
                    basePixels, baseW, baseW, dx, dy, dw, dw);
            log.info("  叠加 [{}] ({},{}) {}x{}", layer.getDisplayName(), dx, dy, dw, dw);
            count++;
        }
        log.info("  共叠加 {} 个 LayerMap 图层", count);
    }

    // ===================== 入口 =====================

    public static void main(String[] args) throws IOException {
        String baseDir = args.length > 0 ? args[0] : "D:\\Documents\\unpack\\map";
        new LayerMapCompositor(baseDir).composite(baseDir + "\\LayerMap_Overlay.png");
    }
}
