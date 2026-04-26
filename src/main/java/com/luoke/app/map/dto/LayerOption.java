package com.luoke.app.map.dto;

import lombok.Data;

/**
 * 地图图层选项配置类
 *
 * <p>该类封装了地图图层的主要配置选项，目前主要关注瓦片服务的URL配置。
 * 使用Lombok的@Data注解自动生成getter/setter方法，简化代码编写。
 * 提供了带参数的构造方法，支持对象初始化时的便捷设置。</p>
 *
 * <p>该类主要用于：
 * <ul>
 *   <li>配置瓦片地图的数据源URL</li>
 *   <li>支持地图瓦片的标准XYZ瓦片格式</li>
 *   <li>为图层提供可扩展的配置基础</li>
 * </ul></p>
 *
 * <p>设计考虑：
 * <ul>
 *   <li>当前版本专注于瓦片URL配置，保持了类的简洁性</li>
 *   <li>提供构造方法支持，便于对象创建和依赖注入</li>
 *   <li>未来可以扩展添加更多图层选项（如透明度、最小/最大缩放级别等）</li>
 * </ul></p>
 *
 * <p>在Native Image环境下：
 * <ul>
 *   <li>字符串字段需要正确处理序列化</li>
 *   <li>URL字符串在编译时已确定，可以利用字符串池优化</li>
 *   <li>避免使用复杂的URL解析对象，保持轻量级</li>
 * </ul></p>
 *
 * <p>性能优化：
 * <ul>
 *   <li>瓦片URL使用字符串模板，减少运行时的字符串拼接开销</li>
 *   <li>构造方法直接赋值，避免中间对象的创建</li>
 *   <li>final字段可以进一步优化，但需要Lombok支持</li>
 * </ul></p>
 *
 * @author RocoMapTracker
 * @version 1.0
 * @since 2024
 */
@Data
public class LayerOption {
    /**
     * 地图瓦片服务的URL模板
     *
     * <p>该URL定义了地图瓦片的加载地址，支持标准的XYZ瓦片格式。
     * URL中包含占位符，地图引擎会根据当前视口和缩放级别动态替换这些占位符。</p>
     *
     * <p>支持的占位符格式：
     * <ul>
     *   <li>{z} - 缩放级别（Zoom level），通常从0到18</li>
     *   <li>{x} - 瓦片的X坐标（列号），从左到右递增</li>
     *   <li>{y} - 瓦片的Y坐标（行号），从上到下递增</li>
     * </ul></p>
     *
     * <p>URL示例：
     * <pre>
     * 标准OSM瓦片：https://{a-c}.tile.openstreetmap.org/{z}/{x}/{y}.png
     * 阿里云瓦片：https://webrd01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}
     * 高德瓦片：https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}
     * </pre></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用字符串模板而非对象配置，减少序列化复杂度</li>
     *   <li>支持自定义瓦片服务，提高系统灵活性</li>
     *   <li>占位符机制符合Web地图标准，兼容性好</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>瓦片URL的拼接操作频繁发生，建议使用高效的字符串处理</li>
     *   <li>在Native Image中，URL模板可以被内联优化</li>
     *   <li>考虑实现URL缓存机制，避免重复解析相同的URL模式</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>tileUrl字符串在对象生命周期内保持不变</li>
     *   <li>相同的URL模板可以被多个LayerOption对象共享</li>
     *   <li>在Native Image中，字符串常量会被放入常量池</li>
     * </ul></p>
     *
     * <p>安全性：
     * <ul>
     *   <li>验证URL格式，防止注入攻击</li>
     *   <li>考虑使用HTTPS协议，确保数据传输安全</li>
     *   <li>处理URL编码，避免特殊字符导致请求失败</li>
     * </ul></p>
     *
     * <p>扩展性：
     * <ul>
     *   <li>未来可以支持更多占位符（如{quadkey}、{bbox}等）</li>
     *   <li>可以添加URL参数配置（如API密钥、样式参数等）</li>
     *   <li>考虑支持多种瓦片格式（PNG、JPG、PBF等）</li>
     * </ul></p>
     */
    private String tileUrl;

    /**
     * 构造方法 - 创建具有指定瓦片URL的图层选项
     *
     * <p>该构造方法允许在创建LayerOption对象时直接设置瓦片URL，
     * 避免了创建后通过setter方法设置的额外步骤。</p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>提供便捷的对象初始化方式</li>
     *   <li>支持依赖注入框架的自动装配</li>
     *   <li>确保tileUrl在对象创建时就被设置，避免空指针异常</li>
     *   <li>配合Lombok的@Data注解，仍然提供setter方法用于后续修改</li>
     * </ul></p>
     *
     * <p>在Native Image环境下的优化：
     * <ul>
     *   <li>构造方法调用可以被JIT优化为内联</li>
     *   <li>参数传递使用寄存器，减少栈内存使用</li>
     *   <li>构造过程简单，对象分配快速</li>
     * </ul></p>
     *
     * <p>使用示例：
     * <pre>
     * LayerOption osmLayer = new LayerOption("https://tile.openstreetmap.org/{z}/{x}/{y}.png");
     * LayerOption gaodeLayer = new LayerOption("https://webrd01.is.autonavi.com/appmaptile?style=6&x={x}&y={y}&z={z}");
     * </pre></p>
     *
     * <p>注意事项：
     * <ul>
     *   <li>传入null会导致后续使用时出现NullPointerException</li>
     *   <li>建议在调用前进行null检查或使用Optional包装</li>
     *   <li>URL格式不正确会在瓦片加载时才发现错误</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>直接赋值操作非常高效，O(1)时间复杂度</li>
     *   <li>字符串引用复制，不会创建新的字符串对象</li>
     *   <li>适合高频调用场景（如批量创建图层）</li>
     * </ul></p>
     *
     * @param tileUrl 地图瓦片服务的URL模板，必须包含{z}、{x}、{y}占位符
     *                通常格式为：https://example.com/{z}/{x}/{y}.png
     *                该参数不应为null，否则会导致后续使用时的空指针异常
     *
     * @throws NullPointerException 如果tileUrl为null（虽然编译时不会显式抛出，
     *                              但在运行时访问tileUrl时会抛出此异常）
     *
     * @see #tileUrl 瓦片URL字段的详细说明
     * @see #LayerOption() 默认构造方法（由Lombok@Data注解隐式提供）
     */
    public LayerOption(String tileUrl) {
        this.tileUrl = tileUrl; // 直接赋值，利用引用传递提高效率
    }
}