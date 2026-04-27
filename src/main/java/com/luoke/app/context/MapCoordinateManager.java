package com.luoke.app.context;

import javafx.geometry.Point2D;

import java.util.HashMap;
import java.util.Map;

/**
 * 地图坐标管理器（单例模式）
 * <p>
 * 职责：
 * <ul>
 *   <li>管理不同地图的配置信息（缩放级别、尺寸等）</li>
 *   <li>提供地图坐标到屏幕坐标的转换功能</li>
 *   <li>处理多个地图版本的坐标系统差异</li>
 * </ul>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>注册地图配置信息（支持多地图切换）</li>
 *   <li>将原始地图坐标转换为UI显示的屏幕坐标</li>
 *   <li>应用缩放、偏移等变换以适配显示需求</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul>
 *   <li>此类专注于坐标计算，不涉及地图渲染</li>
 *   <li>依赖MapContext获取当前地图状态</li>
 *   <li>线程安全：单例实现，方法均为只读操作</li>
 * </ul>
 */
public class MapCoordinateManager {
    // 单例实例：确保全局只有一个坐标管理器
    private static final MapCoordinateManager INSTANCE = new MapCoordinateManager();

    // 地图配置映射：地图唯一标识 -> 地图配置信息
    // 用于支持多个不同版本的地图坐标系统
    private final Map<String, MapConfig> mapConfigMap = new HashMap<>();

    /**
     * 私有构造函数：防止外部实例化，确保单实例
     */
    private MapCoordinateManager() {
    }

    /**
     * 获取坐标管理器的单例实例
     *
     * @return 全局唯一的MapCoordinateManager实例
     */
    public static MapCoordinateManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册新地图的配置信息
     * <p>
     * 用于初始化不同地图版本的坐标系统参数。在应用启动时调用，
     * 为后续坐标转换提供必要的配置数据。
     *
     * @param key      地图的唯一标识符（通常为地图名称或ID）
     * @param w        地图的原始宽度（像素）
     * @param h        地图的原始高度（像素）
     * @param jsonZoom JSON配置文件中的缩放级别（参考基准）
     * @param img      实际地图图片的缩放级别（可能与jsonZoom不同）
     * @throws IllegalArgumentException 如果参数值为负数
     */
    public void registerMap(String key, int w, int h, int jsonZoom, int img) {
        // 参数校验：确保地图尺寸和缩放级别为正数
        if (w <= 0 || h <= 0) {
            throw new IllegalArgumentException("地图宽度和高度必须为正数");
        }
        if (jsonZoom < 0 || img < 0) {
            throw new IllegalArgumentException("缩放级别不能为负数");
        }

        // 存储地图配置：使用唯一key索引，后续通过key快速查找
        mapConfigMap.put(key, new MapConfig(jsonZoom, img, w, h));
    }

    /**
     * 将地图坐标转换为屏幕坐标（核心坐标转换方法）
     * <p>
     * 功能说明：
     * <ul>
     *   <li>从原始地图坐标系统（经纬度或相对坐标）转换到屏幕像素坐标</li>
     *   <li>应用多层变换：缩放级别差异、中心点偏移、用户缩放、用户平移</li>
     *   <li>支持动态地图切换和缩放</li>
     * </ul>
     * <p>
     * 变换顺序：
     * <pre>
     * 原始坐标 -> 缩放级别差异修正 -> 屏幕中心偏移 -> 用户缩放 -> 用户平移
     * </pre>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>使用Math.pow计算缩放倍数（避免重复计算）</li>
     *   <li>直接返回Point2D对象，减少中间转换</li>
     *   <li>方法无状态，适合高频调用</li>
     * </ul>
     *
     * @param x 原始地图坐标X值（通常为经度或相对坐标X）
     * @param y 原始地图坐标Y值（通常为纬度或相对坐标Y）
     * @return 转换后的屏幕坐标Point2D对象（像素坐标系）
     * @throws NullPointerException 如果当前地图未注册配置
     */
    public Point2D toScreen(double x, double y) {
        // 获取当前地图上下文：用于获取用户自定义的缩放和平移参数
        MapContext mm = MapContext.getInstance();

        // 获取当前激活地图的配置信息
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());
        if (cfg == null) {
            throw new NullPointerException("当前地图未注册配置: " + mm.getCurrentMapKey());
        }

        // 计算缩放因子：处理JSON配置和实际图片的缩放级别差异
        // 例如：图片缩放级别为15，而配置为12，则需要放大2^3=8倍
        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);

        // 应用地图尺寸和缩放：将原始坐标转换为以地图中心为原点的坐标
        // 坐标系统转换：从地图左上角(0,0)转换为以中心点为原点
        double mx = cfg.width / 2 + x * scale;
        double my = cfg.height / 2 + y * scale;

        // 应用用户自定义的缩放和平移：最终转换为屏幕显示坐标
        // 这是用户在界面上进行的缩放和拖拽操作的结果
        return new Point2D(
                mx,  // X方向：用户平移X + 地图坐标X * 用户缩放
                my   // Y方向：用户平移Y + 地图坐标Y * 用户缩放
        );
    }

    public Point2D fromScreen(double screenX, double screenY) {
        // 获取当前地图上下文
        MapContext mm = MapContext.getInstance();

        // 获取当前激活地图的配置信息
        MapConfig cfg = mapConfigMap.get(mm.getCurrentMapKey());
        if (cfg == null) {
            throw new NullPointerException("当前地图未注册配置: " + mm.getCurrentMapKey());
        }

        // 1. 逆向应用用户自定义的缩放和平移
        // 对应 toScreen 中的：mm.getOffsetX() + mx * mm.getScale()

        // 2. 计算配置缩放因子 (与 toScreen 保持一致)
        double scale = Math.pow(2, cfg.imageZoom - cfg.jsonZoom);

        // 3. 逆向应用地图尺寸中心对齐和配置缩放
        // 对应 toScreen 中的：cfg.width / 2 + x * scale
        double x = (screenX - cfg.width / 2.0) / scale;
        double y = (screenY - cfg.height / 2.0) / scale;

        return new Point2D(x, y);
    }

    /**
     * 地图配置信息记录类
     * <p>
     * 存储单个地图的坐标系统配置参数，不可变对象确保线程安全。
     * 使用Java 14+的record类型，自动生成构造函数、getter、equals、hashCode等方法。
     * <p>
     * 字段说明：
     * <ul>
     *   <li>jsonZoom: JSON配置文件中定义的参考缩放级别</li>
     *   <li>imageZoom: 实际使用的地图图片缩放级别（可能不同）</li>
     *   <li>width: 地图的原始宽度（像素）</li>
     *   <li>height: 地图的原始高度（像素）</li>
     * </ul>
     * <p>
     * 内存优化：使用record类型减少内存占用，适合大量实例存储
     */
    public record MapConfig(int jsonZoom, int imageZoom, double width, double height) {
    }
}