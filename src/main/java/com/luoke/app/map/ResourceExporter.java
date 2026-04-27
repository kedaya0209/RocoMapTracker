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
import java.util.*;

public class ResourceExporter {

    private static final String BASE_DIR = "C:\\Users\\tangh\\Desktop\\map";
    private static final String RESOURCE_DIR = "D:\\Documents\\unpack\\Output\\Exports\\NRC";
    private static final int CANVAS_SIZE = 8192;
    private static final double HALF_SIZE = 4096.0;

    // 这里填入你需要导出的所有 NPC 名称
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
        List<String> keys = Files.readAllLines(Paths.get(BASE_DIR, "keys.txt"));
        TARGET_NPC_NAMES.addAll(keys);
        new ResourceExporter().export();
    }

    public void export() throws IOException {
        loadData();

        Map<String, ArrayNode> typeMap = new HashMap<>();
        Map<Integer, String> typeIntMap = new HashMap<>();
        itemTypeConf.values().forEach(row -> {
            String typeName = row.get("type_name").asText();
            if (!"材料".equals(typeName)) return;
            Integer typeId = row.get("type").asInt();
            typeIntMap.put(typeId, typeName);
        });

        itemConf.values().forEach(row -> {
            try {
                String name = row.get("name").asText();
                if (!TARGET_NPC_NAMES.contains(name)) return;
                int typeInt = row.get("type").asInt();
                String icon = row.get("big_icon").asText();
                icon = icon.replace("Game", "Content");
                icon = icon.substring(icon.indexOf("'") + 1, icon.lastIndexOf(".") + 1) + "png";
                String type = typeIntMap.get(typeInt);
                ArrayNode values = typeMap.getOrDefault(type, mapper.createArrayNode());
                ObjectNode node = mapper.createObjectNode();
                node.put("name", name);
                node.put("icon", icon);
                values.add(node);
                typeMap.put(type, values);
            } catch (Exception ignore) {
            }
        });
        typeMap.remove(null);

        // 使用 Map 按名称归类点位，最终生成更整齐的 JSON
        Map<String, ArrayNode> resultMap = new HashMap<>();
        TARGET_NPC_NAMES.forEach(name -> resultMap.put(name, mapper.createArrayNode()));

        npcConf.values().forEach(row -> {
            String matchedName = getMatchedName(row);
            if (matchedName != null) {
                double[] wPos = getPos(row);
                String resId = getResId(row);

                if (wPos != null && sceneCenters.containsKey(resId) && sceneSideLengths.containsKey(resId)) {
                    double[] center = sceneCenters.get(resId);
                    double side = sceneSideLengths.get(resId);

                    double pixelsPerUnit = CANVAS_SIZE / side;
                    double dx = wPos[0] - center[0];
                    double dy = wPos[1] - center[1];

                    // 严格复刻你验证准确的加法逻辑
                    int px = (int) Math.round(HALF_SIZE + (dx * pixelsPerUnit));
                    int py = (int) Math.round(HALF_SIZE + (dy * pixelsPerUnit));

                    ObjectNode node = mapper.createObjectNode();
                    node.put("res_id", resId);
                    ArrayNode wArray = mapper.createArrayNode().add(wPos[0]).add(wPos[1]);
                    ArrayNode pArray = mapper.createArrayNode().add(px).add(py);
                    node.set("world_pos", wArray);
                    node.set("pixel_pos", pArray);

                    resultMap.get(matchedName).add(node);
                }
            }
        });

        //copy资源文件
        File iconDir = new File(BASE_DIR, "icon");
        String absolutePath = iconDir.getAbsolutePath();
        if (!iconDir.exists()) {
            iconDir.mkdirs();
        }

        Map<String, String> map = new HashMap<>();
        for (ArrayNode value : typeMap.values()) {
            for (JsonNode node : value) {
                ObjectNode n = (ObjectNode) node;
                String name = n.get("name").asText();
                String iconPath = n.get("icon").asText();
                Files.copy(Paths.get(RESOURCE_DIR, iconPath), Path.of(absolutePath, name + ".png"));
                n.remove("icon");
                map.put(name, name + ".png");
            }

        }
        // 组装最终 JSON
        ArrayNode root1 = mapper.createArrayNode();
        typeMap.forEach((name, nodes) -> {
            if (!nodes.isEmpty()) {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", name);
                node.put("icon", map.get(name));
                node.put("items", nodes);
                root1.add(node);
            }
        });
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(BASE_DIR, "itemType.json"), root1);
        System.out.println("批量导出完成，保存至：itemType.json");
        ArrayNode root2 = mapper.createArrayNode();
        resultMap.forEach((name, nodes) -> {
            if (nodes.size() > 0) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", name);
                node.put("points", nodes);
                root2.add(node);
            }
        });
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(BASE_DIR, "resourcePoint.json"), root2);
        System.out.println("批量导出完成，保存至：BatchNpcPoints.json");
    }

    private String getMatchedName(JsonNode r) {
        if (!r.has("editor_name") || r.get("editor_name").size() == 0) return null;
        String editorName = r.get("editor_name").get(0).asText();
        for (String target : TARGET_NPC_NAMES) {
            if (editorName.contains(target)) return target;
        }
        return null;
    }

    // --- 以下逻辑与你提供的 ResourceExporter 完全一致，确保数据读取不走样 ---

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
                    String key = e.getKey();
                    int key1 = Integer.parseInt(key);
                    map.put(key1, e.getValue());
                } catch (Exception ignore) {
                }
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