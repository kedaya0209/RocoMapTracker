package com.luoke.app.map.loader;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.dto.MapConfig;
import com.luoke.app.map.parse.JsMapConfigParser;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

@Slf4j
public class MapConfigLoader {

    public static MapConfig load() {
        try {
            log.info("正在拉取地图配置...");
            Document doc = Jsoup.connect(AppConfig.MAP_RESOURCE_INFO_URL)
                    .userAgent("Mozilla/5.0")
                    .referrer("https://wiki.biligame.com/")
                    .timeout(15000)
                    .get();

            Element script = doc.select("script").stream()
                    .filter(e -> e.data().contains("window.mapData = {"))
                    .findFirst().orElse(null);

            if (script == null) {
                log.warn("⚠️ 未找到地图配置脚本");
                return null;
            }

            log.info("✅ 地图配置拉取成功，开始解析");
            return JsMapConfigParser.parse(script.data());

        } catch (Exception e) {
            log.error("❌ 地图配置加载失败", e);
            return null;
        }
    }
}