package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 摄像机上下文管理类
 *
 * <p>负责管理摄像机的视角和跟随模式，实现地图视口的自动跟随玩家功能。
 * 采用单例模式，全局唯一实例管理摄像机状态。
 *
 * <p>核心功能：
 * <ul>
 * <li>跟随模式控制：启用/禁用自动跟随玩家</li>
 * <li>跟随缩放控制：设置跟随模式下的地图缩放比例</li>
 * <li>视口更新：计算并应用视口偏移量</li>
 * </ul>
 *
 * <p>设计模式：
 * <ul>
 * <li>单例模式：全局唯一实例，使用Holder实现懒加载</li>
 * <li>观察者模式：updateViewport()观察 MapContext 变化并自动更新</li>
 * </ul>
 *
 * <p>数学原理：
 * <ul>
 * <li>视口中心计算：将玩家位置置于视口中心</li>
 * <li>偏移量计算：offsetX = center - playerX * scale</li>
 * <li>边界限制：调用 MapContext.ensureBounds()确保视口在合理范围内</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
public class CameraContext {
    /**
     * 跟随模式开关
     *
     * <p>true：摄像机自动跟随玩家移动
     * false：摄像机位置固定，可通过手动缩放和平移控制
     *
     * <p>默认值从配置文件读取：AppConfig.DEFAULT_FOLLOW_MODE
     */
    private final BooleanProperty followMode = new SimpleBooleanProperty(AppConfig.DEFAULT_FOLLOW_MODE);

    /**
     * 跟随模式下的缩放比例
     *
     * <p>设计意图：
     * <ul>
     * <li>在跟随模式下，地图使用固定的缩放比例</li>
     * <li>确保玩家始终处于合适的视野范围</li>
     * <li>避免频繁调整缩放导致的视觉抖动</li>
     * </ul>
     *
     * <p>默认值从配置文件读取：AppConfig.DEFAULT_FOLLOW_SCALE
     */
    @Getter
    @Setter
    private double followScale = AppConfig.DEFAULT_FOLLOW_SCALE;

    /**
     * 私有构造函数，防止外部实例化
     *
     * <p>使用Holder实现懒加载，避免类加载时初始化
     */
    private CameraContext() {
    }

    /**
     * 获取单例实例
     *
     * <p>使用Holder实现懒加载，线程安全且高效
     *
     * @return 单例实例
     */
    public static CameraContext getInstance() {
        return Holder.INSTANCE;
    }

    /**
     * 获取跟随模式属性对象
     * * @return BooleanProperty 对象，用于 UI 绑定
     */
    public BooleanProperty followModeProperty() {
        return followMode;
    }

    /**
     * 获取跟随模式开关状态
     * * @return true 为启用，false 为禁用
     */
    public boolean isFollowMode() {
        return followMode.get();
    }

    /**
     * 设置跟随模式开关状态
     * * @param followMode true 启用跟随，false 禁用跟随
     */
    public void setFollowMode(boolean followMode) {
        this.followMode.set(followMode);
    }

    /**
     * 更新摄像机视口
     *
     * <p>功能描述：
     * 当启用跟随模式时，自动计算视口偏移量，使玩家始终位于视口中心。
     * 禁用跟随模式或地图未加载时不执行任何操作。
     *
     * <p>算法流程：
     * <ol>
     * <li>检查地图是否已加载（mapImage != null）</li>
     * <li>检查跟随模式是否启用（followMode == true）</li>
     * <li>设置地图缩放比例为followScale</li>
     * <li>计算视口中心点坐标（cx, cy）</li>
     * <li>计算视口偏移量，使玩家位于中心</li>
     * <li>应用边界限制，确保视口在合理范围内</li>
     * </ol>
     *
     * <p>数学原理：
     * <ul>
     * <li>视口中心：cx = viewWidth / 2, cy = viewHeight / 2</li>
     * <li>玩家屏幕坐标：screenX = playerX * scale</li>
     * <li>视口偏移：offsetX = cx - screenX = cx - playerX * scale</li>
     * <li>Y轴同理：offsetY = cy - playerY * scale</li>
     * </ul>
     *
     * <p>注意事项：
     * <ul>
     * <li>玩家坐标已经包含trim处理，无需额外修正</li>
     * <li>ensureBounds()确保视口不会超出地图边界</li>
     * <li>此方法应该频繁调用（如每帧），实时更新视口</li>
     * </ul>
     *
     * <p>调用时机：
     * <ul>
     * <li>玩家位置更新时</li>
     * <li>跟随模式切换时</li>
     * <li>跟随缩放比例变化时</li>
     * <li>视口大小变化时</li>
     * </ul>
     */
    public void updateViewport() {
        // 获取地图上下文实例
        MapContext mm = MapContext.getInstance();

        // 前置检查：地图未加载或跟随模式未启用，直接返回
        if (mm.getMapImage() == null || !isFollowMode()) {
            return;
        }

        // 设置跟随模式下的缩放比例
        mm.setScale(followScale);

        // 计算视口中心点坐标
        // 视口中心是玩家位置的参照点
        double cx = mm.getViewWidth() / 2;
        double cy = mm.getViewHeight() / 2;

        // ✅ 正确跟随：玩家已经包含 trim，直接用
        // 计算视口偏移量，使玩家位置位于视口中心
        // 公式：offset = center - playerPosition * scale
        mm.setOffsetX(cx - mm.getPlayerX() * mm.getScale());
        mm.setOffsetY(cy - mm.getPlayerY() * mm.getScale());

        // 应用边界限制，确保视口不会超出地图边界
        // 当地图小于视口时，将地图居中显示
        // 当地图大于视口时，限制偏移量使视口不会超出边界
        mm.ensureBounds();
    }

    /**
     * 内部Holder类，实现线程安全的懒加载
     *
     * <p>设计意图：
     * <ul>
     * <li>利用JVM类加载机制保证线程安全</li>
     * <li>Holder类在第一次调用getInstance()时才加载</li>
     * <li>避免直接使用volatile+synchronized带来的性能开销</li>
     * </ul>
     *
     * <p>线程安全保证：
     * <ul>
     * <li>JVM保证类加载过程是线程安全的</li>
     * <li>Holder.INSTANCE在类加载时初始化，只初始化一次</li>
     * <li>无需额外的同步机制</li>
     * </ul>
     */
    private static class Holder {
        /**
         * 单例实例，类加载时初始化
         */
        private static final CameraContext INSTANCE = new CameraContext();
    }
}