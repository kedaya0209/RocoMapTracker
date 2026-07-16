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
 * 资源导出器：从游戏解包数据导出可采集的材料资源点，生成 resource_configs.json。
 *
 * <h3>数据链路</h3>
 * <pre>
 * keys.txt（39 种目标材料名）
 *   │
 *   ├── ① MEGAMAP_GATHERING_CONF          genre(材料名) → param_id
 *   │     遍历 RocoDataRows，genre 在 keys.txt 中的条目 → paramToMaterial[param_id] = genre
 *   │     命中 ~46 个 param_id
 *   │
 *   ├── ② NPC_CONF genre=27 补充          traverse → BAG_ITEM_CONF.name
 *   │     遍历 NPC_CONF，跳过已在①中的 NPCID
 *   │     条件：genre=27 ∧ traverse_data_type=2
 *   │     通过 traverse_data_param[0] 查 BAG_ITEM_CONF.name，匹配 keys.txt 则加入
 *   │     补充 NPC 65599 → 结晶花（BAG_ITEM_CONF 优先使用 _2_P 补丁数据）
 *   │
 *   └── paramToMaterial：47 个 param_id → 39 种材料
 *             │
 *             └── ③ NPC_REFRESH_CONTENT_CONF  刷新点 → 坐标
 *                   遍历 RocoDataRows：
 *                     ① disable:true → 跳过
 *                     ② npc_id 查 paramToMaterial → 无匹配跳过
 *                     ③ refresh_param → AREA_CONF / SCENE_OBJECT_CONF 取 center_xyz / position_xyz
 *                     ④ WORLD_MAP_BLOCK_CONF → canvas 像素坐标 (8192×8192, 中心原点)
 *                     ⑤ 组合键(层级+材料名+坐标)去重
 *                     ⑥ 写入 resource_configs.json
 * </pre>
 *
 * <h3>图标导出</h3>
 * <pre>
 * BAG_ITEM_CONF 按 name 匹配 keys.txt，复制 icon/big_icon 到 icon/{name}.png。
 * 未匹配到的再通过 paramToMaterial → NPC_CONF.traverse_data_param → BAG_ITEM_CONF 回溯，
 * 仍无图标则查 NPC_OPTION_CONF button_icon 含 BagItem/ 的路径。
 * </pre>
 *
 * <h3>数据源</h3>
 * <ul>
 *   <li>BIN_COMPRESSED — 用户自己解包的数据（旧版）</li>
 *   <li>BIN_COMPRESSED_2P — GitHub Roco-Kingdom-World-Data 的 pakchunk4-WindowsNoEditor_2_P 补丁数据，
 *       用于 BAG_ITEM_CONF（有更正后的物品名称，如 item 100851 在旧版为占位符，_2_P 为"结晶花"）</li>
 * </ul>
 *
 * <h3>坐标系</h3>
 * canvas 像素坐标，中心原点 (0,0)，8192×8192。
 * 转换：px = (gameX - centerX) * 8192 / sideLength
 *
 * <h3>脏数据过滤</h3>
 * <ol>
 *   <li>disable:true 的刷新点</li>
 *   <li>AREA_CONF / SCENE_OBJECT_CONF 缺失的条目</li>
 *   <li>WORLD_MAP_BLOCK_CONF 未覆盖的场景</li>
 *   <li>坐标完全重复的组合键</li>
 * </ol>
 */
@Slf4j
@NotThreadSafe
public class ResourceExporter {

    private static final String BASE_DIR = "D:\\Documents\\unpack\\map";
    private static final String RESOURCE_DIR = "D:\\Documents\\unpack\\Output\\Exports\\NRC";
    private static final String BIN_COMPRESSED = "D:\\Documents\\unpack\\Output\\Exports\\NRC\\Content\\ScriptC\\Data\\Bin\\BinDataCompressed";
    private static final String BIN_COMPRESSED_2P = "D:\\Documents\\code\\Roco-tools\\Roco-Kingdom-World-Data\\pakchunk4-WindowsNoEditor_2_P\\Bin\\BinDataCompressed";
    private static final String BIN_LOCALIZE = "D:\\Documents\\unpack\\Output\\Exports\\NRC\\Content\\ScriptC\\Data\\Bin\\BinLocalize\\dev_CN";
    private static final int CANVAS_SIZE = 8192;

    private static final Set<String> TARGET_NPC_NAMES = new HashSet<>();

