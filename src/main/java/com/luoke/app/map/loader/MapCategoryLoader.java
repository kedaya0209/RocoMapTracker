package com.luoke.app.map.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.dto.MapCategoryItem;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class MapCategoryLoader {

    private static final ObjectMapper om = new ObjectMapper();

    public static List<MapCategoryItem> load() {
        try {
            log.info("正在拉取分类数据...");
            Document doc = Jsoup.connect(AppConfig.MAP_RESOURCE_INFO_URL)
                    .userAgent("Mozilla/5.0")
                    .referrer("https://wiki.biligame.com/")
                    .timeout(15000)
                    .get();

            Element pre = doc.getElementById("categoryData");
            if (pre == null) {
                log.warn("⚠️ 未找到分类数据");
                return new ArrayList<>();
            }

            log.info("✅ 分类拉取成功，开始解析");
            var root = om.readTree(pre.text());
            var dataArray = root.get("data");

            List<MapCategoryItem> list = new ArrayList<>();
            if (dataArray.isArray()) {
                dataArray.forEach(node -> {
                    try {
                        list.add(om.treeToValue(node, MapCategoryItem.class));
                    } catch (Exception e) {
                        log.debug("分类解析跳过一条无效数据");
                    }
                });
            }

            log.info("✅ 分类解析完成，共 {} 条", list.size());
            return list;

        } catch (Exception e) {
            log.error("❌ 分类加载失败", e);
            return new ArrayList<>();
        }
    }
}