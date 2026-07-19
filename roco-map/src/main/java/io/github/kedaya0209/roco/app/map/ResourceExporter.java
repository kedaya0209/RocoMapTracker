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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 资源导出器：从游戏解包数据导出可采集的材料资源点，生成 internal_resource_point.json。
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
 *                     ⑥ 写入 internal_resource_point.json
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
    private static final String PATCH_DATA_DIR = "D:\\Documents\\code\\Roco-tools\\Roco-Kingdom-World-Data";
    private static final String[] PATCH_DIRS = {
            "pakchunk4-WindowsNoEditor",
            "pakchunk4-WindowsNoEditor_0_P",
            "pakchunk4-WindowsNoEditor_1_P",
            "pakchunk4-WindowsNoEditor_2_P",
            "pakchunk4-WindowsNoEditor_3_P"
    };
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
    private final Map<Integer, JsonNode> modelConf = new HashMap<>(); // MODEL_CONF 用于眠枭之星颜色判定

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

        // 2d. 为 NPC 名称匹配的目标（眠枭之星、宝箱）添加类型映射并提取图标
        // 眠枭之星：按颜色分别复制图标文件
        String chestIconFile = null;
        Map<String, String> owlColorIcons = new HashMap<>();
        for (String name : paramToMaterial.values()) {
            if (nameToTypeMap.containsKey(name)) continue;
            if (name.contains("眠枭之星")) {
                nameToTypeMap.put(name, "眠枭之星");
                String color = extractColorFromName(name);
                String coloredIconName = "眠枭之星（" + color + "）.png";
                if (!owlColorIcons.containsKey(color)) {
                    String result = tryCopyOwlColorIcon(color, coloredIconName, iconDir);
                    if (result != null) {
                        owlColorIcons.put(color, result);
                    }
                }
                String iconFile = owlColorIcons.get(color);
                if (iconFile != null) {
                    nameToIconMap.put(name, iconFile);
                }
            } else if (name.contains("宝箱")) {
                nameToTypeMap.put(name, "宝箱");
                if (chestIconFile == null) {
                    chestIconFile = tryExtractNpcIcon(name, iconDir);
                }
                if (chestIconFile != null) {
                    nameToIconMap.put(name, chestIconFile);
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
        File outFile = new File(BASE_DIR, "internal_resource_point.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(outFile, root);
        log.info("批量导出并去重完成！");
        log.info("生成总点位数: {}", root.size());
        log.info("结果文件: {}", outFile.getAbsolutePath());
    }

    private void loadData() throws IOException {
        File dataDir = new File(BIN_COMPRESSED);

        // 场景配置（地图中心、边长）— 使用合并补丁数据
        Map<Integer, JsonNode> blockConf = loadMergedData("WORLD_MAP_BLOCK_CONF.json");
        blockConf.values().forEach(r -> {
            if (r.has("map_center_position_xyz") && r.has("side_length")) {
                String[] c = r.get("map_center_position_xyz").asText().split(";");
                String id = r.get("scene_res_id").asText();
                sceneCenters.put(id, new double[]{Double.parseDouble(c[0]), Double.parseDouble(c[1])});
                sceneSideLengths.put(id, r.get("side_length").asDouble());
            }
        });

        // 使用合并补丁数据加载（base → _0_P → _1_P → _2_P → _3_P，后覆盖前）
        npcConf.putAll(loadMergedData("NPC_REFRESH_CONTENT_CONF.json"));
        areaConf.putAll(loadMergedData("AREA_CONF.json"));
        objConf.putAll(loadMergedData("SCENE_OBJECT_CONF.json"));
        npcDef.putAll(loadMergedData("NPC_CONF.json"));

        // BAG_ITEM_CONF 优先使用 _2_P 数据（包 Split 补丁，有更正后的物品名称）
        // 回退到用户自己的解包数据
        File bagItemFile = new File(BIN_COMPRESSED_2P, "BAG_ITEM_CONF.json");
        if (!bagItemFile.exists()) bagItemFile = new File(dataDir, "BAG_ITEM_CONF.json");
        fill(itemConf, bagItemFile);
        fill(itemTypeConf, new File(dataDir, "BAG_ITEM_TYPE_CONF.json"));
        fill(npcOptionConf, new File(dataDir, "NPC_OPTION_CONF.json"));
        modelConf.putAll(loadMergedData("MODEL_CONF.json"));

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

        // 补充2: 按 NPC 名称匹配（眠枭之星、宝箱）
        loadNpcTargets();

        log.info("paramToMaterial 最终: {} 种材料, {} 个 param_id",
                paramToMaterial.values().stream().distinct().count(), paramToMaterial.size());

    }

    /**
     * 从多级补丁目录合并加载 JSON 数据，后加载的覆盖先加载的同 key 条目。
     */
    private Map<Integer, JsonNode> loadMergedData(String fileName) throws IOException {
        Map<Integer, JsonNode> merged = new HashMap<>();
        for (String patchDir : PATCH_DIRS) {
            File f = new File(PATCH_DATA_DIR, patchDir + "\\Bin\\BinDataCompressed\\" + fileName);
            if (f.exists()) {
                mapper.readTree(f).get("RocoDataRows").fields().forEachRemaining(e -> {
                    try {
                        merged.put(Integer.parseInt(e.getKey()), e.getValue());
                    } catch (NumberFormatException ignore) {
                    }
                });
            }
        }
        return merged;
    }

    /**
     * 按 NPC_CONF.name 匹配目标 NPC（眠枭之星、大世界宝箱），加入 paramToMaterial。
     * 排除"废弃"条目；宝箱类排除"副本"（仅大世界）。
     */
    private void loadNpcTargets() {
        int owlCount = 0;
        int chestCount = 0;
        for (var e : npcDef.entrySet()) {
            int npcId = e.getKey();
            if (paramToMaterial.containsKey(npcId)) continue;
            JsonNode row = e.getValue();
            if (!row.has("name")) continue;
            String name = row.get("name").asText();
            if (name.isEmpty() || name.contains("废弃")) continue;

            if (name.contains("眠枭之星")) {
                String color = getOwlStarColor(row);
                paramToMaterial.put(npcId, "眠枭之星（" + color + "）");
                owlCount++;
            } else if (name.contains("宝箱")) {
                if (name.contains("副本")) continue;
                String normalized = normalizeChestName(name);
                if (!name.equals(normalized)) {
                    log.info("宝箱名称规范化: [{}] → [{}]", name, normalized);
                }
                paramToMaterial.put(npcId, normalized);
                chestCount++;
            }
        }
        log.info("NPC 名称匹配: 眠枭之星 {} 个, 宝箱 {} 个", owlCount, chestCount);
    }

    /**
     * 根据 NPC 的 model_conf → MODEL_CONF 蓝图路径判定眠枭之星颜色。
     */
    private String getOwlStarColor(JsonNode npcRow) {
        if (!npcRow.has("model_conf")) return "蓝";
        int mcId = npcRow.get("model_conf").asInt();
        JsonNode mc = modelConf.get(mcId);
        if (mc == null || !mc.has("path")) return "蓝";
        String path = mc.get("path").asText();
        if (path.contains("Purple") || path.contains("Gulitianguo3")) return "紫";
        if (path.contains("Yellow") || path.contains("Gulitianguo2")) return "黄";
        return "蓝";
    }

    /** 从已带颜色后缀的 NPC 名称中提取颜色字符（蓝/黄/紫）。 */
    private String extractColorFromName(String name) {
        if (name.contains("（蓝）")) return "蓝";
        if (name.contains("（黄）")) return "黄";
        if (name.contains("（紫）")) return "紫";
        return "蓝";
    }

    /** 宝箱名称规范化：从原始名称中分别提取"xx系"和"x级"，拼装为"xx系x级宝箱"或"x级宝箱"。 */
    private static final Pattern CHEST_TYPE_PATTERN = Pattern.compile("(\\S+系)");
    private static final Pattern CHEST_LEVEL_PATTERN = Pattern.compile("([\\d一二三四五六七八九十]+级)");

    private static String normalizeChestName(String name) {
        Matcher typeMatcher = CHEST_TYPE_PATTERN.matcher(name);
        String type = typeMatcher.find() ? typeMatcher.group(1) : "";

        Matcher levelMatcher = CHEST_LEVEL_PATTERN.matcher(name);
        String level = levelMatcher.find() ? levelMatcher.group(1) : "";

        return type + level + "宝箱";
    }

    /**
     * 将眠枭之星源图标复制为指定颜色的图标文件。
     * 先尝试找任意一个眠枭之星 NPC 的源图标，再复制到颜色文件名。
     */
    private String tryCopyOwlColorIcon(String color, String targetFileName, File iconDir) {
        // 找一个眠枭之星 NPC 来获取源图标
        for (var e : paramToMaterial.entrySet()) {
            String name = e.getValue();
            if (!name.contains("眠枭之星")) continue;
            String srcIcon = tryExtractNpcIcon(name, iconDir);
            if (srcIcon != null) {
                Path srcPath = Path.of(iconDir.getAbsolutePath(), srcIcon);
                Path dstPath = Path.of(iconDir.getAbsolutePath(), targetFileName);
                try {
                    Files.copy(srcPath, dstPath, StandardCopyOption.REPLACE_EXISTING);
                    return targetFileName;
                } catch (IOException ignore) {
                }
            }
            break;
        }
        return null;
    }

    /**
     * 为 NPC 名称匹配的目标（宝箱、眠枭之星等）提取图标。
     * 先尝试 NPC_OPTION_CONF 的 button_icon 路径，失败则回退到已知资源路径。
     *
     * @return 图标文件名（如 "宝箱.png"），失败返回 null
     */
    private String tryExtractNpcIcon(String npcName, File iconDir) {
        // 1. 找出映射到此 NPC 名称的 npc_id
        Integer npcId = null;
        for (var e : paramToMaterial.entrySet()) {
            if (npcName.equals(e.getValue())) { npcId = e.getKey(); break; }
        }
        if (npcId == null) return null;

        JsonNode npcRow = npcDef.get(npcId);
        if (npcRow == null) return null;

        // 2. 尝试从 NPC_OPTION_CONF.button_icon 复制
        if (npcRow.has("option_id")) {
            for (JsonNode optIdNode : npcRow.get("option_id")) {
                JsonNode optRow = npcOptionConf.get(optIdNode.asInt());
                if (optRow == null || !optRow.has("button_icon")) continue;
                String btnIcon = optRow.get("button_icon").asText();
                if (!btnIcon.contains("'")) continue;
                String iconPath = btnIcon.replace("Game", "Content");
                iconPath = iconPath.substring(iconPath.indexOf("'") + 1, iconPath.lastIndexOf(".") + 1) + "png";
                Path source = Paths.get(RESOURCE_DIR, iconPath);
                if (Files.exists(source)) {
                    try {
                        String targetName = getCategoryName(npcName) + ".png";
                        Files.copy(source, Path.of(iconDir.getAbsolutePath(), targetName),
                                StandardCopyOption.REPLACE_EXISTING);
                        return targetName;
                    } catch (IOException ignore) {
                    }
                }
            }
        }

        // 3. 回退：已知资源路径
        String fallbackPath = getFallbackIconPath(npcName);
        if (fallbackPath != null) {
            Path source = Paths.get(RESOURCE_DIR, fallbackPath);
            if (Files.exists(source)) {
                try {
                    String targetName = getCategoryName(npcName) + ".png";
                    Files.copy(source, Path.of(iconDir.getAbsolutePath(), targetName),
                            StandardCopyOption.REPLACE_EXISTING);
                    return targetName;
                } catch (IOException ignore) {
                }
            }
        }
        return null;
    }

    /** 根据 NPC 名称返回类别名（用作图标文件名前缀） */
    private String getCategoryName(String npcName) {
        if (npcName.contains("眠枭之星")) return "眠枭之星";
        if (npcName.contains("宝箱")) return "宝箱";
        return npcName;
    }

    /** 已知的图标回退路径（相对于 RESOURCE_DIR） */
    private String getFallbackIconPath(String npcName) {
        if (npcName.contains("宝箱")) {
            return "Content\\NewRoco\\Modules\\System\\Activity\\Raw\\Textures\\img_tongyong_baoxiang.png";
        }
        if (npcName.contains("眠枭之星")) {
            return "Content\\NewRoco\\Modules\\System\\BigMap\\Raw\\Atlas\\WorldMapNpc\\Frames\\starsouls_01.png";
        }
        return null;
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