    private final Map<Integer, JsonNode> npcConf = new HashMap<>();
    private final Map<Integer, String> paramToMaterial = new HashMap<>(); // MEGAMAP_GATHERING: param_id → genre(材料名)
    private final Map<Integer, JsonNode> areaConf = new HashMap<>();
    private final Map<Integer, JsonNode> objConf = new HashMap<>();
    private final Map<Integer, JsonNode> itemConf = new HashMap<>();
    private final Map<Integer, JsonNode> itemTypeConf = new HashMap<>();
    private final Map<String, double[]> sceneCenters = new HashMap<>();
    private final Map<String, Double> sceneSideLengths = new HashMap<>();
    // NPC_CONF 定义 — 用于 traverse_data_param 回溯到 BAG_ITEM_CONF
    private final Map<Integer, JsonNode> npcDef = new HashMap<>();
    private final Map<Integer, JsonNode> npcOptionConf = new HashMap<>(); // NPC_OPTION_CONF button_icon 回溯

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
                if (!row.has("name")) continue;

                String name = row.get("name").asText();
                if (!TARGET_NPC_NAMES.contains(name)) continue;

                // 匹配类型：优先使用 BAG_ITEM_TYPE_CONF 中的"材料"类型，
                // 若无 type 字段或非材料类型则默认"材料"
                String typeName = "材料";
                if (row.has("type")) {
                    String mapped = typeIntMap.get(row.get("type").asInt());
                    if (mapped != null) typeName = mapped;
                }
                nameToTypeMap.put(name, typeName);

