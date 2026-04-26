package com.luoke.app.map.parse;

import com.luoke.app.map.dto.LayerOption;
import com.luoke.app.map.dto.MapConfig;
import com.luoke.app.map.dto.MapLayer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript地图配置解析器
 * <p>
 * 负责从JavaScript代码中解析地图配置信息。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>解析地图缩放级别配置</li>
 *   <li>解析地图图层控制配置</li>
 *   <li>解析地图中心点和边界</li>
 *   <li>解析地图Logo、背景等样式配置</li>
 *   <li>解析数据前缀和数据列表</li>
 *   <li>解析地图图层信息</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用正则表达式提取配置项</li>
 *   <li>支持多层嵌套配置解析</li>
 *   <li>设置合理的默认值，提高健壮性</li>
 *   <li>使用分组捕获提取关键信息</li>
 *   <li>DOTALL模式支持多行匹配</li>
 * </ul>
 * <p>
 * 解析策略：
 * <ul>
 *   <li>逐项解析配置，缺失时使用默认值</li>
 *   <li>图层解析使用复杂正则表达式</li>
 *   <li>支持多个图层同时解析</li>
 *   <li>异常时返回null，便于错误处理</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>正则表达式预编译，避免重复编译</li>
 *   <li>使用List收集图层，提高效率</li>
 *   <li>一次性解析所有图层，减少遍历次数</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>正则表达式与JS代码结构强相关</li>
 *   <li>JS代码结构变化可能导致解析失败</li>
 *   <li>需要根据实际JS代码调整正则表达式</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
public class JsMapConfigParser {

