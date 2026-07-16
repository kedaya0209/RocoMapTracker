package io.github.kedaya0209.roco.app.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 资源导出器：精准导出"材料"类资源，适配 ResourceConfig 模型
 * 坐标系：图片中心为 (0,0)
 * 功能：自动去重、自动图标复制、脏数据过滤
 */
@Slf4j
@NotThreadSafe
public class ResourceExporter {

    private static final String BASE_DIR = "D:\\Documents\\unpack\\map";
    private static final String RESOURCE_DIR = "D:\\Documents\\unpack\\Output\\Exports\\NRC";
    private static final int CANVAS_SIZE = 8192;

    private static final Set<String> TARGET_NPC_NAMES = new HashSet<>();

    private final Map<Integer, JsonNode> npcConf = new HashMap<>();
    private final Map<Integer, String> paramToMaterial = new HashMap<>(); // MEGAMAP_GATHERING: param_id → genre(材料名)
    private final Map<Integer, JsonNode> areaConf = new HashMap<>();
    private Map<Integer, String> i18nNpcNameMap; // npc_id → 本地化显示名 (来自 i18n/NPC_CONF.json)
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

        // --- 1. 建立类型白名单 (只保留"材料") ---
        Map<Integer, String> typeIntMap = new HashMap<>();
        itemTypeConf.values().forEach(row -> {
            if (row.has("type_name") && "材料".equals(row.get("type_name").asText())) {
                typeIntMap.put(row.get("type").asInt(), row.get("type_name").asText());
            }
        });

        // --- 2. 构建 keys.txt 名称 → 类型/图标 映射 ---
        Map<String, String> nameToTypeMap = new HashMap<>();
        Map<String, String> nameToIconMap = new HashMap<>();
        File iconDir = new File(BASE_DIR, "icon");
        if (!iconDir.exists()) iconDir.mkdirs();

        // 2a. 先从 BAG_ITEM_CONF 匹配（获取类型+图标路径）
        for (var row : itemConf.values()) {
            try {
                if (!row.has("name") || !row.has("type") || !row.has("big_icon")) continue;

                String name = row.get("name").asText();
                if (!TARGET_NPC_NAMES.contains(name)) continue;

                int typeInt = row.get("type").asInt();
                String typeName = typeIntMap.get(typeInt);
                if (typeName == null) continue;

                nameToTypeMap.put(name, typeName);

                // 复制图标
                String iconRaw = row.get("big_icon").asText();
                if (!iconRaw.contains("'")) continue;

                String iconPath = iconRaw.replace("Game", "Content");
                iconPath = iconPath.substring(iconPath.indexOf("'") + 1, iconPath.lastIndexOf(".") + 1) + "png";
                Path source = Paths.get(RESOURCE_DIR, iconPath);
                String targetName = name + ".png";
                if (Files.exists(source)) {
                    Files.copy(source, Path.of(iconDir.getAbsolutePath(), targetName), StandardCopyOption.REPLACE_EXISTING);
                    nameToIconMap.put(name, targetName);
                }
            } catch (IOException ignore) {
            }
        }

        // 2b. keys.txt 中 BAG_ITEM_CONF 未匹配到的，统一为"材料"类型，图标用已有文件
        for (String name : TARGET_NPC_NAMES) {
            if (!nameToTypeMap.containsKey(name)) {
                nameToTypeMap.put(name, "材料");
                Path iconFile = Path.of(iconDir.getAbsolutePath(), name + ".png");
                if (Files.exists(iconFile)) {
                    nameToIconMap.put(name, name + ".png");
                }
            }
        }

        ArrayNode root = mapper.createArrayNode();
        Set<String> deduplicationSet = new HashSet<>();

        for (var entry : npcConf.entrySet()) {
            try {
                JsonNode row = entry.getValue();
                int npcId = row.has("npc_id") ? row.get("npc_id").asInt() : -1;
                if (npcId < 0) continue;

                // 通过 MEGAMAP_GATHERING_CONF 的 param_id → genre 映射找到材料名
                String matchedName = paramToMaterial.get(npcId);
                if (matchedName == null) continue;

                if (!nameToTypeMap.containsKey(matchedName)) continue;

                double[] wPos = getPos(row);
                String resId = getResId(row);

                if (wPos == null || !sceneCenters.containsKey(resId) || !sceneSideLengths.containsKey(resId)) continue;

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
                if (deduplicationSet.contains(coordKey)) continue;
                deduplicationSet.add(coordKey);

                // 构建模型节点
                ObjectNode node = mapper.createObjectNode();
                node.put("type", nameToTypeMap.get(matchedName));
                node.put("markType", 1);
                node.put("markTypeName", matchedName);
                String iconName = nameToIconMap.get(matchedName);
                if (iconName != null) {
                    node.put("icon", iconName);
                }
                node.put("lng", px);
                node.put("lat", py);
                node.put("layer", resId);
                root.add(node);
            } catch (RuntimeException ignore) {
            }
        }
        log.info("MEGAMAP_GATHERING 映射命中: {}", root.size());

