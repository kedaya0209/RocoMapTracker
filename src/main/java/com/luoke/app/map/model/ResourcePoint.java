package com.luoke.app.map.model;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import lombok.Data;

/**
 * 地图资源点位
 * <p>
 * 表示地图上的一个资源点位，包含配置、位置和显示状态。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>存储资源配置信息</li>
 *   <li>存储屏幕位置坐标</li>
 *   <li>管理置灰显示状态</li>
 *   <li>渲染点位图标到画布</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用全局静态的ColorAdjust实例，避免重复创建</li>
 *   <li>置灰效果通过饱和度调整实现</li>
 *   <li>渲染时考虑图标的实际尺寸，居中显示</li>
 *   <li>使用GraphicsContext的save/restore确保状态隔离</li>
 * </ul>
 * <p>
 * 坐标系统：
 * <ul>
 *   <li>screenPosition: 屏幕坐标，图标的底部中心点</li>
 *   <li>绘制位置: 屏幕坐标减去图标尺寸的一半</li>
 *   <li>Y轴偏移: 图标高度，确保图标底部对齐</li>
 * </ul>
 * <p>
 * 显示效果：
 * <ul>
 *   <li>正常状态: 原始颜色，完全不透明</li>
 *   <li>置灰状态: 饱和度-1.0，透明度0.4</li>
 *   <li>效果切换: 通过grayed字段控制</li>
 * </ul>
 * <p>
 * Native资源管理：
 * <ul>
 *   <li>使用全局静态的ColorAdjust实例，减少Native对象创建</li>
 *   <li>Image对象由外部管理，不在此类中创建和释放</li>
 *   <li>GraphicsContext的save/restore确保状态正确管理</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>全局共享ColorAdjust实例，减少内存开销</li>
 *   <li>避免重复创建相同的效果对象</li>
 *   <li>使用GraphicsContext的状态管理，避免副作用</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Data
public class ResourcePoint {
    /**
     * 全局公用置灰效果
     * <p>
     * 使用静态final变量，确保全局唯一实例：
     * <ul>
     *   <li>避免重复创建ColorAdjust对象</li>
     *   <li>减少Native资源开销</li>
     *   <li>所有ResourcePoint共享同一个效果实例</li>
     * </ul>
     * <p>
     * 置灰效果原理：
     * <ul>
     *   <li>饱和度设置为-1.0（最小值）</li>
     *   <li>完全去除颜色，只保留亮度</li>
     *   <li>实现灰度显示效果</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>ColorAdjust是Native对象，占用系统资源</li>
     *   <li>全局共享避免重复创建，减少开销</li>
     *   <li>对Native Image打包和运行都很重要</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>所有点位共享一个效果实例</li>
     *   <li>避免大量Native对象创建</li>
     *   <li>减少GC压力</li>
     * </ul>
     */
    // 全局公用1个置灰效果（全局复用，不重复new）
    private static final ColorAdjust GRAY_EFFECT;

    // 静态初始化块：初始化全局置灰效果
    static {
        // 创建ColorAdjust对象
        GRAY_EFFECT = new ColorAdjust();

        // 设置饱和度为-1.0（最小值）
        // 饱和度拉到最低 = 完全灰
        // 饱和度范围: -1.0 ~ 1.0
        // -1.0: 完全去色，灰度显示
        // 0.0: 原始饱和度
        // 1.0: 饱和度翻倍
        GRAY_EFFECT.setSaturation(-1.0);
    }

    /**
     * 资源配置信息
     * <p>
     * 包含该点位的完整资源配置：
     * <ul>
     *   <li>资源类型和名称</li>
     *   <li>图标文件名</li>
     *   <li>地理坐标（经纬度）</li>
     *   <li>图层和缩放级别</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>获取图标文件名加载图片</li>
     *   <li>显示资源名称</li>
     *   <li>判断资源类型进行过滤</li>
     * </ulld
     */
    private final ResourceConfig config;

    /**
     * 屏幕位置坐标
     * <p>
     * 表示点位在屏幕上的位置：
     * <ul>
     *   <li>使用Point2D表示二维坐标</li>
     *   <li>X坐标: 屏幕水平位置</li>
     *   <li>Y坐标: 屏幕垂直位置</li>
     *   <li>坐标原点: 画布左上角</li>
     * </ul>
     * <p>
     * 坐标含义：
     * <ul>
     *   <li>图标的底部中心点</li>
     *   <li>绘制时需要减去图标尺寸的一半</li>
     *   <li>确保图标底部对齐到指定位置</li>
     * </ul>
     * <p>
     * 坐标转换：
     * <ul>
     *   <li>从地理坐标投影到屏幕坐标</li>
     *   <li>考虑地图缩放和平移</li>
     *   <li>由外部计算并设置</li>
     * </ul>
     */
    private final Point2D screenPosition;

    /**
     * 置灰状态标志
     * <p>
     * 控制点位的显示状态：
     * <ul>
     *   <li>true: 置灰显示</li>
     *   <li>false: 正常显示</li>
     * </ul>
     * <p>
     * 置灰效果：
     * <ul>
     *   <li>透明度降低到0.4</li>
     *   <li>饱和度设置为-1.0</li>
     *   <li>显示为灰色，半透明</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>已访问的点位置灰</li>
     *   <li>不可达的点位置灰</li>
     *   <li>根据条件切换显示状态</li>
     * </ul>
     */
    private boolean grayed;

    /**
     * 构造函数
     * <p>
     * 创建资源点位对象：
     * <ul>
     *   <li>设置资源配置信息</li>
     *   <li>设置屏幕位置</li>
     *   <li>默认为正常显示（不置灰）</li>
     * </ul>
     * <p>
     * 参数说明：
     * <ul>
     *   <li>config: 必须非空，包含资源信息</li>
     *   <li>screenPosition: 必须非空，指定显示位置</li>
     *   <li>grayed: 默认false，正常显示</li>
     * </ul>
     * <p>
     * 不变对象：
     * <ul>
     *   <li>config和screenPosition使用final修饰</li>
     *   <li>构造后不可更改，确保线程安全</li>
     *   <li>只有grayed字段可以修改</li>
     * </ul>
     *
     * @param config 资源配置信息，包含类型、图标、坐标等
     * @param screenPosition 屏幕位置坐标，指定图标显示位置
     */
    public ResourcePoint(ResourceConfig config, Point2D screenPosition) {
        this.config = config;
        this.screenPosition = screenPosition;
    }

    // ====================== 全版本兼容 安全绘制 ======================

    /**
     * 渲染点位图标到画布
     * <p>
     * 该方法将点位图标渲染到GraphicsContext画布：
     * <ol>
     *   <li>检查图标是否有效（非空且无错误）</li>
     *   <li>获取屏幕坐标和图标尺寸</li>
     *   <li>计算图标的绘制位置</li>
     *   <li>保存GraphicsContext当前状态</li>
     *   <li>如果置灰，应用置灰效果</li>
     *   <li>绘制图标到计算位置</li>
     *   <li>恢复GraphicsContext状态</li>
     * </ol>
     * <p>
     * 坐标计算：
     * <ul>
     *   <li>绘制X = 屏幕X - 图标宽度/2</li>
     *   <li>绘制Y = 屏幕Y - 图标高度</li>
     *   <li>确保图标底部中心对齐到屏幕坐标</li>
     * </ul>
     * <p>
     * 置灰效果实现：
     * <ul>
     *   <li>设置全局透明度为0.4</li>
     *   <li>设置ColorAdjust效果为全局GRAY_EFFECT</li>
     *   <li>效果应用到后续所有绘制操作</li>
     * </ul>
     * <p>
     * 状态管理：
     * <ul>
     *   <li>使用save()保存当前状态</li>
     *   <li>使用restore()恢复到之前状态</li>
     *   <li>确保副作用不影响后续绘制</li>
     *   <li>全版本兼容，安全绘制</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>Image对象由外部管理，不在此类中创建和释放</li>
     *   <li>ColorEffect为全局静态，避免重复创建</li>
     *   <li>GraphicsContext的状态正确管理，避免泄漏</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>使用全局共享的ColorEffect实例</li>
     *   <li>避免重复创建相同的效果对象</li>
     *   <li>save/restore开销很小，确保渲染正确性</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>检查图标是否为null</li>
     *   <li>检查图标是否有错误（isError）</li>
     *   <li>无效图标静默跳过，不抛异常</li>
     * </ul>
     *
     * @param gc GraphicsContext绘图上下文，用于绘制图标
     * @param icon 图标图片对象，包含图标数据
     */
    public void render(GraphicsContext gc, Image icon) {
        // 检查图标是否有效
        // 无效图标（null或加载失败）静默跳过
        // 避免NPE或绘制失败
        if (icon == null || icon.isError()) return;

        // 获取屏幕坐标
        // screenPosition是图标的底部中心点
        double x = screenPosition.getX();
        double y = screenPosition.getY();

        // 获取图标尺寸
        // Image对象已经加载完成，可以获取尺寸
        double w = icon.getWidth();
        double h = icon.getHeight();

        // 计算图标的绘制位置
        // 确保图标底部中心对齐到屏幕坐标
        // X: 向左偏移图标宽度的一半
        // Y: 向上偏移图标高度（图标底部对齐）
        double drawX = x - w / 2;
        double drawY = y - h;

        // 保存GraphicsContext当前状态
        // 包括变换、剪裁、效果、颜色等
        // 确保后续修改不影响其他绘制操作
        gc.save();

        // 如果置灰，应用置灰效果
        if (grayed) {
            // 设置全局透明度为0.4
            // 使图标半透明显示
            gc.setGlobalAlpha(0.4);

            // 设置ColorAdjust效果
            // 直接使用全局共享的GRAY_EFFECT实例
            // 饱和度-1.0实现灰度显示
            gc.setEffect(GRAY_EFFECT);
        }

        // 绘制图标到计算位置
        // 图标左上角对齐到(drawX, drawY)
        gc.drawImage(icon, drawX, drawY);

        // 恢复GraphicsContext到保存的状态
        // 撤销所有修改，恢复到save()之前的状态
        // 包括透明度、效果等
        // 确保不影响后续绘制操作
        gc.restore();
    }
}
