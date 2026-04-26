package com.luoke.app.map.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * 地图分类数据加载器
 *
 * <p>该类负责从远程服务器加载地图分类数据，并将其解析为MapCategoryItem对象列表。</p>
 * <p>分类数据通常用于：</p>
 * <ul>
 *   <li>对点位进行分组和分类管理</li>
 *   <li>控制图层的显示和隐藏</li>
 *   <li>实现图层过滤和搜索功能</li>
 *   <li>定义图层的样式和展示属性</li>
 *   <li>组织和管理地图元素</li>
 * </ul>
 *
 * <p>技术实现：</p>
 * <ul>
 *   <li>使用Jsoup解析HTML页面，提取嵌入的JSON数据</li>
 *   <li>使用Jackson的ObjectMapper解析JSON数据</li>
 *   <li>数据通常包含在id为"categoryData"的pre标签中</li>
 * </ul>
 *
 * <p>数据结构：</p>
 * <ul>
 *   <li>JSON对象，包含"data"字段</li>
 *   <li>"data"字段是一个数组，包含所有分类信息</li>
 *   <li>每个分类包含名称、图标、颜色、显示状态等属性</li>
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
 *   <li>数据必须包含在id为"categoryData"的pre标签中</li>
 * </ul>
 *
 * @author 可达鸭
 * @since 1.0.0
 */
@Slf4j
public class MapCategoryLoader {

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
     * 从远程服务器加载地图分类数据
     *
     * <p>该方法执行以下操作：</p>
     * <ol>
     *   <li>使用Jsoup访问远程页面，获取HTML内容</li>
     *   <li>从HTML中提取包含分类数据的pre标签（id为"categoryData"）</li>
     *   <li>解析JSON数据，提取"data"字段</li>
     *   <li>将"data"数组中的每个元素转换为MapCategoryItem对象</li>
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
     * <p>数据结构格式：</p>
     * <pre>{@code
     * {
     *   "data": [
     *     {
     *       "id": "1",
     *       "name": "分类名称",
     *       "icon": "icon.png",
     *       "color": "#FF0000",
     *       "visible": true
     *     },
     *     ...
     *   ]
     * }
     * }</pre>
     *
     * <p>错误处理：</p>
     * <ul>
     *   <li>网络请求失败：返回空列表，记录ERROR日志</li>
     *   <li>未找到分类数据：返回空列表，记录WARN日志</li>
     *   <li>data字段不是数组类型：返回空列表</li>
     *   <li>单条数据解析失败：跳过该数据，记录DEBUG日志</li>
     * </ul>
     *
     * <p>使用示例：</p>
     * <pre>{@code
     * // 在后台线程中加载分类数据
     * new Thread(() -> {
     *     List<MapCategoryItem> categories = MapCategoryLoader.load();
     *     if (!categories.isEmpty()) {
     *         // 处理分类数据
     *         for (MapCategoryItem category : categories) {
     *             String name = category.getName();
     *             String icon = category.getIcon();
     *             boolean visible = category.isVisible();
     *             // 使用分类信息...
     *         }
     *     }
     * }).start();
     * }</pre>
     *
     * @return 地图分类数据列表，如果加载失败则返回空列表
     *         每个MapCategoryItem对象代表一个图层分类或区域分类
     *
     * @see MapCategoryItem 分类数据实体类
     */
    public static List<MapCategoryItem> load() {
        try {
            // 记录开始加载的日志，便于追踪加载过程
            log.info("正在拉取分类数据...");

            // 使用Jsoup访问远程页面，获取HTML内容
            // 设置User-Agent和Referer模拟浏览器请求，避免被服务器拒绝
            // 设置超时时间为15秒，防止长时间阻塞
            Document doc = Jsoup.connect(AppConfig.MAP_RESOURCE_INFO_URL)
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
            var root = om.readTree(pre.text());

            // 提取"data"字段，该字段包含了分类数据的数组
            var dataArray = root.get("data");

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
                    } catch (Exception e) {
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

        } catch (Exception e) {
            // 捕获所有可能的异常（网络异常、解析异常等）
            // 记录错误日志，包含异常堆栈信息，便于问题排查
            log.error("❌ 分类加载失败", e);

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
     *   <li>支持增量更新：只获取变更的分类数据</li>
     *   <li>支持分类层级：处理多级嵌套分类结构</li>
     *   <li>支持分类排序：按照特定规则对分类进行排序</li>
     *   <li>支持分类合并：将多个分类数据源合并到一起</li>
     *   <li>支持分类过滤：根据条件过滤分类数据</li>
     *   <li>支持本地缓存：减少网络请求，提高加载速度</li>
     * </ul>
     */
}
