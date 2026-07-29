package io.github.kedaya0209.roco.app.map.loader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.map.dto.MapPointItem;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@ThreadSafe
public class MapPointLoader {

    private static final ObjectMapper om = JsonUtils.getMapper();

    public static List<MapPointItem> load(List<String> dataList) {
        List<String> files = (dataList != null && !dataList.isEmpty())
                ? dataList
                : List.of("point.json");

        String baseUrl = DownloadConfig.MAP_RESOURCE_POINT_URL;

        baseUrl = baseUrl + MapConfigLoader.load().getDataPrefix() + "/";

        List<MapPointItem> allItems = new ArrayList<>();
        for (String file : files) {
            List<MapPointItem> items = loadSingle(baseUrl + file, file);
            allItems.addAll(items);
        }

        log.info("✅ 全部点位解析完成，共 {} 条（来自 {} 个文件）", allItems.size(), files.size());
        return allItems;
    }

    private static List<MapPointItem> loadSingle(String url, String fileName) {
        try {
            log.info("正在拉取点位数据: {}", fileName);

            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0")
                    .referrer("https://wiki.biligame.com/")
                    .timeout(15000)
                    .get();

            Element pre = doc.getElementById("mapPointData");
            if (pre == null) {
                log.warn("⚠️ {} 中未找到点位数据", fileName);
                return List.of();
            }

            log.info("✅ {} 拉取成功，开始解析", fileName);

            String text = pre.text().trim()
                    .replaceAll("<[^>]+>", "");

            List<MapPointItem> items = extractItems(text);

            log.info("✅ {} 解析完成，共 {} 条", fileName, items.size());
            return items;

        } catch (IOException e) {
            log.error("❌ {} 加载失败", fileName, e);
            return List.of();
        }
    }

    /**
     * 从文本中按 key -> 数组结构提取点位数据。
     * 只解析值为 JSON 数组的键，自动跳过 wiki Data 引用、HTML 等无效值。
     */
    private static List<MapPointItem> extractItems(String text) {
        List<MapPointItem> items = new ArrayList<>();
        Matcher m = Pattern.compile("\"?(\\d+)\"?\\s*:\\s*\\[").matcher(text);

        while (m.find()) {
            int bracketStart = m.end() - 1;
            int bracketEnd = findMatchingBracket(text, bracketStart);
            if (bracketEnd < 0) {
                continue;
            }

            String arrayJson = text.substring(bracketStart, bracketEnd + 1)
                    .replaceAll(",\\s*([}\\]])", "$1");
            try {
                JsonNode arrayNode = om.readTree(arrayJson);
                if (arrayNode.isArray()) {
                    for (JsonNode node : arrayNode) {
                        try {
                            items.add(om.treeToValue(node, MapPointItem.class));
                        } catch (JsonProcessingException e) {
                            log.warn("点位解析跳过一条无效数据", e);
                        }
                    }
                }
            } catch (JsonProcessingException e) {
                log.warn("跳过无效数组片段", e);
            }
        }

        return items;
    }

    private static int findMatchingBracket(String text, int start) {
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (!inString) {
                if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

}
