package com.luoke.app.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.map.loader.ImageLoader;
import com.luoke.app.map.model.ResourcePoint;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

/**
 * 交互式地图画布组件
 * <p>
 * 该类继承自JavaFX的Canvas，实现了地图的交互式显示功能，包括：
 * <ul>
 *   <li>地图拖拽平移功能（在非跟随模式下）</li>
 *   <li>鼠标滚轮缩放功能（支持中心缩放和跟随模式缩放）</li>
 *   <li>自动适配地图尺寸到画布</li>
 *   <li>资源点图标绘制</li>
 *   <li>边界约束确保地图不会移出可视区域</li>
 * </ul>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>Canvas的底层使用Native GPU资源，需要通过适当的重绘管理来避免内存泄漏</li>
 *   <li>Image对象由ImageLoader统一管理，本类只负责绘制，不负责资源生命周期</li>
 *   <li>GraphicsContext使用save()和restore()确保绘制状态隔离，避免状态污染</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>使用firstResize标志避免重复的自动适配计算</li>
 *   <li>拖拽操作直接更新偏移量，不进行重绘，依赖外部渲染循环</li>
 *   <li>资源点绘制时应用统一的坐标变换，减少重复计算</li>
 * </ul>
 */
public class InteractiveCanvas extends Canvas {

    /**
     * 地图上下文管理器，负责管理地图的偏移、缩放等状态
     * <p>
     * 使用单例模式确保全局唯一的地图状态，避免多个画布实例之间的状态不一致。
     * 在Native Image环境下，频繁创建MapContext可能导致Native资源碎片化。
     */
    private final MapContext mapManager = MapContext.getInstance();

    /**
     * 首次调整大小标志
     * <p>
     * 用于标记是否为首次调整画布大小。首次调整时需要执行自动适配逻辑，
     * 将地图完整显示在画布中。后续的调整大小只需更新视图尺寸并确保边界约束。
     */
    private boolean firstResize = true;

    /**
     * 相机上下文管理器，负责管理相机的跟随模式
     * <p>
     * 在跟随模式下，地图位置由相机自动控制，用户无法手动拖拽地图。
     * 这种设计用于实时追踪玩家位置等场景。
     */
    private final CameraContext cameraManager = CameraContext.getInstance();

    /**
     * 资源点上下文管理器，负责管理所有地图上的资源点
     * <p>
     * 资源点包括图标、标注等地图上的标记元素。该上下文维护资源点的生命周期，
     * 本类只负责绘制逻辑。
     */
    private final ResourcePointContext pointContext = ResourcePointContext.getInstance();

    /**
     * 图片加载器，负责加载和缓存地图图标资源
     * <p>
     * 使用单例模式的图片加载器可以避免重复加载相同的图标，
     * 在Native Image环境中尤为重要，因为Native资源创建开销较大。
     * ImageLoader内部实现了LRU缓存策略，自动管理内存使用。
     */
    private final ImageLoader imageLoader = ImageLoader.getInstance();

    /**
     * 记录上一次鼠标按下时的X坐标
     * <p>
     * 用于计算拖拽过程中的位移量。在拖拽事件中，当前鼠标位置与上一次位置的差值
     * 就是需要平移地图的距离。
     */
    private double lastMouseX;

    /**
     * 记录上一次鼠标按下时的Y坐标
     * <p>
     * 与lastMouseX配合使用，支持二维平移操作。
     */
    private double lastMouseY;

