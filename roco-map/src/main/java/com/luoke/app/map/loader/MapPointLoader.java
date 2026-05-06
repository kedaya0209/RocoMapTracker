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
            // 记录开始加载的日志，便于追踪加载过程
            log.info("正在拉取地图点位数据...");

            // 使用Jsoup访问远程页面，获取HTML内容
            // 设置User-Agent和Referer模拟浏览器请求，避免被服务器拒绝
            // 设置超时时间为15秒，防止长时间阻塞
            Document doc = Jsoup.connect(AppConfig.MAP_RESOURCE_POINT_URL)
                    .userAgent("Mozilla/5.0")  // 模拟Chrome浏览器
                    .referrer("https://wiki.biligame.com/")  // 设置Referer
                    .timeout(15000)  // 设置超时时间为15秒
                    .get();  // 发送GET请求

            // 从HTML中查找id为"mapPointData"的pre标签
            // 这个pre标签包含了点位数据的JSON文本
            Element pre = doc.getElementById("mapPointData");
            if (pre == null) {
                // 未找到点位数据，记录警告日志并返回空列表
                // 这种情况可能是页面结构变化或数据未加载
                log.warn("⚠️ 未找到点位数据");
                return new ArrayList<>();
            }

            // 记录点位拉取成功的日志
            log.info("✅ 点位拉取成功，开始解析");

            // 获取pre标签的文本内容并进行数据清洗
            String json = pre.text().trim()
                    // 移除B站Wiki的特殊语法:Data:.../json
                    // 这种语法不是标准JSON格式，需要移除或替换
                    // 使用正则表达式匹配:Data:后面跟着最多30个字符，然后是/json
                    .replaceAll(":Data:.{0,30}?/json", ":[]")
                    // 转换数字键名为字符串键名
                    // JSON要求键名必须是双引号包围的字符串
                    // 但原始数据可能使用数字作为键名（如：{"1": ...}）
                    // 所以需要将数字键名转换为字符串键名（如：{"1": ...}）
                    .replaceAll("(\\d+):", "\"$1\":");

            // 初始化结果列表，用于存储解析后的点位数据
            List<MapPointItem> items = new ArrayList<>();

            // 解析JSON数据并遍历所有字段
            // readTree方法将JSON字符串解析为Jackson的JsonNode对象
            // fields()方法返回所有字段的迭代器，每个字段是一个键值对
            om.readTree(json).fields().forEachRemaining(entry -> {
                // 检查当前字段的值是否为数组类型
                if (entry.getValue().isArray()) {
                    // 遍历数组中的每个元素
                    entry.getValue().forEach(node -> {
                        try {
                            // 将JsonNode转换为MapPointItem对象
                            // treeToValue方法使用Jackson的自动映射功能
                            // 将JSON对象转换为Java对象，属性名和字段名自动匹配
                            items.add(om.treeToValue(node, MapPointItem.class));
                        } catch (Exception e) {
                            // 单条数据解析失败，记录DEBUG日志
                            // 使用DEBUG级别避免日志过多
                            // 单条数据失败不影响其他数据的解析
                            log.debug("点位解析跳过一条无效数据", e);
                        }
                    });
                }
                // 如果值不是数组类型，跳过该字段
            });

            // 记录点位解析完成的日志，包含解析的点位数量
            log.info("✅ 点位解析完成，共 {} 条", items.size());
            return items;

        } catch (Exception e) {
            // 捕获所有可能的异常（网络异常、解析异常等）
            // 记录错误日志，包含异常堆栈信息，便于问题排查
            log.error("❌ 点位加载失败", e);

            // 返回空列表表示加载失败
            // 调用方需要检查返回列表是否为空
            return new ArrayList<>();
        }
    }

}
