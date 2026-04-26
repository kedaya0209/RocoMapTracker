package com.luoke.app.model;

import java.util.Objects;

/**
 * OCR识别结果数据模型
 * <p>
 * 功能说明：
 * <ul>
 *   <li>封装OCR识别出的物资名称和数量</li>
 *   <li>使用Java record实现不可变数据载体</li>
 *   <li>提供自定义equals和hashCode实现，支持集合操作</li>
 * </ul>
 * <p>
 * 设计考虑：
 * <ul>
 *   <li>不可变性：使用record自动生成final字段和只读访问器</li>
 *   <li>值语义：重写equals和hashCode，确保相同名称和数量的对象被视为相等</li>
 *   <li>线程安全：不可变对象天然线程安全，无需同步控制</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>RealOcrHook中用于存储OCR识别结果</li>
 *   <li>作为MaterialCollectionContext的输入数据</li>
 *   <li>用于帧间稳定性比对（通过equals判断）</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>name可能为null，equals中已处理null比较</li>
 *   <li>count必须为正整数，但在构造时不校验，由调用方保证</li>
 *   <li>序列化：record自动实现Serializable，支持网络传输</li>
 * </ul>
 *
 * @param name 物资名称，如"木材"、"铁矿"等，可能为null
 * @param count 物资数量，必须为非负整数
 */
public record ItemResult(String name, int count) {

    /**
     * 判断两个ItemResult对象是否相等
     * <p>
     * 相等条件：
     * <ul>
     *   <li>两个对象的name和count字段完全相同</li>
     *   <li>name使用Objects.equals进行null安全的比较</li>
     *   <li>count使用基本类型==比较</li>
     * </ul>
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>重写equals是为了支持集合操作（如List.equals）</li>
     *   <li>在RealOcrHook中用于判断两帧OCR结果是否一致</li>
     *   <li>使用Objects.equals处理null值，避免NPE</li>
     * </ul>
     *
     * @param o 要比较的对象
     * @return 如果name和count都相同则返回true，否则返回false
     */
    @Override
    public boolean equals(Object o) {
        // 快速路径：对象引用相同
        if (this == o) return true;

        // 类型检查：必须是ItemResult类
        if (o == null || getClass() != o.getClass()) return false;

        // 类型转换后进行字段比较
        ItemResult that = (ItemResult) o;

        // 先比较count（基本类型比较更快），再比较name（可能为null）
        return count == that.count && Objects.equals(name, that.name);
    }

    /**
     * 计算ItemResult对象的哈希值
     * <p>
     * 实现细节：
     * <ul>
     *   <li>使用Objects.hash组合name和count的哈希值</li>
     *   <li>保证equals为true的对象hashCode相同</li>
     *   <li>保证equals为false的对象hashCode尽量不同</li>
     * </ul>
     * <p>
     * 设计考虑：
     * <ul>
     *   <li>重写hashCode必须同时重写equals，遵守Java约定</li>
     *   <li>使用Objects.hash简化哈希组合逻辑</li>
     *   <li>良好的哈希分布可提升HashSet和HashMap的性能</li>
     * </ul>
     *
     * @return 基于name和count计算出的哈希值
     */
    @Override
    public int hashCode() {
        // Objects.hash内部使用Arrays.hashCode，保证组合哈希的一致性
        return Objects.hash(name, count);
    }
}
