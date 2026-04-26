package com.luoke.app.context;

import com.luoke.app.map.model.ResourcePoint;
import javafx.geometry.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源点位的网格空间索引（高性能空间索引结构）
 * <p>
 * 职责：
 * <ul>
 *   <li>将资源点位分布到网格单元中，构建空间索引</li>
 *   <li>提供高效的邻近查询接口</li>
 *   <li>优化地理空间查询的性能</li>
 * </ul>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>索引构建：将点位按坐标分布到网格单元</li>
 *   <li>邻近查询：快速获取指定某位置附近的点位</li>
 *   <li>性能优化：将查询复杂度从O(n)降低到接近O(1)</li>
 * </ul>
 * <p>
 * 算法原理：
 * <ul>
 *   <li>将地图空间划分为固定大小的网格（CELL_SIZE x CELL_SIZE）</li>
 *   <li>每个点位根据其坐标分配到对应的网格单元</li>
 *   <li>查询时只扫描目标网格及其周围的9个网格（3x3区域）</li>
 *   <li>避免遍历所有点位，大幅提升查询性能</li>
 * </ul>
 * <p>
 * 性能优势：
 * <ul>
 *   <li>构建索引：时间复杂度O(n)，n为点位数量</li>
 *   <li>邻近查询：时间复杂度接近O(1)，只查询固定数量的网格</li>
 *   <li>空间复杂度：O(n)，需要额外的网格存储空间</li>
 *   <li>适合点位数量大、查询频繁的场景</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>游戏循环中每帧查询玩家附近的资源点</li>
 *   <li>地图渲染时只渲染可见区域的点位</li>
 *   <li>距离检测和拾取判断</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>网格大小需要根据实际场景调整（CELL_SIZE）</li>
 *   <li>点位分布均匀时效果最好，极端分布可能有空网格</li>
 *   <li>查询结果可能包含距离较远的点位（外层网格）</li>
 *   <li>适合静态或低频更新的场景，频繁重建索引有开销</li>
 * </ul>
 */
public class ResourceGridIndex {
    // ====================== 【网格配置】 ======================
    // 网格单元格大小（像素）
    // 用于匹配拾取半径或可见区域大小
    // 值越大，单个网格包含的点位越多，查询时返回的结果越多
    // 值越小，网格划分越细，查询精度越高但可能需要查询更多网格
    private static final int CELL_SIZE = 120; // 与拾取半径匹配

    // ====================== 【网格存储结构】 ======================
    // 网格映射表：网格坐标键 -> 该网格内的所有资源点位
    // 键格式："{cellX}_{cellY}"，如"5_3"表示第5列第3行的网格
    // 值：该网格内所有点位的列表（允许为空）
    // 使用HashMap实现O(1)的网格查找性能
    private final Map<String, List<ResourcePoint>> grid = new HashMap<>();

