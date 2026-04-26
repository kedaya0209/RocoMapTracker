package com.luoke.app.context;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luoke.app.config.AppConfig;
import com.luoke.app.map.model.ResourceConfig;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.utils.JsonUtils;
import com.luoke.app.utils.ResourceUtils;
import javafx.geometry.Point2D;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 资源点位上下文管理器（单例模式）
 * <p>
 * 职责：
 * <ul>
 *   <li>从配置文件加载游戏资源点位数据（如矿石、木材等）</li>
 *   <li>预计算资源点位的屏幕坐标（经纬度转像素坐标）</li>
 *   <li>构建GEO空间索引以支持高性能的邻近查询</li>
 *   <li>提供多种查询接口（全部点位、按类型查询、邻近查询）</li>
 * </ul>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>配置加载：从JSON文件读取资源点位配置</li>
 *   <li>坐标转换：将地理坐标（经纬度）转换为屏幕显示坐标</li>
 *   <li>空间索引：使用网格索引优化邻近查询性能</li>
 *   <li>分类查询：支持按资源类型获取点位列表</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>使用GEO空间索引将邻近查询从O(n)优化到O(1)</li>
 *   <li>坐标预计算避免实时转换开销</li>
 *   <li>使用unmodifiableList防止外部修改</li>
 *   <li>使用stream优化按类型分组</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>loadAndInit()应在应用启动时调用一次</li>
 *   <li>预计算依赖于MapCoordinateManager的初始化</li>
 *   <li>getNearbyResources()是高频调用接口，性能关键</li>
 * </ul>
 */
@Slf4j
public class ResourcePointContext {
    // 单例实例：确保全局只有一个资源点位管理器
    private static final ResourcePointContext INSTANCE = new ResourcePointContext();

    // JSON解析器：用于解析配置文件
    // 使用JsonUtils的共享mapper实例，避免重复创建
    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    // ====================== 【原始数据】 ======================
    // 原始资源配置列表：从JSON文件直接读取的配置数据
    // 保留原始数据以支持重新预处理
    private final List<ResourceConfig> rawResourceList = new ArrayList<>();

    // ====================== 【预处理数据】 ======================
    // 预处理后的点位列表：包含计算好的屏幕坐标
    // 这是主要的查询数据源，用于地图渲染和邻近查询
    private final List<ResourcePoint> pointList = new ArrayList<>();

    // 按类型分组的点位映射：资源类型 -> 该类型的所有点位
    // 用于快速获取特定类型的资源点位（如所有矿石点）
    private final Map<String, List<ResourcePoint>> pointByType = new HashMap<>();

    // ====================== 【空间索引】 ======================
    // GEO空间网格索引：用于高性能的邻近查询
    // 基于网格的索引结构，将邻近查询从O(n)优化到O(1)
    private final ResourceGridIndex gridIndex = new ResourceGridIndex();

    /**
     * 获取资源点位上下文管理器的单例实例
     *
     * @return 全局唯一的ResourcePointContext实例
     */
    public static ResourcePointContext getInstance() {
        return INSTANCE;
    }

    /**
     * 私有构造函数：防止外部实例化，确保单实例
     */
    private ResourcePointContext() {
    }

