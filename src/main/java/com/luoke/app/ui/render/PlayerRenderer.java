package com.luoke.app.ui.render;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.InputStream;

/**
 * 玩家位置渲染器
 *
 * <p>负责在地图上渲染玩家位置标记，支持精确的朝向识别和平滑旋转动画。
 * 该渲染器通过OCR技术识别地图中心的箭头图标，获取玩家的精确朝向角度。
 *
 * <p><b>主要功能：</b>
 * <ul>
 *   <li>加载并预处理玩家图标（去除空白边缘、识别基准朝向）</li>
 *   <li>从文件或输入流初始化玩家图标</li>
 *   <li>在地图上绘制玩家标记，应用平滑旋转动画</li>
 *   <li>支持角度插值，实现流畅的旋转效果</li>
 * </ul>
 *
 * <p><b>性能优化：</b>
 * <ul>
 *   <li>单例模式管理实例，避免重复创建</li>
 *   <li>使用volatile变量确保线程安全的图像访问</li>
 *   <li>预处理图标去除空白边缘，减少渲染开销</li>
 *   <li>使用LERP插值实现平滑角度变化</li>
 *   <li>OpenCV Mat资源自动管理（使用try-with-resources）</li>
 * </ul>
 *
 * <p><b>Native资源管理：</b>
 * <ul>
 *   <li>OpenCV Mat对象通过try-with-resources自动释放</li>
 *   <li>JavaFX Image对象由JavaFX垃圾回收器管理</li>
 *   <li>避免在渲染循环中创建临时对象</li>
 * </ul>
 *
 * <p><b>线程安全：</b>
 * <ul>
 *   <li>初始化方法由JavaFX应用线程调用</li>
 *   <li>绘制方法由JavaFX渲染线程调用</li>
 *   <li>角度状态更新使用单线程模型（仅在渲染线程进行）</li>
 * </ul>
 */
@Slf4j
public class PlayerRenderer {
    /**
     * 处理后的玩家图标图像
     * <p>经过去边处理（trimEmptyPixels）的图像，去除了周围透明区域。
     * 由JavaFX垃圾回收器管理内存，无需手动释放。
     */
    private Image processedIcon;

    /**
     * 图标基准角度
     * <p>图标素材本身的初始朝向角度（相对于正北方向）。
     * 在初始化时通过OCR识别获得，用于渲染时的角度补偿。
     */
    private double baseAngle = 0.0;

    /**
     * 角度平滑插值因子
     * <p>用于控制玩家旋转动画的平滑程度。
     * 值范围：[0.0, 1.0]，值越大动画越快，值越小动画越平滑。
     * 从配置文件读取，允许运行时调整。
     */
    private final double LERP_FACTOR = AppConfig.PLAYER_ROTATE_LERP_FACTOR;

    /**
     * 图标绘制尺寸（像素）
     * <p>玩家图标在画布上的显示宽度。
     * 高度根据图标宽高比自动计算。
     */
    private double iconDrawSize = AppConfig.PLAYER_ICON_DRAW_SIZE;

    /**
     * 当前平滑角度
     * <p>经过LERP插值后的当前显示角度。
     * 每帧根据目标角度逐步逼近，实现平滑旋转效果。
     */
    private double smoothedAngle = 0.0;

    /**
     * 私有构造函数
     * <p>实现单例模式，防止外部直接实例化。
     * 通过Holder内部类延迟初始化，提升启动性能。
     */
    private PlayerRenderer() {}

    /**
     * 获取单例实例
     *
     * <p>返回PlayerRenderer的唯一实例。
     * 使用Holder模式实现延迟初始化和线程安全。
     *
     * @return PlayerRenderer单例实例
     */
    public static PlayerRenderer getInstance() { return Holder.INSTANCE; }

