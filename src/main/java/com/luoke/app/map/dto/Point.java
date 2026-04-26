package com.luoke.app.map.dto;

import lombok.Data;

/**
 * 地理坐标点类
 *
 * <p>该类封装了地理位置的坐标信息，使用纬度（lat）和经度（lng）表示。
 * 使用Lombok的@Data注解自动生成getter/setter方法，简化代码编写。</p>
 *
 * <p>该类主要用于：
 * <ul>
 *   <li>表示地图上的地理位置点</li>
 *   <li>作为标记点的位置属性（MapPointItem.point）</li>
 *   <li>支持坐标计算（距离、方向等）</li>
 *   <li>与GeoJSON格式兼容（GeoJSON使用[lng, lat]顺序）</li>
 * </ul></p>
 *
 * <p>坐标系统：
 * <ul>
 *   <li>使用WGS84坐标系（EPSG:4326）</li>
 *   <li>纬度（lat）：-90.0 到 90.0，北纬为正，南纬为负</li>
 *   <li>经度（lng）：-180.0 到 180.0，东经为正，西经为负</li>
 *   <li>精度：通常使用小数点后6位（约0.1米精度）</li>
 * </ul></p>
 *
 * <p>设计考虑：
 * <ul>
 *   <li>使用包装类（Double）而非基本类型（double），支持null值表示"无效坐标"</li>
 *   <li>保持类结构简单，便于序列化和网络传输</li>
 *   <li>字段命名遵循地理学惯例：lat（latitude，纬度）和lng（longitude，经度）</li>
 * </ul></p>
 *
 * <p>在Native Image环境下的考虑：
 * <ul>
 *   <li>Double对象分配在Native Image中可以被优化</li>
 *   <li>坐标计算在热路径中需要高性能实现</li>
 *   <li>考虑使用两个基本类型double字段替代Double对象，减少内存开销</li>
 *   <li>序列化/反序列化需要正确处理null值</li>
 * </ul></p>
 *
 * <p>性能优化策略：
 * <ul>
 *   <li>对于大量坐标点，考虑使用紧凑的存储格式（如字节数组）</li>
 *   <li>坐标计算使用缓存结果，避免重复计算</li>
 *   <li>考虑使用对象池减少GC压力</li>
 * </ul></p>
 *
 * <p>内存管理：
 * <ul>
 *   <li>Double是包装类，每个Double对象占用额外内存（对象头+double值）</li>
 *   <li>大量Point对象会占用显著内存，需要注意内存使用</li>
 *   <li>null值可以表示"无效坐标"，但需要在使用时检查</li>
 *   <li>考虑使用基本类型double和特殊值（如NaN、Infinity）表示无效坐标</li>
 * </ul></p>
 *
 * @author RocoMapTracker
 * @version 1.0
 * @since 2024
 */
@Data
public class Point {
    /**
     * 纬度（Latitude）
     *
     * <p>表示点在地球上的南北位置，从赤道（0度）向北或向南向南测量的角度。</p>
     *
     * <p>纬度范围和含义：
     * <ul>
     *   <li>90.0 - 北极（最北端）</li>
     *   <li>0.0 - 赤道（中间）</li>
     *   <li>-90.0 - 南极（最南端）</li>
     *   <li>null - 无效或未知纬度</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用Double而非基本类型double，支持null值表示"无效坐标"</li>
     *   <li>Double类型提供足够的精度（约15-17位有效数字）</li>
     *   <li>小数点后6位精度约为0.1米，满足大多数应用需求</li>
     *   <li>遵循地理学标准命名（lat是latitude的缩写）</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>Double比较操作在Native Image中可以被优化为直接比较基本类型</li>
     *   <li>坐标计算（距离、角度等）频繁访问该字段，考虑内存布局优化</li>
     *   <li>在缓存敏感的代码中，Double对象的内存布局影响性能</li>
     *   <li>考虑使用基本类型double和特殊值（如Double.NaN）表示无效坐标</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>Double是包装类，占用16字节（对象头12字节+double值8字节）</li>
     *   <li>大量Point对象时，Double对象占用显著内存</li>
     *   <li>相同的纬度值可以共享Double对象（Double缓存有限范围）</li>
     *   <li>在Native Image中，考虑使用基本类型减少内存开销</li>
     * </ul></p>
     *
     * <p>使用示例：
     * <pre>
     * Point point = new Point();
     * point.setLat(39.9042); // 北京纬度
     * point.setLng(116.4074); // 北京经度
     *
     * Double latitude = point.getLat(); // 获取纬度
     * if (latitude != null) {
     *     // 处理有效的纬度
     * }
     * </pre></p>
     *
     * <p>注意事项：
     * <ul>
     *   <li>必须验证纬度在有效范围内（-90.0 到 90.0）</li>
     *   <li>超出范围的纬度会导致坐标计算错误</li>
     *   <li>null值需要在业务逻辑中处理，避免NullPointerException</li>
     *   <li>不要依赖Double缓存机制（只缓存-128到127的值）</li>
     * </ul></p>
     *
     * <p>Native资源管理：
     * <ul>
     *   <li>在Native Image中，Double对象的分配和释放需要特殊处理</li>
     *   <li>坐标数据在渲染时会被频繁访问，确保内存局部性</li>
     *   <li>考虑使用连续的double数组存储大量坐标，提高缓存命中率</li>
     *   <li>避免在热路径中进行Double对象的创建和销毁</li>
     * </ul></p>
     *
     * @return 纬度值，范围-90.0到90.0，null表示无效或未知纬度
     * @see #lng 经度字段
     * @see Double Java Double包装类文档
     */
    private Double lat;

