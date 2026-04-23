package com.luoke.app.map.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.dto.MapPointItem;
import com.luoke.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class MapPointLoader {

    private static final ObjectMapper om = JsonUtils.getMapper();

    public static List<MapPointItem> load() {
        try {
            log.info("正在拉取地图点位数据...");
            Document doc = Jsoup.connect(AppConfig.MAP_RESOURCE_POINT_URL)
                    .userAgent("Mozilla/5.0")
                    .referrer("https://wiki.biligame.com/")
                    .timeout(15000)
                    .get();

            Element pre = doc.getElementById("mapPointData");
            if (pre == null) {
                log.warn("⚠️ 未找到点位数据");
                return new ArrayList<>();
            }

            log.info("✅ 点位拉取成功，开始解析");
            String json = pre.text().trim()
                    .replaceAll(":Data:.{0,30}?/json", ":[]")
                    .replaceAll("(\\d+):", "\"$1\":");

            List<MapPointItem> items = new ArrayList<>();
            om.readTree(json).fields().forEachRemaining(entry -> {
                if (entry.getValue().isArray()) {
                    entry.getValue().forEach(node -> {
                        try {
                            items.add(om.treeToValue(node, MapPointItem.class));
                        } catch (Exception e) {
                            log.debug("点位解析跳过一条无效数据", e);
                        }
                    });
                }
            });

            log.info("✅ 点位解析完成，共 {} 条", items.size());
            return items;

        } catch (Exception e) {
            log.error("❌ 点位加载失败", e);
            return new ArrayList<>();
        }
    }
}