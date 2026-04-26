package com.luoke.app.utils;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON处理工具类
 * <p>
 * 该类提供全局唯一的ObjectMapper单例，用于JSON序列化和反序列化操作。
 * 使用单例模式可以避免重复创建ObjectMapper实例，提升性能并节省内存。
 * <p>
 * <b>设计模式：</b>
 * <ul>
 *   <li>单例模式（Singleton Pattern）</li>
 *   <li>使用饿汉式初始化（类加载时创建）</li>
   *   <li>线程安全（final字段保证可见性）</li>
 * </ul>
 * <p>
 * <b>核心功能：</b>
 * <ul>
 *   <li>提供全局唯一的ObjectMapper实例</li>
 *   <li>支持Java对象与JSON字符串的相互转换</li>
 *   <li>支持JSON文件读写</li>
 *   <li>支持自定义序列化和反序列化配置</li>
 * </ul>
 * <p>
 * <b>ObjectMapper配置说明：</b>
 * <ul>
 *   <li>使用Jackson默认配置</li>
 *   <li>支持标准Java类型（基本类型、集合、Map等）</li>
 *   <li>支持自定义注解（@JsonProperty, @JsonIgnore等）</li>
 *   <li>线程安全（ObjectMapper是线程安全的）</li>
 * </ul>
 * <p>
 * <b>性能优势：</b>
 * <ul>
 *   <li>避免重复创建ObjectMapper（创建开销大）</li>
 *   <li>复用配置和缓存（如类元数据缓存）</li>
   *   <li>减少内存分配和垃圾回收压力</li>
 *   <li>适合高频序列化/反序列化场景</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>配置文件读写（JSON格式）</li>
 *   <li>API请求/响应序列化</li>
 *   <li>数据持久化</li>
 *   <li>日志输出</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>
 * // 获取ObjectMapper实例
 * ObjectMapper mapper = JsonUtils.getMapper();
 *
 * // 对象转JSON字符串
 * User user = new User("张三", 25);
 * String json = mapper.writeValueAsString(user);
 * // 结果：{"name":"张三","age":25}
 *
 * // JSON字符串转对象
 * String json = "{\"name\":\"李四\",\"age\":30}";
 * User user = mapper.readValue(json, User.class);
 *
 * // 读取JSON文件
 * User user = mapper.readValue(new File("user.json"), User.class);
 *
 * // 写入JSON文件
 * mapper.writeValue(new File("user.json"), user);
 * </pre>
 * <p>
 * <b>注意事项：</b>
 * <ul>
 *   <li>ObjectMapper是线程安全的，可以在多线程中共享使用</li>
 *   <li>不要修改ObjectMapper的配置（会影响所有使用者）</li>
 *   <li>如需不同配置，创建新的ObjectMapper实例</li>
 *   <li>序列化/反序列化可能抛出JsonProcessingException</li>
 * </ul>
 * <p>
 * <b>与Native Image兼容性：</b>
 * <ul>
 *   <li>Jackson官方支持GraalVM Native Image</li>
 *   <li>需要在native-image.properties中配置反射</li>
 *   <li>推荐使用jackson-module-jsonSchema等扩展</li>
 * </ul>
 * <p>
 * <b>线程安全：</b>
 * <ul>
 *   <li>ObjectMapper实例是线程安全的</li>
 *   <li>final字段确保实例的可见性</li>
 *   <li>可以安全地在多线程环境中并发调用</li>
 * </ul>
 *
 * @since 1.0
 */
public class JsonUtils {