    /**
     * 经度（Longitude）
     *
     * <p>表示点在地球上的东西位置，从本初子午线（0度，通过英国格林威治）
     * 向东或向西测量的角度。</p>
     *
     * <p>经度范围和含义：
     * <ul>
     *   <li>180.0 - 国际日期变更线西侧（最西端）</li>
     *   <li>0.0 - 本初子午线（英国格林威治）</li>
     *   <li>-180.0 - 国际日期变更线东侧（最东端）</li>
     *   <li>null - 无效或未知经度</li>
     * </ul></p>
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用Double而非基本类型double，支持null值表示"无效坐标"</li>
     *   <li>Double类型提供足够的精度（约15-17位有效数字）</li>
     *   <li>小数点后6位精度约为0.1米，满足大多数应用需求</li>
     *   <li>遵循地理学标准命名（lng是longitude的缩写，避免与length混淆）</li>
     * </ul></p>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>Double比较操作在Native Image中可以被优化为直接比较基本类型</li>
     *   <li>坐标计算（距离、角度等）频繁访问该字段，考虑内存布局优化</li>
     *   <li>在缓存敏感的代码中，Double对象的内存布局影响性能</li>
     *   <li>考虑使用基本类型double和特殊值（如Double.NaN）表示无效坐标</li>
     * </ul></p>
     *
     * <p>内存管理：
     * <ul>
     *   <li>Double是包装类，占用16字节（对象头12字节+double值8字节）</li>
     *   <li>大量Point对象时，Double对象占用显著内存</li>
     *   <li>相同的经度值可以共享Double对象（Double缓存有限范围）</li>
     *   <li>在Native Image中，考虑使用基本类型减少内存开销</li>
     * </ul></p>
     *
     * <p>使用示例：
     * <pre>
     * Point point = new Point();
     * point.setLat(39.9042); // 北京纬度
     * point.setLng(116.4074); // 北京经度
     *
     * Double longitude = point.getLng(); // 获取经度
     * if (longitude != null) {
     *     // 处理有效的经度
     * }
     * </pre></p>
     *
     * <p>注意事项：
     * <ul>
     *   <li>必须验证经度在有效范围内（-180.0 到 180.0）</li>
     *   <li>超出范围的经度会导致坐标计算错误</li>
     *   <li>null值需要在业务逻辑中处理，避免NullPointerException</li>
     *   <li>不要依赖Double缓存机制（只缓存-128到127的值）</li>
     * </ul></p>
     *
     * <p>GeoJSON兼容性：
     * <ul>
     *   <li>GeoJSON格式使用[lng, lat]顺序（经度在前）</li>
     *   <li>序列化时需要注意字段顺序</li>
     *   <li>可以使用@JacksonPropertyOrder注解控制序列化顺序</li>
     * </ul></p>
     *
     * <p>Native资源管理：
     * <ul>
     *   <li>在Native Image中，Double对象的分配和释放需要特殊处理</li>
     *   <li>坐标数据在渲染时会被频繁访问，确保内存局部性</li>
     *   <li>考虑使用连续的double数组存储大量坐标，提高缓存命中率</li>
     *   <li>避免在热路径中进行Double对象的创建和销毁</li>
     * </ul></p>
     *
     * @return 经度值，范围-180.0到180.0，null表示无效或未知经度
     * @see #lat 纬度字段
     * @see Double Java Double包装类文档
     */
    private Double lng;
}
