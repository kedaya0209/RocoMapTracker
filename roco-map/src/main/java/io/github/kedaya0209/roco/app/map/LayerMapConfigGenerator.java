package io.github.kedaya0209.roco.app.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 从原始游戏 metadata 重新生成 layermap_config.json。
 * <p>
 * 读取 conf/LAYERED_WORLD_MAP_CONF.json（坐标/尺寸）和
 * in18/LAYERED_WORLD_MAP_CONF.json（本地化名称），
 * 筛选可放置在地图 8192x8192 范围内的图层输出。
 * <p>
 * 使用示例：
 * <pre>{@code
 *   LayerMapConfigGenerator.main(new String[]{"D:\\Documents\\unpack\\map"});
 * }</pre>
 */
@Slf4j
public class LayerMapConfigGenerator {

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    private static final double MAP_CENTER_X = 510000;
    private static final double MAP_CENTER_Y = 612000;
    private static final double SIDE_LENGTH = 408000;
    private static final int MAP_SIZE = 8192;
    private static final double ORIGIN_X = MAP_CENTER_X - SIDE_LENGTH / 2;
    private static final double ORIGIN_Y = MAP_CENTER_Y - SIDE_LENGTH / 2;
    private static final double SCALE = SIDE_LENGTH / MAP_SIZE;

    private final String mapDir;

    public LayerMapConfigGenerator(String mapDir) {
        this.mapDir = mapDir.endsWith("\\") || mapDir.endsWith("/") ? mapDir : mapDir + File.separator;
    }

    public void generate() throws IOException {
        File confFile = new File(mapDir + "layermap" + File.separator + "metadata" + File.separator
                + "conf" + File.separator + "LAYERED_WORLD_MAP_CONF.json");
        File in18File = new File(mapDir + "layermap" + File.separator + "metadata" + File.separator
                + "in18" + File.separator + "LAYERED_WORLD_MAP_CONF.json");

        if (!confFile.exists()) {
            throw new IOException("conf 不存在: " + confFile);
        }

        JsonNode confRoot = MAPPER.readTree(confFile);
        JsonNode rows = confRoot.get("RocoDataRows");

        // 读取 in18 本地化名称
        Map<String, String> localizedNames = readLocalizedNames(in18File);

        // 读取已存在的 PNG 文件列表
        File layermapDir = new File(mapDir + "layermap");
        String[] pngFiles = layermapDir.list((_, name) -> name.endsWith(".png"));
        if (pngFiles == null) pngFiles = new String[0];

        // 构建输出 layers
        ArrayNode layers = MAPPER.createArrayNode();
        int sequence = 0;

        for (Iterator<String> it = rows.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            JsonNode row = rows.get(key);

            if (!row.has("map_resource") || !row.has("camera_center") || !row.has("Ortho_width")) {
                continue; // 分组头（无具体图层）
            }

            String mapResource = row.get("map_resource").asText();
            String pngFile = mapResource + ".png";

            // 检查 PNG 文件是否存在
            boolean fileExists = false;
            for (String f : pngFiles) {
                if (f.equals(pngFile) || f.equalsIgnoreCase(pngFile)) {
                    fileExists = true;
                    break;
                }
            }
            if (!fileExists) {
                log.warn("跳过 [{}] PNG 文件不存在: {}", key, pngFile);
                continue;
            }

            JsonNode center = row.get("camera_center");
            double cx = center.get(0).asDouble();
            double cy = center.get(1).asDouble();
            double orthoWidth = row.get("Ortho_width").asDouble();

            // 检查是否在地图范围内
            double pixelCx = (cx - ORIGIN_X) / SCALE;
            double pixelCy = (cy - ORIGIN_Y) / SCALE;
            double pixelSize = orthoWidth / SCALE;
            double dx = pixelCx - pixelSize / 2;
            double dy = pixelCy - pixelSize / 2;

            boolean onMap = dx >= -1 && dy >= -1 && dx + pixelSize <= MAP_SIZE + 1
                    && dy + pixelSize <= MAP_SIZE + 1;
            if (!onMap) {
                log.info("跳过 [{}] {} 坐标 ({},{}) 在地图外", key, mapResource, cx, cy);
                continue;
            }

            // 名称：优先使用 in18 本地化名称，回退到 conf display_name
            String displayName = localizedNames.get(key);
            if (displayName == null || displayName.isEmpty()) {
                displayName = row.has("display_name") ? row.get("display_name").asText() : "";
            }
            if (displayName.isEmpty()) {
                displayName = mapResource;
            }

            ObjectNode layer = MAPPER.createObjectNode();
            layer.put("id", Integer.parseInt(key));
            layer.put("file", pngFile);
            layer.put("display_name", displayName);
            layer.put("camera_center_x", (int) cx);
            layer.put("camera_center_y", (int) cy);
            layer.put("ortho_width", (int) orthoWidth);
            layers.add(layer);
            sequence++;
        }

        // 额外处理：Cave_A2_07_01_CaveTunnel_Cave_A2_07_01_02 — 月兔暗港二层
        // 与 id=12 同坐标，conf 中无此条目，但 PNG 存在
        addExtraLayer(layers, 16, "Cave_A2_07_01_CaveTunnel_Cave_A2_07_01_02.png",
                "月兔暗港二层", 595000, 652054, 40000, pngFiles);

        // 组装输出
        ObjectNode root = MAPPER.createObjectNode();
        root.put("map_center_x", (int) MAP_CENTER_X);
        root.put("map_center_y", (int) MAP_CENTER_Y);
        root.put("side_length", (int) SIDE_LENGTH);
        root.put("map_size", MAP_SIZE);
        root.set("layers", layers);

        MAPPER.writerWithDefaultPrettyPrinter().writeValue(
                new File(mapDir + "layermap_config.json"), root);
        log.info("layermap_config.json 已生成，共 {} 个图层", layers.size());
    }

    private void addExtraLayer(ArrayNode layers, int id, String fileName, String displayName,
                                double cx, double cy, double orthoWidth, String[] pngFiles) {
        // 检查是否已存在
        for (JsonNode l : layers) {
            if (l.get("id").asInt() == id) return;
        }
        // 检查 PNG 是否存在
        for (String f : pngFiles) {
            if (f.equals(fileName) || f.equalsIgnoreCase(fileName)) {
                ObjectNode layer = MAPPER.createObjectNode();
                layer.put("id", id);
                layer.put("file", fileName);
                layer.put("display_name", displayName);
                layer.put("camera_center_x", (int) cx);
                layer.put("camera_center_y", (int) cy);
                layer.put("ortho_width", (int) orthoWidth);
                layers.add(layer);
                return;
            }
        }
        log.warn("额外图层 [{}] PNG 不存在: {}", id, fileName);
    }

    private static Map<String, String> readLocalizedNames(File in18File) throws IOException {
        Map<String, String> names = new LinkedHashMap<>();
        if (!in18File.exists()) {
            log.warn("in18 文件不存在: {}", in18File);
            return names;
        }
        JsonNode in18Root = MAPPER.readTree(in18File);
        JsonNode strings = in18Root.get("LocalizationStrings");
        if (strings != null) {
            for (Iterator<String> it = strings.fieldNames(); it.hasNext(); ) {
                String key = it.next();
                String value = strings.get(key).asText();
                if (value != null && !value.isEmpty() && !"卡洛西亚大陆".equals(value)) {
                    names.put(key, value);
                }
            }
        }
        return names;
    }

    public static void main(String[] args) throws IOException {
        String baseDir = args.length > 0 ? args[0] : "D:\\Documents\\unpack\\map";
        new LayerMapConfigGenerator(baseDir).generate();
    }
}
