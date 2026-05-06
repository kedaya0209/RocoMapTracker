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

    // ==================== 公共API方法 ====================

    public static MapConfig load() {
        try {
            // 记录开始加载的日志，便于追踪加载过程
            log.info("正在拉取地图配置...");

            // 使用Jsoup访问远程页面，获取HTML内容
            // 设置User-Agent和Referer模拟浏览器请求，避免被服务器拒绝
            // 设置超时时间为15秒，防止长时间阻塞
            Document doc = Jsoup.connect(AppConfig.MAP_RESOURCE_INFO_URL)
                    .userAgent("Mozilla/5.0")  // 模拟Chrome浏览器
                    .referrer("https://wiki.biligame.com/")  // 设置Referer
                    .timeout(15000)  // 设置超时时间为15秒
                    .get();  // 发送GET请求

            // 从HTML的所有script标签中查找包含地图配置的标签
            // 使用Stream API进行过滤，提高代码可读性
            // 过滤条件：script标签的数据内容必须包含"window.mapData = {"标记
            Element script = doc.select("script").stream()
                    .filter(e -> e.data().contains("window.mapData = {"))
                    .findFirst()  // 只取第一个匹配的script标签
                    .orElse(null);  // 如果没有匹配的标签，返回null

            // 检查是否找到了配置脚本
            if (script == null) {
                // 未找到配置脚本，记录警告日志并返回null
                // 这种情况可能是页面结构变化或配置脚本被移除
                log.warn("⚠️ 未找到地图配置脚本");
                return null;
            }

            // 记录配置拉取成功的日志
            log.info("✅ 地图配置拉取成功，开始解析");

            // 使用JsMapConfigParser解析JavaScript配置对象
            // 解析器会将JavaScript对象语法转换为MapConfig实体对象
            // 支持复杂的嵌套对象、数组、字符串、数字等数据类型
            return JsMapConfigParser.parse(script.data());

        } catch (Exception e) {
            // 捕获所有可能的异常（网络异常、解析异常等）
            // 记录错误日志，包含异常堆栈信息，便于问题排查
            log.error("❌ 地图配置加载失败", e);

            // 返回null表示加载失败
            // 调用方需要检查返回值是否为null
            return null;
        }
    }
}
