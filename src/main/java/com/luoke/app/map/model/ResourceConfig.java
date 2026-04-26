package com.luoke.app.map.model;

import lombok.Data;

/**
 * 资源配置
 * <p>
 * 表示地图上的一个资源点位的完整配置信息。
 * 该类由地图分类数据（MapCategoryItem）和点位数据（MapPointItem）合并而成。
 * <p>
 * 数据来源：
 * <ul>
 *   <li>type: 资源类型（来自MapCategoryItem）</li>
 *   <li>markType: 资源类型ID（来自MapCategoryItem）</li>
 *   <li>markTypeName: 资源名称（来自MapCategoryItem）</li>
 *   <li>icon: 图标文件名（来自MapCategoryItem的icon URL）</li>
 *   <li>lat: 经度（来自MapPointItem的Point）</li>
 *   <li>lng: 纬度（来自MapPointItem的Point）</li>
 *   <li>layer: 图层（来自MapPointItem）</li>
 *   <li>zoom: 缩放级别（默认值）</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>构建ResourcePoint对象</li>
 *   <li>序列化为JSON配置文件</li>
 *   <li>前端加载资源配置</li>
 *   <li>资源过滤和分组</li>
 * </ul>
 * <p>
 * 坐标系统说明：
 * <ul>
 *   <li>lat: 实际存储的是经度（字段名有误，保留兼容）</li>
 *   <li>lng: 实际存储的是纬度（字段名有误，保留兼容）</li>
 *   <li>地理坐标系：WGS84</li>
 *   <li>单位：度（decimal degrees）</li>
 * </ul>
 * <p>
 * 图标说明：
 * <ul>
 *   <li>原始数据为完整URL</li>
 *   <li>构建时提取文件名部分</li>
 *   <li>例如："https://example.com/icon.png" -> "icon.png"</li>
 *   <li>前端根据文件名加载本地图标</li>
 * </ul>
 * <p>
 * Native资源管理：
 * <ul>
 *   <li>纯数据类，无Native资源</li>
 *   <li>使用Lombok自动生成getter/setter</li>
 *   <li>序列化时由Jackson管理内存</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Data
public class ResourceConfig {
    /**
     * 资源类型（可以对应菜单按钮）
     * <p>
     * 取自 MapCategoryItem，用于资源分组和菜单显示。
     * <p>
     * 用途：
     * <ul>
     *   <li>前端菜单分组</li>
     *   <li>资源分类显示</li>
     *   <li>资源过滤条件</li>
     * </ul>
     * <p>
     * 示例值：
     * <ul>
     *   <li>"teleport": 传送点</li>
     *   <li>"npc": NPC</li>
     *   <li>"chest": 宝箱</li>
     * </ul>
     */
    private String type;

    /**
     * 资源类型ID
     * <p>
     * 取自 MapCategoryItem，用于关联点位和分类。
     * <p>
     * 用途：
     * <ul>
     *   <li>关联点位和分类数据</li>
     *   <li>唯一标识资源类型</li>
     *   <li>构建配置时的关联字段</li>
     * </ul>
     * <p>
     * 数据来源：
     * <ul>
     *   <li>MapCategoryItem.markType</li>
     *   <li>MapPointItem.markType</li>
     *   <li>通过此字段关联两个数据源</li>
     * </ul>
     */
    private Integer markType;

    /**
     * 资源名称
     * <p>
     * 取自 MapCategoryItem，用于显示和标识。
     * <p>
     * 用途：
     * <ul>
     *   <li>前端显示资源名称</li>
     *   <li>日志记录和调试</li>
     *   <li>资源标识</li>
     * </ul>
     * <p>
     * 示例值：
     * <ul>
     *   <li>"传送锚点"</li>
     *   <li>"冒险家协会"</li>
     *   <li>"普通宝箱"</li>
     * </ul>
     */
    private String markTypeName;

    /**
     * 图标文件名
     * <p>
     * 取自 MapCategoryItem的icon字段，原始为完整URL，构建时提取文件名部分。
     * <p>
     * 数据转换：
     * <ul>
     *   <li>原始数据："https://example.com/icon.png"</li>
     *   <li>提取方法：substring(lastIndexOf("/") + 1)</li>
     *   <li>结果文件名："icon.png"</li>
     *   <li>前端根据文件名加载本地图标</li>
     * </ul>
     * <p>
     * 用途：
     * <ul>
     *   <li>前端加载图标图片</li>
     *   <li>资源标识</li>
     *   <li>UI显示</li>
     * </ul>
     */
    private String icon;

    /**
     * 经度（注意：字段名lat实际存储经度）
     * <p>
     * 取自 MapPointItem的Point.lat，字段名有误但保留兼容。
     * <p>
     * 坐标系：
     * <ul>
     *   <li>地理坐标系：WGS84</li>
     *   <li>单位：度（decimal degrees）</li>
     *   <li>范围：-180.0 ~ 180.0</li>
     * </ul>
     * <p>
     * 用途：
     * <ul>
     *   <li>地图定位</li>
     *   <li>距离计算</li>
     *   <li>坐标转换</li>
     * </ul>
     */
    private Double lat;

    /**
     * 纬度（注意：字段名lng实际存储纬度）
     * <p>
     * 取自 MapPointItem的Point.lng，字段名有误但保留兼容。
     * <p>
     * 坐标系：
     * <ul>
     *   <li>地理坐标系：WGS84</li>
     *   <li>单位：度（decimal degrees）</li>
     *   <li>范围：-90.0 ~ 90.0</li>
     * </ul>
     * <p>
     * 用途：
     * <ul>
     *   <li>地图定位</li>
     *   <li>距离计算</li>
     *   <li>坐标转换</li>
     * </ul>
     */
    private Double lng;

    /**
     * 图层
     * <p>
     * 取自 MapPointItem，用于资源分层显示。
     * <p>
     * 用途：
     * <ul>
     *   <li>资源分层显示</li>
     *   <li>图层切换</li>
     *   <li>资源过滤</li>
     * </ul>
     * <p>
     * 示例值：
     * <ul>
     *   <li>"overworld": 地表资源</li>
     *   <li>"underground": 地下资源</li>
     *   <li>"sky": 天空资源</li>
     * </ul>
     */
    private String layer;

    /**
     * 默认缩放级别
     * <p>
     * 使用配置的默认值，所有资源配置共享相同的缩放级别。
     * <p>
     * 默认值：
     * <ul>
     *   <li>配置来源：MapResourceUpdater.DEFAULT_ZOOM</li>
     *   <li>典型值：4</li>
     *   <li>意义：中等缩放级别</li>
     * </ul>
     * <p>
     * 用途：
     * <ul>
     *   <li>地图初始缩放级别</li>
     *   <li>资源显示比例</li>
     *   <li>坐标转换参数</li>
     * </ul>
     * <p>
     * 注意：
     * <ul>
     *   <li>目前所有资源使用相同的缩放级别</li>
     *   <li>未来可以支持按类型设置不同缩放级别</li>
     * </ul>
     */
    private Integer zoom;
}