    /**
     * 构建资源点位的空间网格索引
     * <p>
     * 调用时机：在应用启动或资源点位更新时调用
     * <p>
     * 功能流程：
     * <ol>
     *   <li>清空现有索引数据</li>
     *   <li>遍历所有资源点位</li>
     *   <li>计算每个点位所属的网格坐标</li>
     *   <li>将点位添加到对应的网格单元中</li>
     * </ol>
     * <p>
     * 网格划分算法：
     * <pre>
     * 网格X坐标 = int(点位X坐标 / CELL_SIZE)
     * 网格Y坐标 = int(点位Y坐标 / CELL_SIZE)
     * 网格键值 = "{网格X坐标}_{网格Y坐标}"
     * </pre>
     * <p>
     * 示例：
     * <pre>
     * 点位(250, 180) 网格大小120
     * 网格X = int(250 / 120) = 2
     * 网格Y = int(180 / 120) = 1
     * 网格键值 = "2_1"
     * </pre>
     * <p>
     * 性能特点：
     * <ul>
     *   <li>时间复杂度：O(n)，n为点位数量</li>
     *   <li>空间复杂度：O(n)，每个点位存储一次</li>
     *   <li>使用computeIfAbsent避免重复创建列表</li>
     *   <li>适合一次性构建，频繁查询的场景</li>
     * </ul>
     * <p>
     * 内存管理：
     * <ul>
     *   <li>每个网格创建一个ArrayList对象</li>
     *   <li>点位分布不均匀时，部分网格可能为空</li>
     *   <li>点位分布密集时，单个网格可能包含大量点位</li>
     * </ul>
     *
     * @param points 需要建立索引的资源点位列表（不能为null）
     * @throws NullPointerException 如果points参数为null
     * @see #getCellKey(double, double)
     */
    public void buildIndex(List<ResourcePoint> points) {
        // 参数校验：确保点位列表不为null
        if (points == null) {
            throw new NullPointerException("资源点位列表不能为null");
        }

        // 清空现有索引数据：支持重新构建索引
        // 如果不清理，会导致旧数据残留影响查询结果
        grid.clear();

        // 遍历所有资源点位：逐个计算网格坐标并存储
        for (ResourcePoint p : points) {
            // 获取点位的屏幕坐标：屏幕像素坐标
            // 坐标系统与构建索引时使用的坐标系保持一致
            Point2D pos = p.getScreenPosition();

            // 计算点位所属的网格键值
            // 使用整数除法计算网格坐标，向下取整
            // 例如：x=250, CELL_SIZE=120 => cellX=2
            String key = getCellKey(pos.getX(), pos.getY());

            // 将点位添加到对应网格的列表中
            // computeIfAbsent：如果网格不存在则创建新列表
            // 这种方式比get+put更简洁，且保证了线程安全（虽然当前场景不涉及多线程）
            grid.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
    }

    /**
     * 查询指定位置附近的资源点位（核心查询方法）
     * <p>
     * 功能说明：返回目标坐标周围3x3网格区域内的所有资源点位
     * <p>
     * 查询范围：
     * <ul>
     *   <li>以目标坐标为中心</li>
     *   <li>查询3x3网格区域（目标网格+周围8个网格）</li>
     *   <li>实际覆盖范围约为3*CELL_SIZE x 3*CELL_SIZE</li>
     *   <li>例如：CELL_SIZE=120时，覆盖约360x360像素区域</li>
     * </ul>
     * <p>
     * 查询算法：
     * <pre>
     * 1. 计算目标坐标所属的网格(cellX, cellY)
     * 2. 遍历周围9个网格：dx=-1,0,1; dy=-1,0,1
     * 3. 收集这些网格中的所有点位
     * 4. 返回合并后的点位列表
     * </pre>
     * <p>
     * 性能优势：
     * <ul>
     *   <li>时间复杂度：接近O(1)，因为查询的网格数量固定为9</li>
     *   <li>空间复杂度：O(k)，k为返回的点位数量</li>
     *   <li>比暴力遍历（O(n)）性能提升显著，特别是点位数量大时</li>
     *   <li>适合高频调用，如每帧更新时查询</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>游戏循环中每帧查询玩家附近的资源</li>
     *   <li>UI渲染时只渲染可见区域的资源点位</li>
     *   <li>距离检测和拾取判断</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>返回的列表可能包含距离较远的点位（外层网格）</li>
     *   <li>如果网格为空，则该网格不返回任何点位</li>
     *   <li>查询结果未排序，按网格顺序返回</li>
     *   <li>返回的列表是新创建的，可以安全修改</li>
     * </ul>
     *
     * @param x 目标位置的屏幕X坐标（像素）
     * @param y 目标位置的屏幕Y坐标（像素）
     * @return 附近的资源点位列表（包含3x3网格区域内的所有点位）
     */
    public List<ResourcePoint> queryNear(double x, double y) {
        // 创建结果列表：用于存储查询到的所有点位
        // 初始容量可以预设，但由于不知道确切数量，使用默认构造
        List<ResourcePoint> result = new ArrayList<>();

        // 计算目标坐标所属的网格坐标
        // 使用整数除法向下取整，确定目标网格位置
        int cellX = (int) (x / CELL_SIZE);
        int cellY = (int) (y / CELL_SIZE);

        // 遍历目标网格周围的3x3区域
        // dx和dy的取值范围：-1, 0, 1
        // 这确保了查询目标网格及其周围的8个相邻网格
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                // 计算当前网格的绝对坐标
                // cellX+dx: 目标网格X + 偏移量X
                // cellY+dy: 目标网格Y + 偏移量Y
                String key = (cellX + dx) + "_" + (cellY + dy);

                // 获取当前网格中的点位列表
                // 如果网格不存在（grid中没有该key），则返回null
                List<ResourcePoint> cell = grid.get(key);

                // 如果网格存在且有点位，则添加到结果列表
                if (cell != null) {
                    // addAll会添加列表中的所有元素
                    // 这里直接添加引用，不创建新列表，减少内存开销
                    result.addAll(cell);
                }
            }
        }

        // 返回查询结果：包含3x3网格区域内的所有点位
        // 调用方可以安全地修改返回的列表，不影响原始索引数据
        return result;
    }

    /**
     * 根据坐标计算对应的网格键值
     * <p>
     * 功能说明：将屏幕像素坐标转换为网格坐标键值
     * <p>
     * 计算公式：
     * <pre>
     * 网格X坐标 = int(坐标X / CELL_SIZE)
     * 网格Y坐标 = int(坐标Y / CELL_SIZE)
     * 网格键值 = "{网格X坐标}_{网格Y坐标}"
     * </pre>
     * <p>
     * 示例：
     * <pre>
     * 坐标(250, 180), CELL_SIZE=120
     * 网格X = int(250 / 120) = 2
     * 网格Y = int(180 / 120) = 1
     * 返回 = "2_1"
     * </pre>
     * <p>
     * 边界情况：
     * <ul>
     *   <li>坐标为负数时，结果网格也为负数（符合数学定义）</li>
     *   <li>坐标为0时，网格为0（第一个网格）</li>
     *   <li>坐标接近边界时，整数除法会正确处理</li>
     * </ul>
     * <p>
     * 性能考虑：
     * <ul>
     *   <li>使用整数除法，性能优于浮点运算</li>
     *   <li>使用字符串拼接生成键值</li>
     *   <li>适合高频调用，但可考虑使用long代替String优化性能</li>
     * </ul>
     * <p>
     * 设计选择：
     * <ul>
     *   <li>使用String作为键值：简单直观，易于调试</li>
     *   <li>可优化方案：使用long（高位存cellX，低位存cellY）</li>
     *   <li>可优化方案：使用自定义的GridKey类</li>
     * </ul>
     *
     * @param x 屏幕X坐标（像素）
     * @param y 屏幕Y坐标（像素）
     * @return 网格键值字符串，格式为"{cellX}_{cellY}"
     */
    private String getCellKey(double x, double y) {
        // 计算网格X坐标：整数除法，向下取整
        // 例如：x=250, CELL_SIZE=120 => 2
        // 例如：x=119, CELL_SIZE=120 => 0
        int cx = (int) (x / CELL_SIZE);

        // 计算网格Y坐标：整数除法，向下取整
        int cy = (int) (y / CELL_SIZE);

        // 返回网格键值字符串：格式为"{cellX}_{cellY}"
        // 使用下划线作为分隔符，确保唯一性和可读性
        return String.valueOf(cx) + "_" + String.valueOf(cy);
    }
}