    /**
     * 加载配置文件并初始化资源点位（应用启动时调用一次）
     * <p>
     * 调用时机：应用启动时，在MapCoordinateManager初始化之后调用
     * <p>
     * 功能流程：
     * <ol>
     *   <li>从classpath读取资源配置JSON文件</li>
     *   <li>使用Jackson解析为ResourceConfig列表</li>
     *   <li>调用preprocessPoints()进行预处理</li>
     *   <li>记录加载日志</li>
     * </ol>
     * <p>
     * 配置文件格式：
     * <pre>
     * [
     *   {
     *     "name": "矿石点1",
     *     "type": "矿石",
     *     "lat": 34.056,
     *     "lng": 118.234
     *   },
     *   ...
     * ]
     * </pre>
     * <p>
     * 内存管理：
     * <ul>
     *   <li>InputStream使用后自动关闭</li>
     *   <li>异常时抛出RuntimeException，防止应用继续运行</li>
     *   <li>成功加载后输出点位总数</li>
     * </ul>
     *
     * @throws RuntimeException 如果配置文件加载失败或解析失败
     * @see AppConfig#RESOURCE_POINT_CONFIG_PATH
     * @see #preprocessPoints()
     */
    public void loadAndInit() {
        try {
            // 从classpath读取资源配置流：使用ResourceUtils工具类
            InputStream inputStream = ResourceUtils.getResourceStream(AppConfig.RESOURCE_POINT_CONFIG_PATH);

            // 使用Jackson解析JSON为ResourceConfig列表
            // TypeReference用于处理泛型类型List<ResourceConfig>
            List<ResourceConfig> configs = objectMapper.readValue(inputStream, new TypeReference<List<ResourceConfig>>() {
            });

            // 清空原有数据：支持重新加载
            rawResourceList.clear();

            // 加载新配置数据
            rawResourceList.addAll(configs);

            // 执行预处理：坐标转换、分组、索引构建
            preprocessPoints();

            // 记录加载成功日志：输出点位总数
            log.info("资源点位加载完成，总数：{}", pointList.size());

        } catch (Exception e) {
            // 抛出RuntimeException：防止应用在缺少必要配置的情况下运行
            throw new RuntimeException("资源点位配置加载失败", e);
        }
    }

    /**
     * 预处理资源点位：坐标转换、分组、构建索引
     * <p>
     * 功能说明：
     * <ul>
     *   <li>将经纬度坐标转换为屏幕坐标</li>
     *   <li>按资源类型对点位进行分组</li>
     *   <li>构建GEO空间索引以支持高效邻近查询</li>
     * </ul>
     * <p>
     * 预处理流程：
     * <ol>
     *   <li>清空原有预处理数据</li>
     *   <li>遍历原始配置，逐个转换坐标</li>
     *   <li>使用stream API按类型分组</li>
     *   <li>构建空间网格索引</li>
     * </ol>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>坐标转换只执行一次，后续查询直接使用缓存结果</li>
     *   <li>使用Java 8 stream并行处理可以提高性能（可考虑parallelStream）</li>
     *   <li>空间索引的构建时间复杂度为O(n)</li>
     * </ul>
     * <p>
     * 内存优化：
     * <ul>
     *   <li>使用HashMap存储分组，查询时间复杂度O(1)</li>
     *   <li>ResourcePoint对象包含配置和坐标，避免重复计算</li>
     * </ul>
     * <p>
     * 依赖要求：MapCoordinateManager必须已初始化
     *
     * @see MapCoordinateManager#toScreen(double, double)
     * @see ResourceGridIndex#buildIndex(List)
     */
    private void preprocessPoints() {
        // 清空原有预处理数据：支持重新加载时清除旧数据
        pointList.clear();
        pointByType.clear();

        // 获取坐标管理器：用于经纬度到屏幕坐标的转换
        MapCoordinateManager coordManager = MapCoordinateManager.getInstance();

        // 遍历原始配置：逐个处理每个资源点位
        for (ResourceConfig config : rawResourceList) {
            // 处理纬度坐标：如果为null则使用默认值0.0
            double lat = config.getLat() != null ? config.getLat() : 0.0;

            // 处理经度坐标：如果为null则使用默认值0.0
            double lng = config.getLng() != null ? config.getLng() : 0.0;

            // 坐标转换：经纬度 -> 屏幕像素坐标
            // 注意：坐标顺序为(lng, lat)即(经度, 纬度)，符合地理坐标惯例
            Point2D screenPos = coordManager.toScreen(lng, lat);

            // 创建ResourcePoint对象：包含配置和预计算的屏幕坐标
            ResourcePoint point = new ResourcePoint(config, screenPos);

            // 添加到预处理后的点位列表
            pointList.add(point);
        }

        // 按类型分组：使用stream API将点位按资源类型分组
        // groupingBy会创建一个Map<String, List<ResourcePoint>>
        pointByType.putAll(
                pointList.stream().collect(Collectors.groupingBy(
                        p -> p.getConfig().getType()  // 分组键：资源类型
                ))
        );

        // 构建GEO空间索引：用于高性能的邻近查询
        // 索引结构基于网格，查询复杂度从O(n)降到O(1)
        gridIndex.buildIndex(pointList);
    }