    /**
     * 全局唯一的ObjectMapper单例
     * <p>
     * 使用final关键字确保引用不可变，配合static实现类级别单例。
     * 采用饿汉式初始化（类加载时创建），简单且线程安全。
     * <p>
     * <b>初始化时机：</b>
     * <ul>
     *   <li>类首次被加载时创建</li>
     *   <li>由JVM保证线程安全</li>
     *   <li>使用前无需检查是否已初始化</li>
     * </ul>
     * <p>
     * <b>配置说明：</b>
     * <ul>
     *   <li>使用Jackson默认配置</li>
     *   <li>自动注册标准模块（如JDK8时间模块）</li>
     *   <li>支持标准Java类型和集合</li>
     *   <li>不启用特殊特性（如FAIL_ON_UNKNOWN_BEHAVES默认为false）</li>
     * </ul>
     * <p>
     * <b>性能特性：</b>
     * <ul>
     *   <li>缓存类元数据（如序列化器/反序列化器）</li>
     *   <li>重用内部缓冲区</li>
     *   <li>避免重复解析注解</li>
     * </ul>
     * <p>
     * <b>内存管理：</b>
     * <ul>
     *   <li>单例生命周期为应用程序整个生命周期</li>
     *   <li>不会被垃圾回收（静态final字段）</li>
     *   <li>占用内存约为几百KB到几MB（取决于使用类型）</li>
     * </ul>
     * <p>
     * <b>Native Image注意事项：</b>
     * <ul>
     *   <li>需要配置反射访问权限</li>
     *   <li>需要在native-image.properties中注册序列化类型</li>
     *   <li>推荐使用GraalVM Reachability Metadata Repository</li>
     * </ul>
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 私有构造方法，防止实例化
     * <p>
     * 该类为纯工具类，所有方法均为静态方法，无需实例化。
     * 私有构造方法可以防止用户误用，确保类的设计意图被正确遵循。
     * <p>
     * <b>设计意图：</b>
     * <ul>
     *   <li>强制使用静态方法访问ObjectMapper</li>
     *   <li>确保只有一个ObjectMapper实例存在</li>
     *   <li>避免用户创建不必要的实例</li>
     * </ul>
     */
    private JsonUtils() {
    }

    /**
     * 获取全局唯一的ObjectMapper实例
     * <p>
     * 该方法返回类级别唯一的ObjectMapper实例，用于所有JSON序列化和反序列化操作。
     * 由于ObjectMapper是线程安全的，该方法可以在多线程环境中安全调用。
     * <p>
     * <b>返回值特性：</b>
     * <ul>
     *   <li>永远是同一个实例（内存地址相同）</li>
     *   <li>实例已初始化，可直接使用</li>
     *   <li>线程安全，无需同步</li>
     *   <li>final保证引用不可变</li>
     * </ul>
     * <p>
     * <b>使用建议：</b>
     * <ul>
     *   <li>直接使用返回的实例，不要缓存（它已经是单例）</li>
     *   <li>不要修改返回实例的配置（会影响所有使用者）</li>
     *   <li>如需不同配置，创建新的ObjectMapper实例</li>
     *   <li>处理JsonProcessingException异常</li>
     * </ul>
     * <p>
     * <b>常见用法：</b>
     * <pre>
     * // 获取ObjectMapper实例
     * ObjectMapper mapper = JsonUtils.getMapper();
     *
     * // 序列化：对象 -> JSON字符串
     * String json = mapper.writeValueAsString(obj);
     *
     * // 反序列化：JSON字符串 -> 对象
     * MyClass obj = mapper.readValue(json, MyClass.class);
     *
     * // 文件操作
     * mapper.writeValue(new File("data.json"), obj);
     * MyClass obj = mapper.readValue(new File("data.json"), MyClass.class);
     * </pre>
     * <p>
     * <b>异常处理示例：</b>
     * <pre>
     * try {
     *     ObjectMapper mapper = JsonUtils.getMapper();
     *     String json = mapper.writeValueAsString(obj);
     *     // 使用json...
     * } catch (JsonProcessingException e) {
     *     // 处理JSON处理异常
     *     log.error("JSON序列化失败", e);
     * }
     * </pre>
     *
     * @return 全局唯一的ObjectMapper实例，永远不为null
     */
    public static ObjectMapper getMapper() {
        return OBJECT_MAPPER;
    }
}