    /**
     * 构造交互式画布
     * <p>
     * 初始化画布并设置必要的事件监听器：
     * <ul>
     *   <li>宽度和高度属性监听：动态调整地图视图尺寸</li>
     *   <li>鼠标按下监听：记录拖拽起始位置</li>
     *   <li>鼠标拖拽监听：实现地图平移</li>
     *   <li>滚轮滚动监听：实现地图缩放</li>
     * </ul>
     * <p>
     * 设计说明：
     * <ul>
     *   <li>setFocusTraversable(true)允许画布获取键盘焦点，为快捷键支持做准备</li>
     *   <li>setPickOnBounds(true)确保点击事件在画布边界内即可响应</li>
     *   <li>setMouseTransparent(false)确保画布能接收鼠标事件</li>
     * </ul>
     */
    public InteractiveCanvas() {
        // 允许画布获取焦点，支持键盘事件
        setFocusTraversable(true);
        // 启用边界拾取，点击画布边界区域也能响应事件
        setPickOnBounds(true);
        // 非透明，允许鼠标事件穿透处理
        setMouseTransparent(false);

        // 监听画布宽度变化，动态更新地图视图宽度
        widthProperty().addListener(e -> {
            // 更新地图视图宽度，影响边界约束计算
            mapManager.setViewWidth(getWidth());
            // 首次调整且画布有效时，自动适配地图大小以完整显示
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else {
                // 后续调整只需确保地图不会移出可视区域
                mapManager.ensureBounds();
            }
        });

        // 监听画布高度变化，动态更新地图视图高度
        heightProperty().addListener(e -> {
            // 更新地图视图高度，影响边界约束计算
            mapManager.setViewHeight(getHeight());
            // 首次调整且画布有效时，自动适配地图大小以完整显示
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else {
                // 后续调整只需确保地图不会移出可视区域
                mapManager.ensureBounds();
            }
        });

        // 鼠标按下事件：记录起始位置，为拖拽做准备
        setOnMousePressed(e -> {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            // 消费事件，防止事件传播到父节点
            e.consume();
        });

        // 鼠标拖拽事件：实现地图平移
        setOnMouseDragged(e -> {
            // 跟随模式下禁用拖拽，地图位置由相机自动控制
            if (cameraManager.isFollowMode()) return;

            // 计算拖拽位移量：当前位置减去上一次位置
            double dx = e.getX() - lastMouseX;
            double dy = e.getY() - lastMouseY;

            // 更新地图偏移量：在原有偏移基础上累加拖拽位移
            mapManager.setOffsetX(mapManager.getOffsetX() + dx);
            mapManager.setOffsetY(mapManager.getOffsetY() + dy);
            // 确保地图不会移出可视区域，应用边界约束
            mapManager.ensureBounds();

            // 更新上一次位置为当前位置，准备下一帧计算
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            // 消费事件，防止事件传播到父节点
            e.consume();
        });

        // 滚轮滚动事件：实现地图缩放
        setOnScroll(e -> {
            // 根据滚轮方向确定缩放因子：向上滚动放大，向下滚动缩小
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
            if (cameraManager.isFollowMode()) {
                // 跟随模式下调整相机缩放级别，而不是地图本身缩放
                // 限制缩放范围在0.3到5倍之间，避免过小或过大
                cameraManager.setFollowScale(Math.clamp(cameraManager.getFollowScale() * factor, 0.3, 5));
            } else {
                // 普通模式下以鼠标位置为中心进行地图缩放
                // 这种"以鼠标为中心"的缩放体验更自然，符合用户直觉
                mapManager.zoom(factor, e.getX(), e.getY());
            }
            // 消费事件，防止事件传播到父节点（如滚动条的滚动）
            e.consume();
        });
    }