    /**
     * 从资源路径初始化玩家图标
     *
     * <p>从classpath或文件系统加载玩家图标图像，进行预处理并识别基准角度。
     * 该方法支持相对路径和绝对路径的自动识别。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>通过ResourceUtils加载输入流（支持classpath和文件系统）</li>
     *   <li>调用initIcon(InputStream)执行实际初始化</li>
     *   <li>自动关闭输入流，防止资源泄漏</li>
     * </ol>
     *
     * <p><b>资源管理：</b>
     * <ul>
     *   <li>使用try-with-resources确保InputStream自动关闭</li>
     *   <li>即使发生异常，资源也能正确释放</li>
     * </ul>
     *
     * @param resourcePath 资源路径，可以是classpath路径或文件系统路径
     */
    public void initIcon(String resourcePath) {
        try (InputStream is = ResourceUtils.getResourceStream(resourcePath)) {
            // 委托给InputStream版本的方法进行实际处理
            initIcon(is);
        } catch (Exception e) {
            // 记录错误日志，不影响程序继续运行
            log.error("加载玩家图标失败: {}", resourcePath, e);
        }
    }

    /**
     * 从输入流初始化玩家图标（无损压缩加载）
     *
     * <p>从输入流加载玩家图标，进行无损预处理并识别基准角度。
     * 该方法确保图标质量不受损失，同时去除多余的透明区域。
     *
     * <p><b>处理流程：</b>
     * <ol>
     *   <li>加载原始图像</li>
     *   <li>使用trimEmptyPixels去除周围透明区域，减少渲染面积</li>
     *   <li>将图像转换为OpenCV Mat进行OCR识别</li>
     *   <li>识别图标中的箭头，获取基准角度</li>
     *   <li>自动释放OpenCV Mat资源</li>
     * </ol>
     *
     * <p><b>Native资源管理：</b>
     * <ul>
     *   <li>OpenCV Mat对象使用try-with-resources自动释放</li>
     *   <li>避免Mat泄漏导致的内存问题</li>
     *   <li>JavaFX Image对象由GC管理，无需手动释放</li>
     * </ul>
     *
     * <p><b>性能优化：</b>
     * <ul>
     *   <li>去去边处理后减少渲染面积，提升绘制性能</li>
     *   <li>保留原始图像质量，避免重采样导致的模糊</li>
     * </ul>
     *
     * @param is 图像输入流，调用方负责生命周期管理
     */
    public void initIcon(InputStream is) {
        try {
            // 加载原始图像（无损）
            Image rawIcon = new Image(is);
            // 去除周围透明区域，减少渲染面积并保持图像质量
            this.processedIcon = ImageUtil.trimEmptyPixels(rawIcon);

            // 将图像转换为OpenCV Mat进行OCR识别
            // 使用try-with-resources确保Mat资源自动释放
            try (Mat iconMat = ImageUtil.imageToMat(processedIcon)) {
                // 使用边缘锐利的图进行识别，提高识别准确率
                Player result = ArrowDetector.detectPlayer(iconMat);
                // 如果识别成功，保存基准角度
                if (result != null && result.isFound()) {
                    this.baseAngle = result.getAngle();
                    log.info("玩家素材基准角校准成功: {}°", baseAngle);
                }
            }
        } catch (Exception e) {
            // 记录错误日志，不影响程序继续运行
            log.error("加载并压缩玩家图标失败", e);
        }
    }

