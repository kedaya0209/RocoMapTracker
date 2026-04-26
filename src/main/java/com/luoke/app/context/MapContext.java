package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import com.luoke.app.event.PlayerPositionEvent;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.HookRegistry;
import javafx.scene.image.Image;
import lombok.Getter;
import lombok.Setter;

/**
 * 地图上下文管理类
 *
 * <p>负责管理地图图像、缩放、偏移、玩家位置等核心状态。
 * 实现地图的缩放、平移、边界限制等功能，并发布玩家位置更新事件。
 *
 * <p>核心功能：
 * <ul>
 *   <li>地图图像管理：加载和存储地图图像</li>
 *   <li>视口管理：缩放、平移、边界限制</li>
 *   <li>玩家位置管理：更新玩家坐标并发布事件</li>
 *   <li>坐标转换：世界坐标到屏幕坐标的转换</li>
 * </ul>
 *
 * <p>设计模式：
 * <ul>
 *   <li>单例模式：全局唯一实例，使用Holder实现懒加载</li>
 *   <li>观察者模式：通过HookRegistry发布玩家位置更新事件</li>
 * </ul>
 *
 * <p>数学原理：
 * <ul>
 *   <li>屏幕坐标 = 偏移量 + 世界坐标 * 缩放比例</li>
 *   <li>边界限制：确保视口不会超出地图边界</li>
 *   <li>缩放中心：以鼠标位置为中心进行缩放</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Getter
@Setter
public class MapContext {
    /**
     * 地图图像对象
     *
     * <p>注意：
     * <ul>
     *   <li>如果为null，表示地图未加载</li>
     *   <li>使用init()或initWithKey()方法设置</li>
     *   <li>JavaFX自动管理图像内存，无需手动释放</li>
     * </ul>
     */
    private Image mapImage;

    /**
     * 地图原始尺寸（像素）
     *
     * <p>与mapImage关联，通过mapImage.getWidth()和mapImage.getHeight()获取
     */
    private double mapWidth, mapHeight;

    /**
     * 视口状态参数
     *
     * <ul>
     *   <li>scale：缩放比例，1.0表示原始尺寸</li>
     *   <li>offsetX, offsetY：视口偏移量，相对于地图左上角</li>
     * </ul>
     *
     * <p>数学关系：
     * <ul>
     *   <li>屏幕坐标X = offsetX +X * scale</li>
     *   <li>屏幕坐标Y = offsetY +Y * scale</li>
     * </ul>
     */
    private double scale = 1.0, offsetX = 0, offsetY = 0;

    /**
     * 视口尺寸（像素）
     *
     * <p>表示当前可见区域的大小，通常为窗口大小
     */
    private double viewWidth, viewHeight;

    /**
     * 玩家位置（世界坐标）
     *
     * <ul>
     *   <li>playerX, playerY：玩家在地图中的位置</li>
     *   <li>初始值为-1，表示玩家位置未初始化</li>
     *   <li>通过updatePlayerState()方法更新</li>
     * </ul>
     */
    private double playerX = -1, playerY = -1;

    /**
     * 玩家朝向角度（度）
     *
     * <p>范围：[0, 360)，表示玩家面朝的方向
     */
    private double playerAngle = 0;

    /**
     * 玩家位置初始化标志
     *
     * <p>true：玩家位置已初始化，可以计算屏幕坐标
     * false：玩家位置未初始化，playerX/Y值为-1
     */
    private boolean playerInitialized = false;

    // ====================== 我加的：当前地图唯一 KEY ======================
    /**
     * 当前地图的唯一标识符
     *
     * <p>用途：
     * <ul>
     *   <li>区分不同的地图</li>
     *   <li>用于坐标转换和注册</li>
     *   <li>与MapCoordinateManager配合使用</li>
     * </ul>
     */
    private String currentMapKey;

    /**
     * 私有构造函数，防止外部实例化
     *
     * <p>使用Holder实现懒加载，避免类加载时初始化
     */
    private MapContext() {
    }

    /**
     * 获取单例实例
     *
     * <p>使用Holder实现懒加载，线程安全且高效
     *
     * @return 单例实例
     */
    public static MapContext getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 初始化地图上下文（不包含地图key）
     *
     * <p>功能描述：
     * 设置地图图像和视口尺寸，初始化基本状态参数。
     * 使用此方法不会注册地图到坐标管理器。
     *
     * <p>初始化步骤：
     * <ol>
     *   <li>设置地图图像</li>
     *   <li>获取并设置地图原始尺寸</li>
     *   <li>设置视口尺寸</li>
     * </ol>
     *
     * <p>注意事项：
     * <ul>
     *   <li>不设置currentMapKey，不注册地图</li>
     *   <li>建议使用initWithKey()方法，注册地图信息</li>
     * </ul>
     *
     * @param image 地图图像对象
     * @param w 视口宽度（像素）
     * @param h 视口高度（像素）
     */
    public void init(Image image, double w, double h) {
        this.mapImage = image;
        this.mapWidth = image.getWidth();
        this.mapHeight = image.getHeight();
        this.viewWidth = w;
        this.viewHeight = h;
    }

    /**
     * 初始化地图上下文（包含地图key）
     *
     * <p>功能描述：
     * 设置地图图像和视口尺寸，并将地图注册到坐标管理器。
     * 建议使用此方法替代init()，以便进行坐标转换。
     *
     * <p>初始化步骤：
     * <ol>
     *   <li>调用init()设置基本参数</li>
     *   <li>设置当前地图key</li>
     *   <li>将地图注册到MapCoordinateManager</li>
     * </ol>
     *
     * <p>地图注册：
     * <ul>
     *   <li>key用于唯一标识地图</li>
     *   <li>尺寸用于坐标转换</li>
     *   <li>缩放比例用于坐标精度控制</li>
     * </ul>
     *
     * @param image 地图图像对象
     * @param w 视口宽度（像素）
     * @param h 视口高度（像素）
     * @param mapKey 地图唯一标识符
     */
    public void initWithKey(Image image, double w, double h, String mapKey) {
        // 先进行基本初始化
        init(image, w, h);

        // 设置当前地图key
        this.currentMapKey = mapKey;

        // 将地图注册到坐标管理器
        // 参数：地图key、地图宽度、地图高度、JSON缩放比例、地图缩放比例
        MapCoordinateManager.getInstance().registerMap(
            mapKey,
            (int) w,
            (int) h,
            AppConfig.JSON_ZOOM,
            AppConfig.MAP_ZOOM
        );
    }

    /**
     * 更新玩家状态（位置和朝向）
     *
     * <p>功能描述：
     * 更新玩家位置坐标和朝向角度，并发布玩家位置更新事件。
     * 此方法会被频繁调用（如每帧），实时跟踪玩家移动。
     *
     * <p>更新流程：
     * <ol>
     *   <li>更新玩家坐标（playerX, playerY）</li>
     *   <li>更新玩家朝向角度（playerAngle）</li>
     *   <li>设置玩家位置初始化标志</li>
     *   <li>发布PlayerPositionEvent事件</li>
     * </ol>
     *
     * <p>事件发布：
     * <ul>
     *   <li>通过HookRegistry发布PLAYER_UPDATE事件</li>
     *   <li>所有监听此事件的钩子都会收到通知</li>
     *   <li>事件包含玩家位置信息（x, y）</li>
     * </ul>
     *
     * <p>设计意图：
     * <ul>
     *   <li>解耦玩家位置更新和业务逻辑</li>
     *   <li>支持多个模块监听玩家位置变化</li>
     *   <li>避免直接依赖，提高系统可扩展性</li>
     * </ul>
     *
     * @param x 玩家X坐标（世界坐标）
     * @param y 玩家Y坐标（世界坐标）
     * @param visualAngle 玩家朝向角度（度）
     */
    public void updatePlayerState(double x, double y, double visualAngle) {
        // 更新玩家位置
        this.playerX = x;
        this.playerY = y;

        // 更新玩家朝向角度
        this.playerAngle = visualAngle;

        // 标记玩家位置已初始化
        this.playerInitialized = true;

        // 发布事件 → 自动分发给所有监听玩家的钩子
        HookRegistry.INSTANCE.publish(
            HookEventType.PLAYER_UPDATE,
            new PlayerPositionEvent(x, y)
        );
    }

    /**
     * 获取玩家屏幕X坐标
     *
     * <p>功能描述：
     * 将玩家世界坐标转换为屏幕坐标，用于在界面上绘制玩家位置。
     *
     * <p>数学公式：
     * <pre>
     * 屏幕坐标X = 偏移量X + 玩家世界坐标X * 缩放比例
     * screenX = offsetX + playerX * scale
     * </pre>
     *
     * <p>注意事项：
     * <ul>
     *   <li>返回值可能为负数，表示玩家在当前视口外</li>
     *   <li>调用前应检查playerInitialized标志</li>
     *   <li>结果受offsetX、scale影响，需确保这些值正确</li>
     * </ul>
     *
     * @return 玩家屏幕X坐标（像素）
     */
    public double getPlayerCanvasX() {
        return offsetX + playerX * scale;
    }

    /**
     * 获取玩家屏幕Y坐标
     *
     * <p>功能描述：
     * 将玩家世界坐标转换为屏幕坐标，用于在界面上绘制玩家位置。
     *
     * <p>数学公式：
     * <pre>
     * 屏幕坐标Y = 偏移量Y + 玩家世界坐标Y * 缩放比例
     *。screenY = offsetY + playerY * scale
     * </pre>
     *
     * <p>注意事项：
     * <ul>
     *   <li>返回值可能为负数，表示玩家在当前视口外</li>
     *   <li>调用前应检查playerInitialized标志</li>
     *   <li>结果受offsetY、scale影响，需确保这些值正确</li>
     * </ul>
     *
     * @return 玩家屏幕Y坐标（像素）
     */
    public double getPlayerCanvasY() {
        return offsetY + playerY * scale;
    }

    /**
     * 执行地图缩放操作
     *
     * <p>功能描述：
     * 以指定位置为中心进行缩放，调整视口偏移量保持鼠标位置的屏幕坐标不变。
     *
     * <p>缩放算法：
     * <ol>
     *   <li>计算最小缩放比例（确保地图完整显示）</li>
     *   <li>限制缩放范围：[minScale, 15]</li>
     *   <li>计算缩放因子：newScale / oldScale</li>
     *   <li>调整偏移量，保持鼠标位置不变</li>
     *   <li>应用边界限制</li>
     * </ol>
     *
     * <p>数学原理：
     * <pre>
     * 鼠标位置不变：mouseScreen = offsetX + mouseX * scale
     * 缩放后：mouseScreen = newOffsetX + mouseX * newScale
     * 联立求解：newOffsetX = mouseScreen - mouseX * newScale
     *           newOffsetX = mouseX - (mouseX - offsetX) * (newScale / scale)
     * </pre>
     *
     * <p>参数说明：
     * <ul>
     *   <li>factor：缩放因子，>1表示放大，<1表示缩小</li>
     *   <li>mx, my：缩放中心的屏幕坐标（通常是鼠标位置）</li>
     * </ul>
     *
     * <p>缩放范围：
     * <ul>
     *   <li>最小缩放：确保地图完整显示在视口中</li>
     *   <li>最大缩放：15倍（硬编码限制）</li>
     * </ul>
     *
     * @param factor 缩放因子
     * @param mx 缩放中心的屏幕X坐标
     * @param my 缩放中心的屏幕Y坐标
     */
    public void zoom(double factor, double mx, double my) {
        // 计算最小缩放比例：确保地图完整显示在视口中
        // 取宽度和高度两个维度中较大的那个，确保两个方向都完整显示
        double minScale = Math.max(viewWidth / mapWidth, viewHeight / mapHeight);

        // 限制缩放范围：[minScale, 15]
        // 既不能太小（地图看不清），也不能太大（像素化严重）
        double newScale = Math.max(minScale, Math.min(scale * factor, 15));

        // 计算缩放因子
        double f = newScale / scale;

        // 调整偏移量，保持鼠标位置不变
        // 公式：newOffset = mousePosition - (mousePosition - oldOffset) * scaleFactor
        offsetX = mx - (mx - offsetX) * f;
        offsetY = my - (my - offsetY) * f;

        // 更新缩放比例
        scale = newScale;

        // 应用边界限制，确保视口不会超出地图边界
        ensureBounds();
    }

    /**
     * 确保视口在合理范围内（边界限制）
     *
     * <p>功能描述：
     * 限制视口偏移量，确保地图不会超出视口边界。
     * 当地图小于视口时，将地图居中显示；当地图大于视口时，限制偏移量。
     *
     * <p>边界限制逻辑：
     * <ol>
     *   <li>检查地图是否已加载</li>
     *   <li>计算地图在屏幕上的显示尺寸</li>
     *   <li>判断地图尺寸与视口尺寸的关系</li>
     *   <li>根据关系调整偏移量</li>
     * </ol>
     *
     * <p>两种情况处理：
     * <ul>
     *   <li>地图 >= 视口：限制偏移量，使地图边缘与视口边缘对齐</li>
     *   <li>地图 < 视口：将地图居中显示</li>
     * </ul>
     *
     * <p>数学公式：
     * <pre>
     * 情况1（地图 >= 视口）：
     *   offset = min(0, max(offset, viewSize - mapSize))
     *   含义：offset在[viewSize - mapSize, 0]范围内
     *
     * 情况2（地图 < 视口）：
     *   offset = (viewSize - mapSize) / 2
     *   含义：offset居中，使地图位于视口中央
     * </pre>
     *
     * <p>调用时机：
     * <ul>
     *   <li>缩放操作后</li>
     *   <li>玩家位置更新后（跟随模式）</li>
     *   <li>视口尺寸变化后</li>
     * </ul>
     */
    public void ensureBounds() {
        // 检查地图是否已加载
        if (mapImage == null) return;

        // 计算地图在屏幕上的显示尺寸
        double w = mapWidth * scale;
        double h = mapHeight * scale;

        // X轴边界限制
        // 情况1：地图宽度 >= 视口宽度，限制offsetX
        // 情况2：地图宽度 < 视口宽度，地图居中
        offsetX = (w >= viewWidth)
            ? Math.min(0, Math.max(offsetX, viewWidth - w))  // 限制范围
            : (viewWidth - w) / 2;                            // 居中显示

        // Y轴边界限制
        // 情况1：地图高度 >= 视口高度，限制offsetY
        // 情况2：地图高度 < 视口高度，地图居中
        offsetY = (h >= viewHeight)
            ? Math.min(0, Math.max(offsetY, viewHeight - h))  // 限制范围
            : (viewHeight - h) / 2;                            // 居中显示
    }

    /**
     * 内部Holder类，实现线程安全的懒加载
     *
     * <p>设计意图：
     * <ul>
     *   <li>利用JVM类加载机制保证线程安全</li>
     *   <li>Holder类在第一次调用getInstance()时才加载</li>
     *   <li>避免直接使用volatile+synchronized带来的性能开销</li>
     * </ul>
     *
     * <p>线程安全保证：
     * <ul>
     *   <li>JVM保证类加载过程是线程安全的</li>
     *   <li>Holder.INSTANCE在类加载时初始化，只初始化一次</li>
     *   <li>无需额外的同步机制</li>
     * </ul>
     */
    private static class Holder {
        /**
         * 单例实例，类加载时初始化
         */
        private static final MapContext INSTANCE = new MapContext();
    }
}
