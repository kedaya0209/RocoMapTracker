package com.luoke.app.map.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 地图分类项数据传输对象
 *
 * <p>该类封装了地图标记点的分类信息，包括类型定义、显示配置、
 * 交互属性等。使用Lombok的@Data注解自动生成getter/setter方法，
 * 使用@JsonIgnoreProperties注解确保JSON反序列化时忽略未知字段。</p>
 *
 * <p>该类主要用于：
 * <ul>
 *   <li>定义标记点类型的元数据（图标、名称、描述等）</li>
 *   <li>控制不同类型标记点的显示和交互行为</li>
 *   <li>关联标记点类型与UI渲染样式</li>
 *   <li>支持分类的扩展属性（如GeoJSON、自定义类等）</li>
 * </ul></p>
 *
 * <p>在地图系统中，MapCategoryItem相当于标记点的"类型定义"，
 * MapPointItem中的markType字段引用此对象的markType值，形成分类关联。</p>
 *
 * <p>Native Image环境下的考虑：
 * <ul>
 *   <li>大量String字段在Native Image中需要正确处理序列化</li>
   *   <li>使用@JsonIgnoreProperties提高反序列化的健壮性</li>
 *   <li>字符串字段支持null值，提供灵活性</li>
 *   <li>整体设计轻量化，适合大量分类配置</li>
 * </ul></p>
 *
 * <p>性能优化策略：
 * <ul>
 *   <li>分类配置通常在应用启动时加载，使用缓存提高访问速度</li>
 *   <li>字符串长度保持合理，避免占用过多内存</li>
 *   <li>考虑使用枚举或常量替代部分字符串，提高性能</li>
 * </ul></p>
 *
 * @author RocoMapTracker
 * @version 1.0
 * @since 2024
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapCategoryItem {
    /**
     * 分类的类型标识
     *
     * <p>标识分类的类型，用于区分不同性质的分类（如点标记、线标记、面标记等）。
     * 该字段决定了分类的基本性质和渲染方式。</p>
     *
     * <p>常见类型值：
     * <ul>
     *   <li>"point" - 点标记分类</li>
     *   <li>"line" - 线标记分类</li>
     *   <li>"polygon" - 面标记分类</li>
     *   <li>"marker" - 自定义标记分类</li>
     *   <li>"overlay" - 叠加层分类</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，便于扩展新的分类类型</li>
     *   <li>支持null值，表示"未定义类型"</li>
     *   <li>类型驱动渲染逻辑（点、线、面使用不同的渲染器）</li>
     *   <li>与markType配合，形成二级分类体系</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>类型判断使用字符串比较，可以考虑使用枚举优化</li>
     *   <li>在Native Image中，类型字符串可以被内联优化</li>
     *   <li>频繁的类型检查可以考虑使用if-else或switch优化</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>类型字符串在对象生命周期内存在</li>
     *   <li>相同类型的分类可以共享字符串对象</li>
     *   <li>字符串使用UTF-8编码，支持国际化</li>
     * </ul></p>
     */
    private String type;

    /**
     * 标记类型的数值标识
     *
     * <p>标记点的分类标识码，MapPointItem.markType字段引用此值。
     * 该字段是连接分类项与标记点的关键，形成分类体系。</p>
     *
     * <p>设计规范：
     * <ul>
     *   <li>必须全局唯一，不同分类使用不同的markType值</li>
     *   <li>建议使用正整数（1, 2, 3...），便于理解和调试</li>
     *   <li>预留一些范围给系统分类（如1-100）和用户自定义分类（101+）</li>
     *   <li>0或null可以保留用于"默认分类"或"未分类"</li>
     * </ul></p>
     *
     * <p>使用场景：
     * <ul>
     *   <li>标记点分类：MapPointItem.markType引用此值</li>
     *   <li>图标映射：根据markType选择对应的图标</li>
     *   <li>样式应用：根据markType应用不同的样式</li>
     *   <li>筛选过滤：按markType筛选标记点</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用Integer而非基本类型int，支持null表示"无效分类"</li>
     *   <li>数值类型比较高效，适合作为数组索引或Map键</li>
     *   <li>与MapPointItem.markType建立关联，形成类型系统</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>markType查找使用HashMap，O(1)时间复杂度</li>
     *   <li>在Native Image中，Integer对象分配可以被优化</li>
     *   <li>大量分类时，可以考虑使用int而非Integer</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>Integer是包装类，会占用额外内存</li>
     *   <li>对于少量分类，内存开销可以接受</li>
     *   <li>相同markType值的对象可以共享Integer实例</li>
     * </ul></p>
     */
    private Integer markType;

    /**
     * 分类的长度或尺寸信息
     *
     * <p>定义分类相关的长度、尺寸或范围信息。
     * 具体含义根据分类类型（type）不同而不同。</p>
     *
     * <p>可能的含义：
     * <ul>
     *   <li>对于点标记：图标的大小（如"32px"）</li>
     *   <li>对于线标记：线条的宽度（如"2px"）</li>
     *   <li>对于面标记：边框的宽度（如"1px"）</li>
     *   <li>对于区域：区域的半径或范围（如"100m"）</li>
     * </ul></p>
     *
     * <p>格式示例：
     * <pre>
     * "32px" - 像素单位
     * "100m" - 米单位
     * "1.5em" - 相对单位
     * "auto" - 自动计算
     * </pre></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，灵活支持各种单位和格式</li>
     *   <li>支持null值，表示"使用默认长度"</li>
     *   <li>长度信息影响UI渲染，需要正确解析</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>长度解析在渲染时进行，考虑缓存解析结果</li>
     *   <li>在Native Image中，长度字符串可以被内联优化</li>
     *   <li>频繁渲染时，考虑预解析为数值类型</li>
     * </ul></p>
     */
    private String length;

    /**
     * 标记类型的显示名称
     *
     * <p>分类的用户友好名称，显示在UI界面上。
     * 该名称用于图例、筛选列表、详情显示等场景。</p>
     *
     * <p>UI显示：
     * <ul>
     *   <li>图例显示：地图图例中显示分类名称</li>
     *   <li>筛选列表：用户筛选标记点时的分类名称</li>
     *   <li>详情显示：标记点详情中显示分类名称</li>
     *   <li>提示文字：鼠标悬停时显示的提示信息</li>
     * </ul></p>
     *
     * <p>命名规范：
     * <ul>
     *   <li>使用简短、明确的名称（2-10个字符）</li>
     *   <li>支持中文显示，面向中文用户</li>
     *   <li>避免使用缩写，提高可读性</li>
     *   <li>示例："传送点"、"宝箱"、"NPC" 等</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，支持多语言显示</li>
     *   <li>支持null值，表示"无名称"或"使用markType"</li>
     *   <li>名称影响用户体验，需要精心设计</li>
     * </ul></p>
     *
     * <p>国际化考虑：
     * <ul>
     *   <li>当前使用中文硬编码</li>
     *   <li>未来可以改为资源键，支持多语言切换</li>
     *   <li>需要考虑不同语言下名称的长度差异</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>名称字符串在对象生命周期内存在</li>
     *   <li>相同名称的分类可以共享字符串对象</li>
     *   <li>字符串长度影响内存占用，保持简洁</li>
     * </ul></p>
     */
    private String markTypeName;

    /**
     * 分类的默认显示状态
     *
     * <p>控制该分类的标记点在地图加载时是否默认显示。
     * 该字段影响用户初始看到的地图内容。</p>
     *
     * <p>取值含义：
     * <ul>
     *   <li>"true" - 默认显示该分类的标记点</li>
     *   <li>"false" - 默认隐藏该分类的标记点</li>
     *   <li>null 或 其他值 - 视为"true"（默认显示）</li>
     * </ul></p>
     *
     * <p>使用场景：
     * <ul>
     *   <li>基础分类（如底图元素）：默认显示</li>
     *   <li>详细分类（如详细POI）：默认隐藏，用户手动开启</li>
     *   <li>临时分类（如调试标记）：默认隐藏</li>
     *   <li>重要分类（如用户标记）：默认显示</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用String而非boolean，支持JSON配置的灵活性</li>
     *   <li>支持null值，表示"使用默认值（true）"</li>
     *   <li>影响初始加载性能，隐藏的标记点不加载</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>默认隐藏的分类可以延迟加载，减少初始加载时间</li>
     *   <li>在Native Image中，字符串比较可以被优化</li>
     *   <li>频繁的显示/隐藏切换需要高效的UI更新</li>
     * </ul></p>
     *
     * <p>用户体验：
     * <ul>
     *   <li>避免显示过多分类，造成地图混乱</li>
     *   <li>重要的分类默认显示，次要的分类隐藏</li>
     *   <li>用户可以手动切换显示状态</li>
     * </ul></p>
     */
    private String defaultShow;

    /**
     * 分类的CSS类名
     *
     * <p>用于应用自定义CSS样式的类名。
     * 该类名会被添加到相关DOM元素的class属性中，实现样式定制。</p>
     *
     * <p>使用场景：
     * <ul>
     *   <li>图标样式：控制标记图标的大小、颜色等</li>
     *   <li>动画效果：添加悬停、点击等动画</li>
     *   <li>主题定制：根据分类应用不同的主题</li>
     *   <li>响应式设计：根据屏幕尺寸应用不同样式</li>
     * </ul></p>
     *
     * <p>命名规范：
     * <ul>
     *   <li>使用kebab-case命名（如"marker-treasure"）</li>
     *   <li>避免使用特殊字符和空格</li>
     *   <li>语义化命名，便于理解和维护</li>
     *   <li>可以包含前缀，避免命名冲突</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，支持任意CSS类名</li>
     *   <li>支持null值，表示"无自定义样式"</li>
     *   <li>灵活的样式系统，无需修改代码即可定制</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>CSS类名查找在渲染时进行，考虑优化</li>
     *   <li>在Native Image中，类名字符串可以被内联优化</li>
     *   <li>复杂的CSS样式可能影响渲染性能</li>
     * </ul></p>
     *
     * <p>安全性：
     * <ul>
     *   <li>避免使用用户输入的类名，防止XSS攻击</li>
     *   <li>类名应该从可信的配置源加载</li>
     *   <li>考虑对类名进行验证和过滤</li>
     * </ul></p>
     */
    private String clazz;

    /**
     * 分类是否可收藏
     *
     * <p>标识该分类的标记点是否允许用户收藏。
     * 收藏功能用于用户保存感兴趣的标记点。</p>
     *
     * <p>取值含义：
     * <ul>
     *   <li>"true" - 允许收藏该类标记点</li>
     *   <li>"false" - 不允许收藏该类标记点</li>
     *   <li>null 或 其他值 - 视为"false"（不可收藏）</li>
     * </ul></p>
     *
     * <p>使用场景：
     * <ul>
     *   <li>重要标记（如宝箱、传送点）：允许收藏</li>
     *   <li>临时标记（如调试点）：不允许收藏</li>
     *   <li>系统标记（如装饰元素）：不允许收藏</li>
     *   <li>用户自定义标记：允许收藏</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用String而非boolean，支持JSON配置的灵活性</li>
     *   <li>支持null值，表示"使用默认值（false）"</li>
     *   <li>收藏功能影响数据库设计和API设计</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>收藏检查在用户交互时进行，影响较小</li>
     *   <li>在Native Image中，字符串比较可以被优化</li>
     *   <li>大量收藏数据需要考虑索引优化</li>
     * </ul></p>
     *
     * <p>用户体验：
     * <ul>
     *   <li>可收藏的标记显示收藏按钮</li>
     *   <li>收藏操作应该有明确的反馈</li>
     *   <li>收藏列表便于用户快速访问</li>
     * </ul></p>
     */
    private String collectible;

    /**
     * 分类的GeoJSON数据
     *
     * <p>包含与分类相关的地理空间数据，采用GeoJSON格式。
     * GeoJSON是一种基于JSON的地理数据格式，支持点、线、面等几何类型。</p>
     *
     * <p>GeoJSON格式示例：
     * <pre>
     * {
     *   "type": "Feature",
     *   "geometry": {
     *     "type": "Point",
     *     "coordinates": [125.6, 10.1]
     *   },
     *   "properties": {
     *     "name": "Dinagat Islands"
     *   }
     * }
     * </pre></p>
     *
     * <p>使用场景：
     * <ul>
     *   <li>区域定义：定义分类的有效区域范围</li>
     *   <li>形状定制：自定义标记的形状和边界</li>
     *   <li>复杂区域：定义多边形区域、复杂边界等</li>
     *   <li>叠加层：在地图上叠加自定义区域</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，灵活存储各种GeoJSON数据</li>
     *   <li>支持null值，表示"无自定义GeoJSON"</li>
     *   <li>GeoJSON是标准的地理数据格式，兼容性好</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>GeoJSON解析在渲染时进行，考虑预解析优化</li>
     *   <li>复杂的GeoJSON可能影响渲染性能</li>
     *   <li>在Native Image中，JSON解析需要正确配置</li>
     *   <li>大块GeoJSON数据可以考虑分片加载</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>GeoJSON字符串可能很长，占用较多内存</li>
     *   <li>考虑在解析后释放原始JSON字符串</li>
     *   <li>相同的GeoJSON可以共享字符串对象</li>
     * </ul></p>
     */
    private String geojson;

    /**
     * 分类的图标URL或路径
     *
     * <p>定义分类标记点在地图上显示的图标。
     * 可以是网络URL或本地文件路径，支持各种图片格式。</p>
     *
     * <p>支持的格式：
     * <ul>
     *   <li>PNG格式：支持透明背景，适合标记图标</li>
     *   <li>JPG格式：文件较小，适合照片类标记</li>
     *   <li>SVG格式：矢量图标，可缩放，文件小</li>
     *   <li>Base64编码：data:image/png;base64,...</li>
     * </ul></p>
     *
     * <p>URL示例：
     * <pre>
     * 网络URL：https://example.com/icons/marker.png
     * 本地路径：/static/icons/treasure.svg
     * Base64：data:image/svg+xml;base64,PHN2Zy...
     * </pre></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，灵活支持各种图标来源</li>
     *   <li>支持null值，表示"使用默认图标"</li>
     *   <li>图标是分类的核心视觉元素，需要精心设计</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>图标加载影响渲染速度，选择合适的格式和大小</li>
     *   <li>SVG图标渲染开销较大，PNG更适合高频渲染</li>
     *   <li>在Native Image中，本地资源路径需要正确处理</li>
     *   <li>考虑实现图标缓存，避免重复加载</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>图标在加载后占用内存，注意内存泄漏</li>
     *   <li>Base64编码的图标占用更多内存</li>
     *   <li>相同的图标可以共享图片对象</li>
     *   <li>不可见分类的图标可以延迟加载或卸载</li>
     * </ul></p>
     *
     * <p>Native资源管理：
     * <ul>
     *   <li>本地资源在Native Image中需要打包到可执行文件</li>
     *   <li>网络资源需要处理跨域和加载超时</li>
     *   <li>图标渲染使用Canvas或SVG引擎，注意资源释放</li>
     * </ul></p>
     */
    private String icon;

    /**
     * 分类的描述信息
     *
     * <p>提供分类的详细描述和说明文字。
     * 用于帮助文档、提示信息、详情显示等场景。</p>
     *
     * <p>内容要求：
     * <ul>
     *   <li>简洁明了，避免冗长的描述</li>
     *   <li>支持中文，面向中文用户</li>
     *   <li>可以包含换行和格式，但保持简单</li>
     *   <li>描述分类的用途、特点和使用建议</li>
     * </ul></p>
     *
     * <p>使用场景：
     * <ul>
     *   <li>图例说明：地图图例中显示分类描述</li>
     *   <li>帮助文档：分类的使用说明和示例</li>
     *   <li>详情显示：标记点详情中显示分类描述</li>
     *   <li>提示信息：鼠标悬停时显示补充信息</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串类型，灵活支持各种描述内容</li>
     *   <li>支持null值，表示"无描述"</li>
     *   <li>描述帮助用户理解分类，提高可用性</li>
     * </ul></p>
     *
     * <p>国际化考虑：
     * <ul>
     *   <li>当前使用中文硬编码</li>
     *   <li>未来可以改为资源键，支持多语言切换</li>
     *   <li>不同语言的描述长度可能不同</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>描述字符串在对象生命周期内存在</li>
     *   <li>描述可能较长，注意内存占用</li>
     *   <li>相同描述的分类可以共享字符串对象</li>
     * </ul></p>
     */
    private String desc;
}