package io.github.kedaya0209.roco.app.map.parse;

import io.github.kedaya0209.roco.app.map.dto.LayerOption;
import io.github.kedaya0209.roco.app.map.dto.MapConfig;
import io.github.kedaya0209.roco.app.map.dto.MapLayer;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.IOException;

/**
 * JavaScript地图配置解析器
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
@ThreadSafe
public class JsMapConfigParser {

    public static MapConfig parse(String jsContent) {
        try {
            // 创建地图配置对象
            MapConfig config = new MapConfig();

            // 解析数值类型的配置
            // 使用正则表达式提取配置值，缺失时使用默认值0
            config.setZoom(getInt(jsContent, "zoom:\\s*(\\d+)"));
            config.setMaxZoom(getInt(jsContent, "maxZoom:\\s*(\\d+)"));
            config.setMinZoom(getInt(jsContent, "minZoom:\\s*(\\d+)"));

            // 解析布尔类型的配置
            // 使用正则表达式提取布尔值，缺失时使用默认值false
            config.setLayerControl(getBool(jsContent, "layerControl:\\s*(true|false)"));

            // 设置中心点和边界（使用默认值）
            // 这些值通常在前端动态计算，后端使用默认值即可
            config.setCenter(List.of(0D, 0D));
            config.setMaxBounds(List.of(List.of(0, 0), List.of(0, 0)));

            // 解析字符串类型的配置
            // 使用正则表达式提取字符串值，缺失时使用默认值空字符串
            config.setLogo(getStr(jsContent, "logo:\\s*\"([^\"]+)\""));
            config.setPointShadow(getStr(jsContent, "pointShadow:\\s*\"([^\"]+)\""));
            config.setMapBG(getStr(jsContent, "mapBG:\\s*\"([^\"]+)\""));
            config.setDataPrefix(getStr(jsContent, "dataPrefix:\\s*\"([^\"]+)\""));

            // 设置数据列表（使用默认值）
            // 默认加载point.json文件
            config.setDataList(List.of("point.json"));

            // 解析地图图层信息
            List<MapLayer> layers = new ArrayList<>();

            // 使用复杂正则表达式提取图层信息
            // DOTALL模式支持跨行匹配，使用分组捕获提取name、index、tileUrl
            // 正则表达式分解：
            // - "name:\\s*\"([^\"]+)\"": 匹配图层名称
            // - ".*?index:\\s*(-?\\d+)": 匹配图层索引（支持负数）
            // - ".*?tileUrl:\\s*\"([^\"]+)\"": 匹配瓦片URL
            Matcher matcher = Pattern.compile(
                    "name:\\s*\"([^\"]+)\".*?index:\\s*(-?\\d+).*?tileUrl:\\s*\"([^\"]+)\"",
                    Pattern.DOTALL  // DOTALL模式，使.匹配包括换行符在内的所有字符
            ).matcher(jsContent);

            // 循环查找所有匹配的图层
            while (matcher.find()) {
                // 创建图层对象
                MapLayer layer = new MapLayer();

                // 从正则表达式的分组中提取值
                // group(1): 图层名称
                // group(2): 图层索引
                // group(3): 瓦片URL
                layer.setName(matcher.group(1));
                layer.setIndex(Integer.parseInt(matcher.group(2)));

                // 创建图层选项对象，传入瓦片URL
                layer.setLayerOption(new LayerOption(matcher.group(3)));

                // 将图层添加到列表
                layers.add(layer);
            }

            // 设置图层数据
            config.setMapLayers(layers);

            // 记录解析成功的日志
            log.info("✅ 地图配置解析完成，图层数量：{}", layers.size());

            return config;
        } catch (RuntimeException e) {
            // 捕获所有异常，记录错误日志
            // 返回null表示解析失败
            log.error("❌ 地图JS解析失败", e);
            return null;
        }
    }

    private static String getStr(String s, String regex) {
        // 编译正则表达式并创建匹配器
        Matcher m = Pattern.compile(regex).matcher(s);

        // 查找匹配，找到则返回分组1的值，否则返回空字符串
        return m.find() ? m.group(1) : "";
    }


    private static int getInt(String s, String regex) {
        // 编译正则表达式并创建匹配器
        Matcher m = Pattern.compile(regex).matcher(s);

        // 查找匹配，找到则返回分组1的整数值，否则返回0
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static boolean getBool(String s, String regex) {
        // 编译正则表达式并创建匹配器
        Matcher m = Pattern.compile(regex).matcher(s);

        // 查找匹配，找到则返回分组1的布尔值，否则返回false
        // Boolean.parseBoolean会将字符串"true"转为true，其他转为false
        return m.find() && Boolean.parseBoolean(m.group(1));
    }
}