    /**
     * 绘制所有资源点图标
     * <p>
     * 该方法负责将所有注册到地图上的资源点（如标记、图标等）绘制到画布上。
     * 绘制过程包含以下步骤：
     * <ol>
     *   <li>检查是否存在资源点，若为空则直接返回</li>
     *   <li>保存当前GraphicsContext状态</li>
     *   <li>应用地图的平移和缩放变换</li>
     *   <li>遍历所有资源点进行绘制</li>
     *   <li>恢复GraphicsContext状态</li>
     * </ol>
     * <p>
     * Native资源管理说明：
     * <ul>
     *   <li>使用save()和restore()确保GraphicsContext状态隔离，避免状态污染</li>
     *   <li>Image对象由ImageLoader统一管理，本类不负责其生命周期</li>
     *   <li>在Native Image环境中，频繁的GraphicsContext操作需要注意性能</li>
     * </ul>
     * <p>
     * 性能优化要点：
     * <ul>
     *   <li>在绘制前检查资源点列表是否为空，避免不必要的变换操作</li>
     *   <li>使用统一的坐标变换，减少每个资源点的重复计算</li>
     *   <li>跳过无效图标路径的资源点，减少无效绘制操作</li>
     * </ul>
     *
     * @param gc 图形上下文，用于绘制操作
     *            该上下文由外部传入，本类只负责使用而不管理其生命周期。
     *            GraphicsContext的底层使用Native资源，需要在适当的时机释放。
     */
    public void drawAllResourceIcons(GraphicsContext gc) {
        // 提前检查资源点列表，避免在无资源点时执行不必要的绘制操作
        if (pointContext.getAllPoints().isEmpty()) return;

        // 保存当前图形上下文状态，包括变换矩阵、颜色、字体等
        // 这是必要的，因为后续的translate和scale会改变全局变换状态
        gc.save();

        // 应用地图偏移量：将地图坐标系原点平移到当前显示位置
        gc.translate(mapManager.getOffsetX(), mapManager.getOffsetY());
        // 应用地图缩放：支持地图的放大和缩小显示
        gc.scale(mapManager.getScale(), mapManager.getScale());

        // 遍历所有资源点进行绘制
        // 注意：在变换后的坐标系中绘制，资源点坐标是地图原始坐标
        for (ResourcePoint point : pointContext.getAllPoints()) {
            // 获取资源点配置的图标路径
            String iconPath = point.getConfig().getIcon();
            // 跳过无效图标路径的资源点，避免加载异常
            if (iconPath == null || iconPath.isBlank()) continue;
            // 加载并缩放图标，ImageLoader内部实现了缓存，避免重复加载
            // 拼接完整的图标路径：图标目录 + 图标文件名
            Image icon = imageLoader.loadScaledIcon(AppConfig.ICON_DIR + iconPath);
            // 调用资源点的渲染方法，将图标绘制到画布上
            // ResourcePoint的render方法内部会根据资源点的位置和属性进行绘制
            point.render(gc, icon);
        }

        // 恢复图形上下文状态到保存之前的状态
        // 这是为了避免影响后续的绘制操作，确保状态隔离
        gc.restore();
    }

    /**
     * 自动适配地图尺寸到画布
     * <p>
     * 计算合适的缩放比例，使地图能够完整地显示在画布中，同时保持地图的宽高比。
     * 该方法通常在画布首次初始化时调用，确保地图初始状态下用户可以看到完整内容。
     * <p>
     * 算法说明：
     * <ol>
     *   <li>检查地图尺寸是否有效，无效则直接返回</li>
     *   <li>分别计算基于宽度和高度的缩放比例</li>
     *   <li>取两个比例中的较小值，确保地图完全适应画布</li>
     *   <li>应用缩放并确保边界约束</li>
     * </ol>
     * <p>
     * 边界约束确保地图至少有一部分区域在画布的可视范围内，避免地图完全移出视野。
     */
    private void autoFitMap() {
        // 检查地图尺寸是否有效，避免除零错误和无效缩放
        if (mapManager.getMapWidth() <= 0 || mapManager.getMapHeight() <= 0) return;

        // 计算两个维度的缩放比例：取较小值确保地图完全适应画布
        // 基于宽度的缩放：画布宽度 / 地图宽度
        // 基于高度的缩放：画布高度 / 地图高度
        // 使用min确保地图不会超出画布边界，保留地图的宽高比
        double scale = Math.min(getWidth() / mapManager.getMapWidth(), getHeight() / mapManager.getMapHeight());

        // 应用计算出的缩放比例
        mapManager.setScale(scale);
        // 确保地图不会移出可视区域，自动调整偏移量
        // 在初始状态下，这会将地图居中显示在画布中
        mapManager.ensureBounds();
    }
}