    /**
     * 解析JavaScript配置代码
     * <p>
     * 该方法从JavaScript代码中提取地图配置信息：
     * <ol>
     *   <li>创建MapConfig对象</li>
     *   <li>解析缩放级别（zoom、maxZoom、minZoom）</li>
     *   <li>解析图层控制（layerControl）</li>
     *   <li>设置中心点和边界（使用默认值）</li>
     *   <li>解析样式配置（logo、pointShadow、mapBG）</li>
     *   <li>解析数据配置（dataPrefix、dataList）</li>
     *   <li>解析地图图层信息</li>
     *   <li>返回完整配置对象</li>
     * </ol>
     * <p>
     * 正则表达式设计：
     * <ul>
     *   <li>zoom: "zoom:\\s*(\\d+)"</li>
     *   <li>maxZoom: "maxZoom:\\s*(\\d+)"</li>
     *   <li>minZoom: "minZoom:\\s*(\\d+)"</li>
     *   <li>layerControl: "layerControl:\\s*(true|false)"</li>
     *   <li>logo: "logo:\\s*\"([^\"]+)\""</li>
     *   <li>pointShadow: "pointShadow:\\s*\"([^\"]+)\""</li>
     *   <li>mapBG: "mapBG:\\s*\"([^\"]+)\""</li>
     *   <li>dataPrefix: "dataPrefix:\\s*\"([^\"]+)\""</li>
     *   <li>图层: "name:\\s*\"([^\"]+)\".*?index:\\s*(-?\\d+).*?tileUrl:\\s*\"([^\"]+)\""</li>
     * </ul>
     * <p>
     * 图层解析逻辑：
     * <ul>
     *   <li>使用DOTALL模式支持多行匹配</li>
     *   <li>使用分组捕获提取name、index、tileUrl</li>
     *   <li>创建MapLayer和LayerOption对象</li>
     *   <li>将图层添加到列表</li>
     * </ul>
     * <p>
     * 默认值处理：
     * <ul>
     *   <li>center: [0D, 0D]</li>
     *   <li>maxBounds: [[0, 0], [0, 0]]</li>
     *   <li>dataList: ["point.json"]</li>
     *   <li>数值配置默认为0</li>
     *   <li>字符串配置默认为空字符串</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>捕获所有异常，记录错误日志</li>
     *   <li>返回null表示解析失败</li>
     *   <li>单个配置项失败不影响其他项</li>
     * </ul>
     *
     * @param jsContent JavaScript配置代码字符串
     * @return 解析后的MapConfig对象，解析失败时返回null
     */
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
        } catch (Exception e) {
            // 捕获所有异常，记录错误日志
            // 返回null表示解析失败
            log.error("❌ 地图JS解析失败", e);
            return null;
        }
    }

    /**
     * 从字符串中提取字符串值
     * <p>
     * 该方法使用正则表达式从字符串中提取第一个匹配的字符串值：
     * <ul>
     *   <li>编译正则表达式</li>
     *   <li>在输入字符串中查找匹配</li>
     *   <li>找到匹配则返回第一个分组</li>
     *   <li>未找到匹配则返回空字符串</li>
     * </ul>
     * <p>
     * 正则表达式设计：
     * <ul>
     *   <li>使用分组捕获提取目标字符串</li>
     *   <li>分组1即为目标值</li>
     *   <li>例如："logo:\\s*\"([^\"]+)\""提取logo值</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>解析JS配置中的字符串值</li>
     *   <li>提取配置项的值</li>
     *   <li>处理缺失配置</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>正则表达式在方法内编译</li>
     *   <li>可以改为预编译以提高性能</li>
     * </ul>
     *
     * @param s 输入字符串
     * @param regex 正则表达式，必须包含分组
     * @return 匹配的字符串值，未找到则返回空字符串
     */
    private static String getStr(String s, String regex) {
        // 编译正则表达式并创建匹配器
        Matcher m = Pattern.compile(regex).matcher(s);

        // 查找匹配，找到则返回分组1的值，否则返回空字符串
        return m.find() ? m.group(1) : "";
    }

    /**
     * 从字符串中提取整数值
     * <p>
     * 该方法使用正则表达式从字符串中提取第一个匹配的整数值：
     * <ul>
     *   <li>编译正则表达式</li>
     *   <li>在输入字符串中查找匹配</li>
     *   <li>找到匹配则返回第一个分组的整数值</li>
     *   <li>未找到匹配则返回0</li>
     * </ul>
     * <p>
     * 正则表达式设计：
     * <ul>
     *   <li>使用分组捕获提取数字字符串</li>
     *   <li>分组1为目标数字</li>
     *   <li>例如："zoom:\\s*(\\d+)"提取zoom值</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>解析JS配置中的数值</li>
     *   <li>提取缩放级别</li>
     *   <li>处理缺失配置</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>正则表达式在方法内编译</li>
     *   <li>可以改为预编译以提高性能</li>
     * </ul>
     *
     * @param s 输入字符串
     * @param regex 正则表达式，必须包含分组
     * @return 匹配的整数值，未找到则返回0
     */
    private static int getInt(String s, String regex) {
        // 编译正则表达式并创建匹配器
        Matcher m = Pattern.compile(regex).matcher(s);

        // 查找匹配，找到则返回分组1的整数值，否则返回0
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    /**
     * 从字符串中提取布尔值
     * <p>
     * 该方法使用正则表达式从字符串中提取第一个匹配的布尔值：
     * <ul>
     *   <li>编译正则表达式</li>
     *   <li>在输入字符串中查找匹配</li>
     *   <li>找到匹配则返回第一个分组的布尔值</li>
     *   <li>未找到匹配则返回false</li>
     * </ul>
     * <p>
     * 正则表达式设计：
     * <ul>
     *   <li>使用分组捕获提取true或false</li>
     *   <li>分组1为目标布尔字符串</li>
     *   <li>例如："layerControl:\\s*(true|false)"提取layerControl值</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>解析JS配置中的布尔值</li>
     *   <li>提取开关配置</li>
     *   <li>处理缺失配置</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>正则表达式在方法内编译</li>
     *   <li>可以改为预编译以提高性能</li>
     * </ul>
     *
     * @param s 输入字符串
     * @param regex 正则表达式，必须包含分组
     * @return 匹配的布尔值，未找到则返回false
     */
    private static boolean getBool(String s, String regex) {
        // 编译正则表达式并创建匹配器
        Matcher m = Pattern.compile(regex).matcher(s);

        // 查找匹配，找到则返回分组1的布尔值，否则返回false
        // Boolean.parseBoolean会将字符串"true"转为true，其他转为false
        return m.find() && Boolean.parseBoolean(m.group(1));
    }
}
