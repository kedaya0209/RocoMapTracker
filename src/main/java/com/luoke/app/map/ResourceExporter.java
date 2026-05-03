package com.luoke.app.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 资源导出器：精准导出“材料”类资源，适配 ResourceConfig 模型
 * 坐标系：图片中心为 (0,0)
 * 功能：自动去重、自动图标复制、脏数据过滤
 */
public class ResourceExporter {

    private static final String BASE_DIR = "C:\\Users\\tangh\\Desktop\\map";
    private static final String RESOURCE_DIR = "D:\\Documents\\unpack\\Output\\Exports\\NRC";
    private static final int CANVAS_SIZE = 8192;

    private static final Set<String> TARGET_NPC_NAMES = new HashSet<>();

    private final Map<Integer, JsonNode> npcConf = new HashMap<>();
    private final Map<Integer, JsonNode> areaConf = new HashMap<>();
    private final Map<Integer, JsonNode> objConf = new HashMap<>();
    private final Map<Integer, JsonNode> itemConf = new HashMap<>();
    private final Map<Integer, JsonNode> itemTypeConf = new HashMap<>();
    private final Map<String, double[]> sceneCenters = new HashMap<>();
    private final Map<String, Double> sceneSideLengths = new HashMap<>();

    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        Path keysPath = Paths.get(BASE_DIR, "keys.txt");
        if (Files.exists(keysPath)) {
            TARGET_NPC_NAMES.addAll(Files.readAllLines(keysPath));
        }
        new ResourceExporter().export();
    }

    public void export() throws IOException {
        loadData();

        // --- 1. 建立类型白名单 (只保留“材料”) ---
        Map<Integer, String> typeIntMap = new HashMap<>();
        itemTypeConf.values().forEach(row -> {
            if (row.has("type_name") && "材料".equals(row.get("type_name").asText())) {
                typeIntMap.put(row.get("type").asInt(), row.get("type_name").asText());
            }
        });

        // --- 2. 映射 NPC 名称到 类型和图标，并复制文件 ---
        Map<String, String> nameToTypeMap = new HashMap<>();
        Map<String, String> nameToIconMap = new HashMap<>();
        File iconDir = new File(BASE_DIR, "icon");
        if (!iconDir.exists()) iconDir.mkdirs();

        itemConf.values().forEach(row -> {
            try {
                // 忽略字段缺失的脏数据
                if (!row.has("name") || !row.has("type") || !row.has("big_icon")) return;

                String name = row.get("name").asText();
                if (!TARGET_NPC_NAMES.contains(name)) return;

                int typeInt = row.get("type").asInt();
                String typeName = typeIntMap.get(typeInt);
                if (typeName == null) return; // 只处理“材料”

                nameToTypeMap.put(name, typeName);

                String iconRaw = row.get("big_icon").asText();
                if (!iconRaw.contains("'")) return;

                String iconPath = iconRaw.replace("Game", "Content");
                iconPath = iconPath.substring(iconPath.indexOf("'") + 1, iconPath.lastIndexOf(".") + 1) + "png";

                Path source = Paths.get(RESOURCE_DIR, iconPath);
                String targetName = name + ".png";
                if (Files.exists(source)) {
                    Files.copy(source, Path.of(iconDir.getAbsolutePath(), targetName), StandardCopyOption.REPLACE_EXISTING);
                    nameToIconMap.put(name, targetName);
                }
            } catch (Exception ignore) {}
        });

        // --- 3. 生成 JSON 并执行坐标去重 ---
        ArrayNode root = mapper.createArrayNode();
        Set<String> deduplicationSet = new HashSet<>();

        npcConf.values().forEach(row -> {
            try {
                String matchedName = getMatchedName(row);
                // 过滤：必须是 keys.txt 里的 NPC，且在 itemConf 中被识别为“材料”
                if (matchedName != null && nameToTypeMap.containsKey(matchedName)) {
                    double[] wPos = getPos(row);
                    String resId = getResId(row);

                    if (wPos != null && sceneCenters.containsKey(resId) && sceneSideLengths.containsKey(resId)) {
                        double[] center = sceneCenters.get(resId);
                        double side = sceneSideLengths.get(resId);

                        double pixelsPerUnit = CANVAS_SIZE / side;
                        double dx = wPos[0] - center[0];
                        double dy = wPos[1] - center[1];

                        // 转换坐标：中心原点 (0,0)
                        double px = dx * pixelsPerUnit;
                        double py = dy * pixelsPerUnit;

                        // --- 组合键去重：层级 + 坐标 (保留2位小数) ---
                        String coordKey = String.format("%s_%.2f_%.2f", resId, px, py);
                        if (deduplicationSet.contains(coordKey)) {
                            return;
                        }
                        deduplicationSet.add(coordKey);

                        // 构建模型节点
                        ObjectNode node = mapper.createObjectNode();
                        node.put("type", nameToTypeMap.get(matchedName));
                        node.put("markType", 1);
                        node.put("markTypeName", matchedName);
                        node.put("icon", nameToIconMap.get(matchedName));
                        node.put("lng", px);
                        node.put("lat", py);
                        node.put("layer", resId);
                        root.add(node);
                    }
                }
            } catch (Exception ignore) {}
        });

        // 4. 落地保存
        File outFile = new File(BASE_DIR, "resource_configs.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outFile, root);
        System.out.println("批量导出并去重完成！");
        System.out.println("生成总点位数: " + root.size());
        System.out.println("结果文件: " + outFile.getAbsolutePath());
    }

    private String getMatchedName(JsonNode r) {
        if (!r.has("editor_name") || r.get("editor_name").isEmpty()) return null;
        String editorName = r.get("editor_name").get(0).asText();
        for (String target : TARGET_NPC_NAMES) {
            if (editorName.contains(target)) return target;
        }
        return null;
    }

    private void loadData() throws IOException {
        File dataDir = new File(BASE_DIR, "pointdata");
        JsonNode sceneConf = mapper.readTree(new File(dataDir, "WORLD_MAP_BLOCK_CONF.json")).get("RocoDataRows");
        sceneConf.fields().forEachRemaining(e -> {
            JsonNode r = e.getValue();
            if (r.has("map_center_position_xyz") && r.has("side_length")) {
                String[] c = r.get("map_center_position_xyz").asText().split(";");
                String id = r.get("scene_res_id").asText();
                sceneCenters.put(id, new double[]{Double.parseDouble(c[0]), Double.parseDouble(c[1])});
                sceneSideLengths.put(id, r.get("side_length").asDouble());
            }
        });
        fill(npcConf, new File(dataDir, "NPC_REFRESH_CONTENT_CONF.json"));
        fill(areaConf, new File(dataDir, "AREA_CONF.json"));
        fill(objConf, new File(dataDir, "SCENE_OBJECT_CONF.json"));
        fill(itemConf, new File(dataDir, "BAG_ITEM_CONF.json"));
        fill(itemTypeConf, new File(dataDir, "BAG_ITEM_TYPE_CONF.json"));
    }

    private void fill(Map<Integer, JsonNode> map, File f) throws IOException {
        if (f.exists()) {
            mapper.readTree(f).get("RocoDataRows").fields().forEachRemaining(e -> {
                try {
                    map.put(Integer.parseInt(e.getKey()), e.getValue());
                } catch (Exception ignore) {}
            });
        }
    }

    private double[] getPos(JsonNode r) {
        int t = r.get("refresh_type").asInt();
        int p = r.get("refresh_param").asInt();
        JsonNode n = (t != 4) ? areaConf.get(p) : objConf.get(p);
        if (n != null) {
            JsonNode pos = n.get(t != 4 ? "center_xyz" : "position_xyz");
            if (pos != null && pos.size() >= 2) return new double[]{pos.get(0).asDouble(), pos.get(1).asDouble()};
        }
        return null;
    }

    private String getResId(JsonNode r) {
        int t = r.get("refresh_type").asInt();
        int p = r.get("refresh_param").asInt();
        JsonNode n = (t != 4) ? areaConf.get(p) : objConf.get(p);
        return n != null ? n.get(t != 4 ? "scene_res_id" : "scene_res_conf_id").asText() : "";
    }
}