                // 复制图标：优先 icon(BagItem/)，回退 big_icon(Item190/)
                String targetName = name + ".png";
                Path targetFile = Path.of(iconDir.getAbsolutePath(), targetName);
                for (String field : new String[]{"icon", "big_icon"}) {
                    if (!row.has(field)) continue;
                    String iconRaw = row.get(field).asText();
                    if (!iconRaw.contains("'")) continue;
                    String iconPath = iconRaw.replace("Game", "Content");
                    iconPath = iconPath.substring(iconPath.indexOf("'") + 1, iconPath.lastIndexOf(".") + 1) + "png";
                    Path source = Paths.get(RESOURCE_DIR, iconPath);
                    if (Files.exists(source)) {
                        Files.copy(source, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        nameToIconMap.put(name, targetName);
                        break;
                    }
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

        // 2c. 对 i18n 匹配但无图标的材料，通过 NPC_CONF traverse_data_param 回溯 BAG_ITEM_CONF
        for (String name : TARGET_NPC_NAMES) {
            if (nameToIconMap.containsKey(name)) continue;
            // 找出映射到此材料名的 npc_id
            Integer npcId = null;
            for (var e : paramToMaterial.entrySet()) {
                if (name.equals(e.getValue())) { npcId = e.getKey(); break; }
            }
            if (npcId == null) continue;
            JsonNode npcRow = npcDef.get(npcId);
            if (npcRow == null) continue;
            if (npcRow.has("traverse_data_type") && npcRow.get("traverse_data_type").asInt() == 2
                    && npcRow.has("traverse_data_param") && npcRow.get("traverse_data_param").size() > 0) {
                // traverse_data_type=2: param 是 BAG_ITEM_CONF id
                int itemId = npcRow.get("traverse_data_param").get(0).asInt();
                JsonNode itemRow = itemConf.get(itemId);
                if (itemRow != null) {
                    if (tryCopyIcon(itemRow, name, iconDir)) continue;
                }
            }
            // 回退：排查 NPC_OPTION_CONF 中 button_icon 含 BagItem/ 路径的图标
            if (npcRow.has("option_id")) {
                for (JsonNode optIdNode : npcRow.get("option_id")) {
                    JsonNode optRow = npcOptionConf.get(optIdNode.asInt());
                    if (optRow == null || !optRow.has("button_icon")) continue;
                    String btnIcon = optRow.get("button_icon").asText();
                    if (!btnIcon.contains("BagItem/") || !btnIcon.contains("'")) continue;
                    String iconPath = btnIcon.replace("Game", "Content");
                    iconPath = iconPath.substring(iconPath.indexOf("'") + 1, iconPath.lastIndexOf(".") + 1) + "png";
                    Path source = Paths.get(RESOURCE_DIR, iconPath);
                    if (Files.exists(source)) {
                        String targetName = name + ".png";
                        Files.copy(source, Path.of(iconDir.getAbsolutePath(), targetName), StandardCopyOption.REPLACE_EXISTING);
                        nameToIconMap.put(name, targetName);
                        break;
                    }
                }
            }
        }

        ArrayNode root = mapper.createArrayNode();
        Set<String> deduplicationSet = new HashSet<>();

        for (var entry : npcConf.entrySet()) {
            try {
                JsonNode row = entry.getValue();
                // 跳过已禁用的刷新点
                if (row.has("disable") && row.get("disable").asBoolean()) continue;
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

                // 组合键去重：层级 + 材料名 + 坐标 (保留2位小数)
                // 不同材料可能在同一 AREA 刷新，必须包含名称避免误过滤
                String coordKey = String.format("%s_%s_%.2f_%.2f", resId, matchedName, px, py);
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
        File dataDir = new File(BIN_COMPRESSED);
        File locDir = new File(BIN_LOCALIZE);

        // 场景配置（地图中心、边长）
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

        // 从最新解包数据加载
        fill(npcConf, new File(dataDir, "NPC_REFRESH_CONTENT_CONF.json"));
        fill(areaConf, new File(dataDir, "AREA_CONF.json"));
        fill(objConf, new File(dataDir, "SCENE_OBJECT_CONF.json"));
        // BAG_ITEM_CONF 优先使用 _2_P 数据（包 Split 补丁，有更正后的物品名称）
        // 回退到用户自己的解包数据
        File bagItemFile = new File(BIN_COMPRESSED_2P, "BAG_ITEM_CONF.json");
        if (!bagItemFile.exists()) bagItemFile = new File(dataDir, "BAG_ITEM_CONF.json");
        fill(itemConf, bagItemFile);
        fill(itemTypeConf, new File(dataDir, "BAG_ITEM_TYPE_CONF.json"));
        fill(npcDef, new File(dataDir, "NPC_CONF.json"));
        fill(npcOptionConf, new File(dataDir, "NPC_OPTION_CONF.json"));

        // 构建 param_id → 材料名 映射
        // 来源1：MEGAMAP_GATHERING_CONF — 采集地图上的采集物，genre 字段即材料名
        File gatheringFile = new File(dataDir, "MEGAMAP_GATHERING_CONF.json");
        if (gatheringFile.exists()) {
            mapper.readTree(gatheringFile).get("RocoDataRows").fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                if (!v.has("genre") || !v.has("param_id")) return;
                int paramId = v.get("param_id").asInt();
                String genre = v.get("genre").asText();
                if (TARGET_NPC_NAMES.contains(genre)) {
                    paramToMaterial.put(paramId, genre);
                }
            });
        }
        log.info("MEGAMAP_GATHERING genre 映射: {} 种材料, {} 个 param_id",
                paramToMaterial.values().stream().distinct().count(), paramToMaterial.size());

        // 补充1: NPC_CONF genre=27 + traverse_data_type=2 -> BAG_ITEM_CONF.name
        // 覆盖不在 MEGAMAP_GATHERING 中的材料 NPC（如下层家园种植材料）
        for (var e : npcDef.entrySet()) {
            int npcId = e.getKey();
            if (paramToMaterial.containsKey(npcId)) continue;
            JsonNode row = e.getValue();
            if (!row.has("genre") || row.get("genre").asInt() != 27) continue;
            if (row.has("traverse_data_type") && row.get("traverse_data_type").asInt() == 2
                    && row.has("traverse_data_param") && row.get("traverse_data_param").size() > 0) {
                int itemId = row.get("traverse_data_param").get(0).asInt();
                JsonNode itemRow = itemConf.get(itemId);
                if (itemRow != null && itemRow.has("name")
                        && TARGET_NPC_NAMES.contains(itemRow.get("name").asText())) {
                    paramToMaterial.put(npcId, itemRow.get("name").asText());
                }
            }
        }

        log.info("paramToMaterial 最终: {} 种材料, {} 个 param_id",
                paramToMaterial.values().stream().distinct().count(), paramToMaterial.size());

    }
    /** 尝试从 BAG_ITEM_CONF row 复制图标，成功返回 true */
    private boolean tryCopyIcon(JsonNode itemRow, String materialName, File iconDir) throws IOException {
        for (String field : new String[]{"icon", "big_icon"}) {
            if (!itemRow.has(field)) continue;
            String iconRaw = itemRow.get(field).asText();
            if (!iconRaw.contains("'")) continue;
            String iconPath = iconRaw.replace("Game", "Content");
            iconPath = iconPath.substring(iconPath.indexOf("'") + 1, iconPath.lastIndexOf(".") + 1) + "png";
            Path source = Paths.get(RESOURCE_DIR, iconPath);
            if (Files.exists(source)) {
                String targetName = materialName + ".png";
                Files.copy(source, Path.of(iconDir.getAbsolutePath(), targetName), StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
        }
        return false;
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
