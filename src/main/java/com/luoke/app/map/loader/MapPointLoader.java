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

/**
 * 地图点位数据加载器
 *
 * <p>该类负责从远程服务器加载地图点位数据，并将其解析为MapPointItem对象列表。</p>
 * <p>点位数据通常包含：</p>
 * <ul>
 *   <li>点位坐标（经度、纬度）</li>
 *   <li>点位名称和描述</li>
 *   <li>点位分类和类型</li>
 *   <li>关联的图标资源路径</li>
 *   <li>显示和交互属性</li>
 * </ul>
 *
 * <p>技术实现：</p>
 * <ul>
 *   <li>使用Jsoup解析HTML页面，提取嵌入的JSON数据</li>
 *   <li>使用Jackson的ObjectMapper解析JSON数据</li>
 *   <li>支持数据清洗和格式转换</li>
 * </ul>
 *
 * <p>数据格式处理：</p>
 * <ul>
 *   <li>原始数据可能包含特殊的B站Wiki语法（如:Data:）</li>
 *   <li>数字键名会被转换为字符串键名（例如：1 -> "1"）</li>
 *   <li>无效数据会被自动跳过，不影响其他数据的解析</li>
 * </ul>
 *
 * <p>设计特点：</p>
 * <ul>
 *   <li>采用静态工具类设计，无需实例化</li>
 *   <li>集成日志记录，便于问题排查</li>
 *   <li>使用超时机制防止长时间阻塞</li>
 *   <li>设置合理的User-Agent和Referer，模拟浏览器请求</li>
 *   <li>容错性强，单条数据解析失败不影响整体</li>
 * </ul>
 *
 * <p>错误处理：</p>
 * <ul>
 *   <li>网络请求失败时返回空列表，并记录错误日志</li>
 *   <li>未找到数据时返回空列表，并记录警告日志</li>
 *   <li>单条数据解析失败时跳过该数据，记录DEBUG日志</li>
 * </ul>
 *
 * <p>注意事项：</p>
 * <ul>
 *   <li>所有方法均为同步方法，建议在后台线程中调用</li>
 *   <li>网络请求超时时间为15秒</li>
 *   <li>数据必须包含在id为"mapPointData"的pre标签中</li>
 * </ul>
 *
 * @author 可达鸭
 * @since 1.0.0
 */
@Slf4j
public class MapPointLoader {

    // ==================== 实例变量 ====================

    /**
     * JSON对象映射器，用于JSON数据的序列化和反序列化
     *
     * <p>使用JsonUtils工具类获取全局共享的ObjectMapper实例，</p>
     * <p>避免重复创建对象，提高性能。</p>
     *
     * <p>ObjectMapper配置：</p>
     * <ul>
     *   <li>支持JSON到Java对象的转换</li>
     *   <li>支持复杂类型的自动处理</li>
     *   <li>配置了适当的序列化和反序列化特性</li>
     * </ul>
     */
    private static final ObjectMapper om = JsonUtils.getMapper();

    // ==================== 公共API方法 ====================

    /**
     * 从远程服务器加载地图点位数据
     *
     * <p>该方法执行以下操作：</p>
     * <ol>
     *   <li>使用Jsoup访问远程页面，获取HTML内容</li>
     *   <li>从HTML中提取包含点位数据的pre标签（id为"mapPointData"）</li>
     *   <li>清洗数据格式（移除特殊语法、转换键名格式）</li>
     *   <li>解析JSON数据为MapPointItem对象列表</li>
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
     * <p>数据清洗过程：</p>
     * <ul>
     *   <li>移除B站Wiki的特殊语法：:Data:.{0,30}?/json -> :[]</li>
     *   <li>转换数字键名为字符串键名：(\\d+) -> "$1"</li>
     *   <li>这些操作是为了将非标准JSON转换为标准JSON格式</li>
     * </ul>
     *
     * <p>数据结构：</p>
     * <ul>
     *   <li>外层是一个对象，键名为层级或分类标识</li>
     *   <li>每个键对应的值是一个数组</li>
     *   <li>数组中的每个元素代表一个点位信息</li>
     * </ul>
     *
     * <p>错误处理：</p>
     * <ul>
     *   <li>网络请求失败：返回空列表，记录ERROR日志</li>
     *   <li>未找到点位数据：返回空列表，记录WARN日志</li>
     *   <li>单条数据解析失败：跳过该数据，记录DEBUG日志</li>
     * </ul>
     *
     * <p>使用示例：</p>
     * <pre>{@code
     * // 在后台线程中加载点位数据
     * new Thread(() -> {
     *     List<MapPointItem> points = MapPointLoader.load();
     *     if (!points.isEmpty()) {
     *         // 处理点位数据
     *         for (MapPointItem point : points) {
     *             String name = point.getName();
     *             double lat = point.getLat();
     *             double lng = point.getLng();
     *             // 使用点位信息...
     *         }
     *     }
     * }).start();
     * }</pre>
     *
     * @return 地图点位数据列表，如果加载失败则返回空列表
     *         每个MapPointItem对象代表地图上的一个兴趣点或标记点
     *
     * @see MapPointItem 点位数据实体类
     */
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
    // ==================== 未来的扩展可能 ====================

    /**
     * 未来可以考虑添加的功能：
     *
     * <ul>
     *   <li>支持增量更新：只获取变更的点位数据</li>
     *   <li>支持分页加载：大数据量场景下的分页处理</li>
     *   <li>支持本地缓存：减少网络请求，提高加载速度</li>
     *   <li>支持数据校验：对点位数据进行格式和范围校验</li>
     *   <li>支持数据去重：处理重复的点位数据</li>
     * </ul>
     */
}
