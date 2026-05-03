package com.luoke.app.map.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.map.LoadInfo;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.map.dto.MapPointItem;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源配置构建器
 * 从地图分类和点位数据构建资源配置文件
 */
@Slf4j
public class ResourceConfigBuilder {
    private static final ObjectMapper om = JsonUtils.getMapper();

    public static void buildAndSaveConfig() {
        try {
            List<MapCategoryItem> categories = LoadInfo.parseCategoryData();
            List<MapPointItem> points = LoadInfo.parsePointJson();

            Map<Integer, MapCategoryItem> catMap = new HashMap<>();
            for (MapCategoryItem cat : categories) {
                if (cat.getMarkType() != null) {
                    catMap.put(cat.getMarkType(), cat);
                }
            }

            List<ResourceConfig> list = new ArrayList<>();
            for (MapPointItem point : points) {
                Integer type = point.getMarkType();
                MapCategoryItem cat = catMap.get(type);

                if (cat == null) continue;

                ResourceConfig cfg = getResourceConfig(point, cat);
                list.add(cfg);
            }

            File out = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR, "resource_config.json");
            om.writerWithDefaultPrettyPrinter().writeValue(out, list);

            log.info("✅ 配置生成完成：{} 条", list.size());

        } catch (Exception e) {
            log.error("❌ 生成配置失败", e);
        }
    }

    private static ResourceConfig getResourceConfig(MapPointItem point, MapCategoryItem cat) {
        ResourceConfig cfg = new ResourceConfig();

        cfg.setType(cat.getType());
        cfg.setMarkType(cat.getMarkType());
        cfg.setMarkTypeName(cat.getMarkTypeName());

        String icon = cat.getIcon();
        String fileName = icon.substring(icon.lastIndexOf("/") + 1);
        cfg.setIcon(fileName);

        cfg.setLat(point.getPoint().getLat());
        cfg.setLng(point.getPoint().getLng());
        cfg.setLayer(point.getLayer());

        return cfg;
    }
}
