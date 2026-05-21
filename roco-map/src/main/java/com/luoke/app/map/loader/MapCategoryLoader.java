package com.luoke.app.map.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.DownloadConfig;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@ThreadSafe
public class MapCategoryLoader {

    private static final ObjectMapper om = JsonUtils.getMapper();

    public static List<MapCategoryItem> load() {
        try {
            // 记录开始加载的日志，便于追踪加载过程
            log.info("正在拉取分类数据...");

            // 使用Jsoup访问远程页面，获取HTML内容
            // 设置User-Agent和Referer模拟浏览器请求，避免被服务器拒绝
            // 设置超时时间为15秒，防止长时间阻塞
            Document doc = Jsoup.connect(DownloadConfig.MAP_RESOURCE_INFO_URL)
                    .userAgent("Mozilla/5.0")  // 模拟Chrome浏览器
                    .referrer("https://wiki.biligame.com/")  // 设置Referer
                    .timeout(15000)  // 设置超时时间为15秒
                    .get();  // 发送GET请求

            // 从HTML中查找id为"categoryData"的pre标签
            // 这个pre标签包含了分类数据的JSON文本
            Element pre = doc.getElementById("categoryData");
            if (pre == null) {
                // 未找到分类数据，记录警告日志并返回空列表
                // 这种情况可能是页面结构变化或数据未加载
                log.warn("⚠️ 未找到分类数据");
                return new ArrayList<>();
            }

            // 记录分类拉取成功的日志
            log.info("✅ 分类拉取成功，开始解析");

            // 解析JSON文本为JsonNode对象
            JsonNode root = om.readTree(pre.text());

            // 提取"data"字段，该字段包含了分类数据的数组
            JsonNode dataArray = root.get("data");

            // 初始化结果列表，用于存储解析后的分类数据
            List<MapCategoryItem> list = new ArrayList<>();

            // 检查data字段是否为数组类型
            if (dataArray.isArray()) {
                // 遍历数组中的每个元素
                dataArray.forEach(node -> {
                    try {
                        // 将JsonNode转换为MapCategoryItem对象
                        // treeToValue方法使用Jackson的自动映射功能
                        // 将JSON对象转换为Java对象，属性名和字段名自动匹配
                        list.add(om.treeToValue(node, MapCategoryItem.class));
                    } catch (JsonProcessingException e) {
                        // 单条数据解析失败，记录DEBUG日志
                        // 使用DEBUG级别避免日志过多
                        // 单条数据失败不影响其他数据的解析
                        log.debug("分类解析跳过一条无效数据");
                    }
                });
            }
            // 如果data字段不是数组类型，list保持为空列表

            // 记录分类解析完成的日志，包含解析的分类数量
            log.info("✅ 分类解析完成，共 {} 条", list.size());
            return list;

        } catch (IOException e) {
            // 捕获所有可能的异常（网络异常、解析异常等）
            // 记录错误日志，包含异常堆栈信息，便于问题排查
            log.error("❌ 分类加载失败", e);

            // 返回空列表表示加载失败
            // 调用方需要检查返回列表是否为空
            return new ArrayList<>();
        }
    }

}