        // 4. 落地保存
        File outFile = new File(BASE_DIR, "resource_configs.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outFile, root);
        log.info("批量导出并去重完成！");
        log.info("生成总点位数: {}", root.size());
        log.info("结果文件: {}", outFile.getAbsolutePath());
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

        // 加载 NPC 本地化名称 (i18n) — 用于 MEGAMAP_GATHERING genre 不匹配时回退
        i18nNpcNameMap = loadI18nNpcNames(dataDir);

        // 加载 MEGAMAP_GATHERING_CONF: param_id → genre(材料名) 映射
        // 如果 genre 不在 keys.txt 中，尝试用 i18n 本地化名回退
        File gatheringFile = new File(dataDir, "MEGAMAP_GATHERING_CONF.json");
        if (gatheringFile.exists()) {
            mapper.readTree(gatheringFile).get("RocoDataRows").fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (!v.has("param_id")) return;
                int paramId = v.get("param_id").asInt();

                String materialName = null;
                if (v.has("genre")) {
                    String genre = v.get("genre").asText();
                    if (TARGET_NPC_NAMES.contains(genre)) {
                        materialName = genre;
                    }
                }
                // 回退：尝试 i18n 本地化显示名
                if (materialName == null && i18nNpcNameMap != null) {
                    materialName = i18nNpcNameMap.get(paramId);
                }
                if (materialName != null) {
                    paramToMaterial.put(paramId, materialName);
                }
            });
        }
        log.info("MEGAMAP_GATHERING + i18n 映射加载: {} 种材料, {} 个 param_id",
                paramToMaterial.values().stream().distinct().count(), paramToMaterial.size());
    }

    /**
     * 从 i18n/NPC_CONF.json 加载 NPC 本地化显示名。
     * 读取 NPC_CONF.model_conf → LocalizationStrings → keys.txt 匹配的显示名。
     */
    private Map<Integer, String> loadI18nNpcNames(File dataDir) throws IOException {
        File npcConfFile = new File(dataDir, "NPC_CONF.json");
        File i18nFile = new File(dataDir, "i18n/NPC_CONF.json");
        if (!npcConfFile.exists() || !i18nFile.exists()) return null;

        JsonNode i18nRoot = mapper.readTree(i18nFile);
        JsonNode locStrings = i18nRoot.get("LocalizationStrings");
        if (locStrings == null || !locStrings.isObject()) return null;

        // 加载本地化字符串: model_conf_key → 显示名
        Map<Integer, String> locMap = new HashMap<>();
        locStrings.fields().forEachRemaining(e -> {
            try { locMap.put(Integer.parseInt(e.getKey()), e.getValue().asText()); }
            catch (NumberFormatException ignore) {}
        });

        // 遍历 NPC_CONF: npc_id 的 model_conf 指向本地化名称
        Map<Integer, String> result = new HashMap<>();
        JsonNode npcConfRoot = mapper.readTree(npcConfFile).get("RocoDataRows");
        if (npcConfRoot == null) return null;

        npcConfRoot.fields().forEachRemaining(e -> {
            JsonNode row = e.getValue();
            if (!row.has("model_conf")) return;
            int mc = row.get("model_conf").asInt();
            String localizedName = locMap.get(mc);
            if (localizedName != null && TARGET_NPC_NAMES.contains(localizedName)) {
                try { result.put(Integer.parseInt(e.getKey()), localizedName); }
                catch (NumberFormatException ignore) {}
            }
        });
        log.info("i18n NPC 本地化名称加载: {} 个 NPC 匹配 keys.txt", result.size());
        return result;
    }

    private void fill(Map<Integer, JsonNode> map, File f) throws IOException {
        if (f.exists()) {
            mapper.readTree(f).get("RocoDataRows").fields().forEachRemaining(e -> {
                try {
                    map.put(Integer.parseInt(e.getKey()), e.getValue());
                } catch (NumberFormatException ignore) {
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