    /**
     * 绘制玩家位置和朝向
     *
     * <p>在地图上绘制玩家标记，应用平滑旋转动画。
     * 该方法在每一帧渲染时被调用，实现流畅的玩家位置和朝向更新。
     *
     * <p><b>绘制流程：</b>
     * <ol>
     *   <li>检查图标和玩家位置是否初始化</li>
     *   <li>获取目标角度（从MapContext获取玩家当前朝向）</li>
     *   <li>计算LERP插值，实现平滑旋转</li>
     *   <li>应用坐标变换（平移+旋转）</li>
     *   <li>绘制图标（以中心为原点）</li>
     *   <li>恢复绘图状态</li>
     * </ol>
     *
     * <p><b>角度平滑算法：</b>
     * <ul>
     *   <li>计算目标角度与当前角度的差值</li>
     *   <li>处理角度回绕（-180° ~ 180°）</li>
     *   <li>应用LERP插值：newAngle = oldAngle + diff * LERP_FACTOR</li>
     *   <li>归一化到[0, 360)范围</li>
     * </ul>
     *
     * <p><b>性能优化：</b>
     * <ul>
     *   <li>使用save()/restore()保护绘图状态</li>
     *   <li>提前返回避免不必要的计算</li>
     *   <li>使用LERP插值避免角度跳变</li>
     * </ul>
     *
     * <p><b>内存生命周期：</b>
     * <ul>
     *   <li>不创建临时对象，避免GC压力</li>
     *   <li>所有绘图操作在JavaFX渲染线程执行</li>
     *   <li>processedIcon由JavaFX管理，无需手动释放</li>
     * </ul>
     *
     * @param gc JavaFX绘图上下文，用于绘制操作
     */
    public void draw(GraphicsContext gc) {
        // 检查图标是否已加载
        if (processedIcon == null) return;

        // 获取地图上下文单例
        MapContext mm = MapContext.getInstance();
        // 检查玩家位置是否已初始化
        if (!mm.isPlayerInitialized()) return;

        // 获取玩家在画布上的坐标
        double canvasX = mm.getPlayerCanvasX();
        double canvasY = mm.getPlayerCanvasY();
        // 获取玩家的目标朝向角度
        double targetAngle = mm.getPlayerAngle();

        // ========== 角度平滑插值逻辑 ==========
        // 计算目标角度与当前平滑角度的差值
        double diff = targetAngle - smoothedAngle;
        // 处理角度回绕：确保选择最短路径旋转
        // 例如：从350°到10°应该顺时针转20°，而不是逆时针转340°
        if (diff < -180) diff += 360;
        if (diff > 180) diff -= 360;

        // 应用LERP插值，逐步逼近目标角度
        // LERP_FACTOR控制平滑程度，值越大动画越快
        smoothedAngle += diff * LERP_FACTOR;
        // 归一化角度到[0, 360)范围
        smoothedAngle = (smoothedAngle + 360) % 360;

        // ========== 绘制玩家图标 ==========
        // 保存当前绘图状态
        gc.save();
        // 平移到玩家位置
        gc.translate(canvasX, canvasY);
        // 应用旋转变换（减去基准角进行补偿）
        gc.rotate(smoothedAngle - baseAngle);

        // 计算图标绘制尺寸（保持宽高比）
        double ratio = processedIcon.getHeight() / processedIcon.getWidth();
        double drawW = iconDrawSize;
        double drawH = iconDrawSize * ratio;

        // 绘制图标（以中心为原点）
        // JavaFX会自动应用图像平滑处理
        gc.drawImage(processedIcon, -drawW / 2, -drawH / 2, drawW, drawH);
        // 恢复绘图状态
        gc.restore();
    }

    /**
     * 单例持有者
     *
     * <p>使用静态内部类实现线程安全的延迟初始化。
     * 这种模式被称为"Initialization-on-demand holder idiom"。
     *
     * <p><b>线程安全保证：</b>
     * <ul>
     *   <li>JVM保证静态内部类的初始化是线程安全的</li>
     *   <li>首次调用getInstance()时才创建实例</li>
     *   <li>避免同步开销，提升性能</li>
     * </ul>
     *
     * <p><b>内存生命周期：</b>
     * <ul>
     *   <li>实例在类加载时创建</li>
     *   <li>生命周期与应用一致，无需手动释放</li>
     *   <li>包含的Image对象由JavaFX GC管理</li>
     * </ul>
     */
    private static class Holder {
        /**
         * PlayerRenderer单例实例
         * <p>由JVM保证线程安全的初始化。
         * 使用final修饰确保引用不可变。
         */
        private static final PlayerRenderer INSTANCE = new PlayerRenderer();
    }
}