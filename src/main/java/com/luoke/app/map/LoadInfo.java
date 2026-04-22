package com.luoke.app.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.map.dto.MapConfig;
import com.luoke.app.map.dto.MapPointItem;
import com.luoke.app.map.loader.MapCategoryLoader;
import com.luoke.app.map.loader.MapConfigLoader;
import com.luoke.app.map.loader.MapPointLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class LoadInfo {

    public static void remoteResolveConfig() {
        log.info("=====================================");
        log.info("开始远程加载地图配置");
        log.info("=====================================");

        MapConfig cfg = MapConfigLoader.load();
        if (cfg == null) return;

        AppConfig.MAP_MAX_ZOOM = cfg.getMaxZoom() > 0 ? cfg.getMaxZoom() : AppConfig.MAP_MAX_ZOOM;
        AppConfig.MAP_MIN_ZOOM = cfg.getMinZoom() > 0 ? cfg.getMinZoom() : AppConfig.MAP_MIN_ZOOM;

        List<Integer> sortList = new ArrayList<>();
        List<String> urlList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();

        cfg.getMapLayers().forEach(layer -> {
            String name = layer.getName();
            int index = layer.getIndex();
            String url = layer.getLayerOption().getTileUrl();

            if (name != null && !name.isBlank() && url != null && !url.isBlank()) {
                sortList.add(index);
                urlList.add(url);
                nameList.add(name);
            }
        });

        if (!sortList.isEmpty()) {
            AppConfig.MAP_REMOTE_URLS = urlList.stream()
                    .map(u -> u.replace("{z}", String.valueOf(AppConfig.MAP_ZOOM)))
                    .toArray(String[]::new);

            AppConfig.MAP_REMOTE_URL_SORT = sortList.stream().mapToInt(i -> i).toArray();
            AppConfig.MAP_REMOTE_URL_NAME = nameList.toArray(String[]::new);

            log.info("✅ 远程配置加载完成，地图数量：{}", urlList.size());
        } else {
            log.warn("⚠️ 未解析到任何地图图层");
        }
    }

    public static List<MapPointItem> parsePointJson() {
        return MapPointLoader.load();
    }

    public static List<MapCategoryItem> parseCategoryData() {
        return MapCategoryLoader.load();
    }

    public static MapConfig getMapConfig() {
        return MapConfigLoader.load();
    }
}