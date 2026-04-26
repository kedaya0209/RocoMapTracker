package com.luoke.app.map.entity;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 瓦片数据实体类
 *
 * <p>表示地图瓦片的基本信息，包含瓦片的坐标位置和图像数据。</p>
 *
 * <p><b>设计特点：</b></p>
 * <ul>
 *   <li>使用Lombok的@Data注解自动生成getter/setter/equals/hashCode/toString方法</li>
 *   <li>使用Lombok的@AllArgsConstructor注解自动生成全参数构造器</li>
 *   <li>不可变设计倾向：通过构造器初始化后，仅通过setter修改（如需要）</li>
 * </ul>
 *
 * <p><b>坐标系统：</b></p>
 * <ul>
 *   <li>使用标准的Web瓦片坐标系（XYZ）</li>
 *   <li>x和y是瓦片在某个缩放级别下的行列号</li>
 *   <li>坐标从(0,0)开始，向右下递增</li>
 * </ul>
 *
 * <p><b>内存管理注意事项：</b></p>
 * <ul>
 *   <li>data字段持有字节数组引用，可能占用较大内存（瓦片图像通常为几十KB）</li>
 *   <li>在大量瓦片场景下，需要注意内存使用，及时释放不再使用的瓦片数据</li>
 *   <li>建议使用软引用或弱引用进行缓存管理，避免内存溢出</li>
 *   <li>byte数组是纯Java对象，无需特殊清理，GC会自动回收</li>
 * </ul>
 *
 * <p><b>Native Image兼容性：</b></p>
 * <ul>
 *   <li>不使用任何反射或动态代理，完全兼容GraalVM Native Image</li>
 *   <li>Lombok生成的代码在编译时已生成，无运行时代码生成</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @version 1.0
 */
@Data
@AllArgsConstructor
public class Tile {
    /**
     * 瓦片在地图网格中的X坐标（列号）
     *
     * <p><b>取值范围：</b>非负整数，从0开始</p>
     * <p><b>说明：</b>表示瓦片在缩放级别下的水平位置</p>
     */
    private int x;

    /**
     * 瓦片在地图网格中的Y坐标（行号）
     *
     * <p><b>取值范围：</b>非负整数，从0开始</p>
     * <p><b>说明：</b>表示瓦片在缩放级别下的垂直位置</p>
     */
    private int y;

    /**
     * 瓦片的图像数据
     *
     * <p><b>数据格式：</b>通常是PNG或JPG格式的字节数组</p>
     * <p><b>典型大小：</b>取决于图像格式和瓦片内容，通常在10KB-100KB之间</p>
     * <p><b>生命周期：</b>由创建者管理，GC会自动回收不再引用的数组</p>
     *
     * <p><b>内存优化建议：</b></p>
     * <ul>
     *   <li>在缓存大量瓦片时，考虑使用压缩格式</li>
     *   <li>对于不需要的瓦片，应立即置null释放内存</li>
     *   <li>使用WeakHashMap或软引用进行缓存，避免内存溢出</li>
     * </ul>
     *
     * <p><b>线程安全：</b>byte数组本身非线程安全，多线程访问需外部同步</p>
     */
    private byte[] data;
}