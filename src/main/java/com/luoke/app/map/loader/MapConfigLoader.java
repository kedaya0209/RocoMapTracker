package com.luoke.app.map.loader;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.dto.MapConfig;
import com.luoke.app.map.parse.JsMapConfigParser;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * 地图配置加载器
 *
 * <p>该类负责从远程服务器加载地图配置数据，并将其解析为MapConfig对象。</p>
 * <p>地图配置通常包含：</p>
 * <ul>
 *   <li>地图基础信息（名称、版本、描述等）</li>
 *   <li>缩放级别配置（最小/最大缩放级别）</li>
 *   <li>图层配置（图层列表、图层属性、瓦片URL等）</li>
 *   <li>样式配置（颜色、字体、线条样式等）</li>
 *   <li>交互配置（点击行为、悬停效果等）</li>
 * </ul>
 *
 * <p>技术实现：</p>
 * <ul>
 *   <li>使用Jsoup解析HTML页面，提取嵌入的JavaScript配置</li>
 *   <li>使用JsMapConfigParser解析JavaScript对象为MapConfig对象</li>
 *   <li>配置数据嵌入在页面的script标签中，格式为"window.mapData = {...}"</li>
 * </ul>
 *
 * <p>设计特点：</p>
 * <ul>
 *   <li>采用静态工具类设计，无需实例化</li>
 *   <li>集成日志记录，便于问题排查</li>
 *   <li>使用超时机制防止长时间阻塞</li>
 *   <li>设置合理的User-Agent和Referer，模拟浏览器请求</li>
 * </ul>
 *
 * <p>错误处理：</p>
 * <ul>
 *   <li>网络请求失败时返回null，并记录错误日志</li>
 *   <li>未找到配置脚本时返回null，并记录警告日志</li>
 *   <li>解析失败时返回null，并记录错误日志</li>
 * </ul>
 *
 * <p>注意事项：</p>
 * <ul>
 *   <li>所有方法均为同步方法，建议在后台线程中调用</li>
 *   <li>网络请求超时时间为15秒</li>
 *   <li>配置脚本必须包含"window.mapData = {"标记</li>
 * </ul>
 *
 * @author 可达鸭
 * @since 1.0.0
 */
@Slf4j
public class MapConfigLoader {

    // ==================== 公共API方法 ====================

    /**
     * 从远程服务器加载地图配置
     *
     * <p>该方法执行以下操作：</p>
     * <ol>
     *   <li>使用Jsoup访问远程页面，获取HTML内容</li>
     *   <li>从HTML中提取包含地图配置的script标签</li>
     *   <li>解析script标签中的JavaScript配置对象</li>
     *   <li>将JavaScript对象转换为MapConfig实体对象</li>
     *   <li>返回解析结果</li>
     * </ol>
     *
     * <p>网络请求配置：</p>
     * <ul>
     *   <li>User-Agent: Mozilla/5.0（模拟现代浏览器）</li>
     *   <li>Referer: https://wiki.biligame.com/（模拟来自B站Wiki的请求）</li>
     *   <li>超时时间: 15秒</li>
     *   <li>请求方式: GET</li>
     * </ul>
     *
     * <p>配置数据格式：</p>
     * <ul>
     *   <li>配置数据嵌入在script标签中</li>
     *   <li>必须包含"window.mapData = {"标记</li>
     *   <li>使用JavaScript对象字面量语法</li>
     * </ul>
     *
     * <p>错误处理：</p>
     * <ul>
     *   <li>网络请求失败：返回null，记录ERROR日志</li>
     *   <li>未找到配置脚本：返回null，记录WARN日志</li>
     *   <li>解析失败：返回null，记录ERROR日志</li>
     * </ul>
     *
     * <p>使用示例：</p>
     * <pre>{@code
     * // 在后台线程中加载配置
     * new Thread(() -> {
     *     MapConfig config = MapConfigLoader.load();
     *     if (config != null) {
     *         // 处理配置数据
     *         int maxZoom = config.getMaxZoom();
     *         List<MapLayer> layers = config.getMapLayers();
     *     }
     * }).start();
     * }</pre>
     *
     * @return 地图配置对象，如果加载或解析失败则返回null
     *         返回的MapConfig对象包含完整的地图配置信息
     *
     * @see MapConfig 地图配置实体类
     * @see JsMapConfigParser JavaScript配置解析器
     */
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