    /**
     * 获取玩家（或指定位置）附近的资源点（高频调用接口）
     * <p>
     * 功能说明：基于屏幕坐标查询邻近的资源点位
     * <p>
     * 性能特点：
     * <ul>
     *   <li>使用空间网格索引，查询复杂度接近O(1)</li>
     *   <li>比暴力遍历（O(n)）性能提升显著，特别是点位数量大时</li>
     *   <li>适合高频调用，如每帧更新时查询玩家附近的资源</li>
     * </ul>
     * <p>
     * 查询范围：
     * <ul>
     *   <li>默认查询3x3网格区域（见ResourceGridIndex.QUERY_RANGE）</li>
     *   <li>实际范围取决于网格大小（CELL_SIZE）</li>
     *   <li>通常覆盖屏幕上玩家周围的可见区域</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>游戏循环中每帧查询玩家附近的资源</li>
     *   <li>UI渲染时只渲染可见区域的资源点位</li>
     *   <li>距离检测和拾取判断</li>
   * </ul>
     * <p>
     * 注意事项：返回的列表可能包含距离查询点较远的点位（外层网格）
     *
     * @param x 查询中心的屏幕X坐标（像素）
     * @param y 查询中心的屏幕Y坐标（像素）
     * @return 附近的资源点列表（包含查询点所在网格及周围8个网格的点位）
     * @see ResourceGridIndex#queryNear(double, double)
     */
    public List<ResourcePoint> getNearbyResources(double x, double y) {
        // 直接委托给空间索引的查询方法
        // 空间索引内部使用网格结构快速定位邻近点位
        return gridIndex.queryNear(x, y);
    }

    /**
     * 获取所有资源点位列表（只读视图）
     * <p>
     * 功能说明：返回所有已加载的资源点位列表
     * <p>
     * 使用场景：
     * <ul>
     *   <li>地图渲染时绘制所有资源点位</li>
     *   <li>导出点位数据</li>
     *   <li>统计分析</li>
     * </ul>
     * <p>
     * 线程安全：返回不可修改列表，防止外部修改影响内部数据
     * <p>
     * 性能注意：返回的是包装列表，修改原始列表不会影响返回的列表
     *
     * @return 所有资源点位的不可修改列表
     */
    public List<ResourcePoint> getAllPoints() {
        // 返回不可修改列表：防止外部修改内部数据
        // 使用Collections.unmodifiableList包装，任何修改尝试都会抛出UnsupportedOperationException
        return Collections.unmodifiableList(pointList);
    }

    /**
     * 根据资源类型获取对应的点位列表
     * <p>
     * 功能说明：返回指定类型的所有资源点位
     * <p>
     * 使用场景：
     * <ul>
     *   <li>按类型渲染资源（如只显示矿石点）</li>
     *   <li>过滤特定类型的资源</li>
     *   <li>类型相关的统计和分析</li>
     * </ul>
     * <p>
     * 线程安全：
     * <ul>
     *   <li>返回原始列表的引用（非不可修改）</li>
     *   <li>如果类型不存在，返回空列表（防止返回null）</li>
     * </ul>
     * <p>
     * 性能特点：HashMap查询时间复杂度为O(1)
     *
     * @param type 资源类型（如"矿石"、"木材"等）
     * @return 指定类型的资源点位列表；如果类型不存在，返回空列表
     */
    public List<ResourcePoint> getPointsByType(String type) {
        // 使用getOrDefault：如果类型不存在，返回空列表而非null
        // 这避免了调用方需要检查null，简化了API使用
        return pointByType.getOrDefault(type, Collections.emptyList());
    }

}