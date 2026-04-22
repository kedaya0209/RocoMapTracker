package com.luoke.app.map.parse;

import com.luoke.app.map.dto.LayerOption;
import com.luoke.app.map.dto.MapConfig;
import com.luoke.app.map.dto.MapLayer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class JsMapConfigParser {

    public static MapConfig parse(String jsContent) {
        try {
            MapConfig config = new MapConfig();
            config.setZoom(getInt(jsContent, "zoom:\\s*(\\d+)"));
            config.setMaxZoom(getInt(jsContent, "maxZoom:\\s*(\\d+)"));
            config.setMinZoom(getInt(jsContent, "minZoom:\\s*(\\d+)"));
            config.setLayerControl(getBool(jsContent, "layerControl:\\s*(true|false)"));
            config.setCenter(List.of(0D, 0D));
            config.setMaxBounds(List.of(List.of(0, 0), List.of(0, 0)));
            config.setLogo(getStr(jsContent, "logo:\\s*\"([^\"]+)\""));
            config.setPointShadow(getStr(jsContent, "pointShadow:\\s*\"([^\"]+)\""));
            config.setMapBG(getStr(jsContent, "mapBG:\\s*\"([^\"]+)\""));
            config.setDataPrefix(getStr(jsContent, "dataPrefix:\\s*\"([^\"]+)\""));
            config.setDataList(List.of("point.json"));

            List<MapLayer> layers = new ArrayList<>();
            Matcher matcher = Pattern.compile(
                    "name:\\s*\"([^\"]+)\".*?index:\\s*(-?\\d+).*?tileUrl:\\s*\"([^\"]+)\"",
                    Pattern.DOTALL
            ).matcher(jsContent);

            while (matcher.find()) {
                MapLayer layer = new MapLayer();
                layer.setName(matcher.group(1));
                layer.setIndex(Integer.parseInt(matcher.group(2)));
                layer.setLayerOption(new LayerOption(matcher.group(3)));
                layers.add(layer);
            }

            config.setMapLayers(layers);
            log.info("✅ 地图配置解析完成，图层数量：{}", layers.size());
            return config;
        } catch (Exception e) {
            log.error("❌ 地图JS解析失败", e);
            return null;
        }
    }

    private static String getStr(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static int getInt(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean getBool(String s, String regex) {
        Matcher m = Pattern.compile(regex).matcher(s);
        return m.find() && Boolean.parseBoolean(m.group(1));
    }
}