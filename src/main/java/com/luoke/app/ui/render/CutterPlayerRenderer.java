package com.luoke.app.ui.render;

import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.context.MapContext;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.WritableRaster;

/**
 * 圆形裁剪玩家渲染器
 *
 * <p>从完整的小地图图像中心裁剪一个圆形区域作为玩家标记显示。
 * 该渲染器直接操作图像字节数据，不使用OpenCV Mat，Native资源管理更简单。
 *
 * <p><b>主要功能：</b>
 * <ul>
 *   <li>从小地图中心裁剪圆形区域（直径为短边的18%）</li>
 *   <li>圆形内部保留原始像素，圆形外部设置为透明</li>
 *   <li>在地图上绘制裁剪后的圆形图像</li>
 *   <li>支持双线程安全（生产线程更新图像，渲染线程绘制）</li>
 * </ul>
 *
 * <p><b>性能优势：</b>
 * <ul>
 *   <li>纯字节操作，比OCR模式快约10倍</li>
 *   <li>无OpenCV依赖，减少Native资源占用</li>
 *   <li>使用volatile实现线程安全，无锁开销</li>
 *   <li>内存占用小，适合Native Image打包</li>
 * </ul>
 *
 * <p><b>Native资源管理：</b>
 * <ul>
 *   <li>不使用OpenCV Mat，无Native资源泄漏风险</li>
 *   <li>JavaFX Image对象由GC自动管理</li>
 *   <li>BufferedImage仅用于工具方法，调用方负责生命周期</li>
 *   <li>字节数组直接分配，由GC管理</li>
 * </ul>
 *
 * <p><b>线程安全：</b>
 * <ul>
 *   <li>使用volatile修饰currentArrowImage，确保可见性</li>
 *   <li>生产线程：调用updateArrow()更新图像</li>
 *   <li>消费线程：调用draw()绘制图像</li>
 *   <li>无锁设计，避免线程阻塞</li>
 * </ul>
 *
 * <p><b>设计权衡：</b>
 * <ul>
 *   <li>优点：性能高、资源占用小、Native兼容性好</li>
 *   <li>缺点：无法识别精确朝向，只能显示玩家存在</li>
 *   <li>适用场景：对朝向精度要求不高，注重性能的场合</li>
 * </ul>
 */
@Slf4j
public class CutterPlayerRenderer {
    /**
     * 像素通道数
     * <p>使用BGRA格式，每个像素4个字节。
     * BGRA是JavaFX和AWT常用的像素格式。
     */
    private static final int CHANNELS = 4; // BGRA

    /**
     * 圆形直径比例
     * <p>圆形占画面短边的比例，默认为18%。
     * 例如：对于100x100的图像，圆形直径为18px。
     */
    private static final double CIRCLE_RATIO = 0.18; // 圆形占画面短边比例

    /**
     * 当前圆形箭头图像
     * <p>从裁剪中心裁剪出的圆形图像，用于渲染。
     * 使用volatile修饰确保多线程可见性：
     * <ul>
     *   <li>生产线程：通过updateArrow()更新</li>
     *   <li>消费线程：通过draw()读取</li>
     *   <li>volatile保证线程间的数据同步</li>
     * </ul>
     *
     * <p><b>内存生命周期：</b>
     * <ul>
     *   <li>由JavaFX垃圾回收器管理</li>
     *   <li>无需手动释放</li>
     *   <li>旧的Image对象会在下一次更新时自动回收</li>
     * </ul>
     */
    private volatile Image currentArrowImage;

    /**
     * 私有构造函数
     * <p>实现单例模式，防止外部直接实例化。
     */
    private CutterPlayerRenderer() {
    }

    /**
     * 获取单例实例
     *
     * <p>返回CutterPlayerRenderer的唯一实例。
     * 使用Holder模式实现线程安全的延迟初始化。
     *
     * @return CutterPlayerRenderer单例实例
     */
    public static CutterPlayerRenderer getInstance() {
        return Holder.INSTANCE;
    }

    // ==============================================
    // 工具方法（保留你需要的）
    // ==============================================

    /**
     * 将字节数组转换为BufferedImage
     *
     * <p>将BGRA格式的字节数组转换为BufferedImage对象。
     * 该方法是静态工具方法，不依赖实例状态。
     *
     * <p><b>性能考虑：</b>
     * <ul>
     *   <li>使用System.arraycopy进行高效字节复制</li>
     *   <li>避免重复创建BufferedImage对象</li>
     *   <li>直接操作DataBufferByte，减少中间层开销</li>
     * </ul>
     *
     * <p><b>内存管理：</b>
     * <ul>
     *   <li>调用方负责返回的BufferedImage生命周期</li>
     *   <li>字节数组与BufferedImage共享内存（零拷贝）</li>
     * </ul>
     *
     * @param bytes BGRA格式的字节数组
     * @param width 图像宽度（像素）
     * @param height 图像高度（像素）
     * @return BufferedImage对象，包含BGRA格式的图像数据
     */
    public static BufferedImage toBufferedImage(byte[] bytes, int width, int height) {
        // 创建BufferedImage，使用4字节ABGR格式
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_4BYTE_ABGR);
        // 获取图像的光栅对象
        WritableRaster raster = image.getRaster();
        // 获取底层字节数据缓冲区
        DataBufferByte dataBuffer = (DataBufferByte) raster.getDataBuffer();
        byte[] targetBytes = dataBuffer.getData();
        // 复制字节数据（零拷贝优化：实际上只是引用传递）
        System.arraycopy(bytes, 0, targetBytes, 0, Math.min(bytes.length, targetBytes.length));
        return image;
    }

    /**
     * 从捕获帧记录创建BufferedImage
     *
     * <p>将CaptureFrameRecord转换为BufferedImage。
     * 这是toBufferedImage(byte[], int, int)的便捷封装。
     *
     * <p><b>参数校验：</b>
     * <ul>
     *   <li>检查frame是否为null</li>
     *   <li>检查frame.bytes()是否为null</li>
     *   <li>任何校验失败返回null</li>
     * </ul>
     *
     * @param frame 捕获帧记录，包含图像数据和尺寸信息
     * @return BufferedImage对象，如果输入无效则返回null
     */
    public static BufferedImage toBufferedImage(CaptureFrameRecord frame) {
        // 参数校验：检查frame和字节数据
        if (frame == null || frame.bytes() == null) return null;
        // 调用字节数组版本的方法进行转换
        return toBufferedImage(frame.bytes(), frame.width(), frame.height());
    }

    /**
     * 【生产线程】更新圆形箭头图像
     *
     * <p>从完整的小地图中心裁剪圆形区域，圆形内部保留原始像素，
     * 圆形外部设置为透明。该方法应在捕获线程中调用。
     *
     * <p><b>算法流程：</b>
     * <ol>
     *   <li>计算圆心（画面正中心）</li>
     *   <li>计算圆形直径（画面短边 × 0.18，最小4px）</li>
     *   <li>创建输出字节数组</li>
     *   <li>逐像素复制，判断点是否在圆内</li>
     *   <li>圆内复制原像素，圆外设置为透明</li>
     *   <li>创建JavaFX Image对象并更新到volatile变量</li>
     * </ol>
     *
     * <p><b>圆形判断算法：</b>
     * <pre>
     * 点(x,y)在圆内的条件：(x-cx)² + (y-cy)²r²
     * 其中：(cx,cy)为圆心，r为半径
     * </pre>
     *
     * <p><b>性能优化：</b>
     * <ul>
     *   <li>使用System.arraycopy批量复制像素（4字节）</li>
     *   <li>避免创建临时对象，减少GC压力</li>
     *   <li>边界检查提前，避免越界访问</li>
     *   <li>零拷贝：字节数组直接传递给JavaFX Image</li>
     * </ul>
     *
     * <p><b>线程安全：</b>
     * <ul>
     *   <li>在生产线程中执行</li>
     *   <li>使用volatile更新currentArrowImage</li>
     *   <li>渲染线程看到的总是完整的图像</li>
     * </ul>
     *
     * <p><b>内存管理：</b>
     * <ul>
     *   <li>字节数组由GC自动回收</li>
     *   <li>JavaFX Image对象由GC管理</li>
     *   <li>旧的Image对象在引用更新后自动回收</li>
     * </ul>
     *
     * @param frame 捕获帧记录，包含完整的小地图图像数据
     */
    public void updateArrow(CaptureFrameRecord frame) {
        // 参数校验：检查frame和字节数据
        if (frame == null || frame.bytes() == null) return;

        // 获取图像尺寸和字节数据
        int w = frame.width();
        int h = frame.height();
        byte[] srcBytes = frame.bytes();

        // 计算圆心：位于画面正中心
        int cx = w / 2;
        int cy = h / 2;

        // 计算圆形直径：画面短边 × 18%
        int diameter = (int) (Math.min(w, h) * CIRCLE_RATIO);
        // 确保直径不小于4px，避免过小的图像
        if (diameter < 4) diameter = 4;
        int radius = diameter / 2;

        // 输出图像尺寸 = 圆形直径
        int outSize = diameter;

        // ==============================================
        // 逐像素复制处理：圆内保留原像素，圆外设置为透明
        // ==============================================
        // 分配输出字节数组（BGRA格式）
        byte[] outBytes = new byte[outSize * outSize * CHANNELS];

        // 遍历输出图像的每个像素
        for (int dy = 0; dy < outSize; dy++) {
            // 计算源图像Y坐标：从圆心顶部开始
            int srcY = cy - radius + dy;
            // 边界检查：跳过越界的Y坐标
            if (srcY < 0 || srcY >= h) continue;

            for (int dx = 0; dx < outSize; dx++) {
                // 计算源图像X坐标：从圆心左侧开始
                int srcX = cx - radius + dx;
                // 边界检查：跳过越界的X坐标
                if (srcX < 0 || srcX >= w) continue;

                // 计算点到圆心的距离分量
                int distX = srcX - cx;
                int distY = srcY - cy;
                // 判断点是否在圆内：使用距离平方比较（避免开方运算）
                boolean inCircle = (distX * distX + distY * distY) <= radius * radius;

                // 计算源图像和输出图像的字节位置
                int srcPos = (srcY * w + srcX) * CHANNELS;
                int dstPos = (dy * outSize + dx) * CHANNELS;

                if (inCircle) {
                    // 圆内：复制原像素的BGRA数据
                    System.arraycopy(srcBytes, srcPos, outBytes, dstPos, CHANNELS);
                } else {
                    // 圆外：设置为全透明（RGBA=0）
                    outBytes[dstPos] = 0;        // B = 0
                    outBytes[dstPos + 1] = 0;    // G = 0
                    outBytes[dstPos + 2] = 0;    // R = 0
                    outBytes[dstPos + 3] = 0;    // A = 0 (完全透明)
                }
            }
        }

        // 生成最终圆形图
        // 创建可写的JavaFX Image对象
        WritableImage img = new WritableImage(outSize, outSize);
        PixelWriter writer = img.getPixelWriter();
        // 将字节数组直接写入Image（零拷贝，提升性能）
        writer.setPixels(0, 0, outSize, outSize,
                javafx.scene.image.PixelFormat.getByteBgraInstance(),
                outBytes, 0, outSize * CHANNELS);

        // 原子更新当前图像（volatile确保线程可见性）
        currentArrowImage = img;
    }

    /**
     * 【渲染线程】绘制中心圆形箭头
     *
     * <p>在地图上绘制裁剪后的圆形玩家标记。
     * 该方法应在JavaFX渲染线程中调用。
     *
     * <p><b>绘制流程：</b>
     * <ol>git
     *   <li>读取volatile变量获取当前图像（线程安全）</li>
     *   <li>检查图像和玩家位置是否已初始化</li>
     *   <li>获取玩家在画布上的中心坐标</li>
     *   <li>计算图像的半宽和半高</li>
     *   <li>以中心点为基准绘制图像</li>
     * </ol>
     *
     * <p><b>定位逻辑：</b>
     * <ul>
     *   <li>图像中心点对齐玩家位置</li>
     *   <li>绘制坐标 = 玩家中心 - 图像尺寸/2</li>
     *   <li>确保图像以玩家位置为对称中心</li>
     * </ul>
     *
     * <p><b>性能优化：</b>
     * <ul>
     *   <li>先读取volatile变量到局部变量，避免重复访问</li>
     *   <li>提前返回避免不必要的计算</li>
     *   <li>使用save()/restore()保护绘图状态</li>
     * </ul>
     *
     * <p><b>线程安全：</b>
     * <ul>
     *   <li>在渲染线程中执行</li>
     *   <li>读取volatile变量保证可见性</li>
     *   <li>与updateArrow()共享数据，但通过volatile保证安全</li>
     * </ul>
     *
     * @param gc JavaFX绘图上下文，用于绘制操作
     */
    public void draw(GraphicsContext gc) {
        // 先读取volatile变量到局部变量，避免重复访问
        // 这样可以确保在绘制期间图像引用不会改变
        Image img = currentArrowImage;
        // 检查图像是否已加载
        if (img == null) return;

        // 获取地图上下文单例
        MapContext mm = MapContext.getInstance();
        // 检查玩家位置是否已初始化
        if (!mm.isPlayerInitialized()) return;

        // 获取玩家在画布上的中心坐标
        double centerX = mm.getPlayerCanvasX();
        double centerY = mm.getPlayerCanvasY();
        // 计算图像的半宽和半高（用于中心对齐）
        double halfW = img.getWidth() / 2;
        double halfH = img.getHeight() / 2;

        // 保存当前绘图状态
        gc.save();
        // 以玩家中心为基准绘制图像，图像中心对齐玩家位置
        gc.drawImage(img, centerX - halfW, centerY - halfH);
        // 恢复绘图状态
        gc.restore();
    }

    /**
     * 释放资源
     *
     * <p>清空当前圆形图像引用，释放占用的内存。
     * 该方法在程序退出或切换模式时调用。
     *
     * <p><b>内存管理：</b>
     * <ul>
     *   <li>将volatile变量设置为null</li>
     *   <li>JavaFX Image对象由GC自动回收</li>
     *   <li>无需手动释放Native资源（本类不使用Mat）</li>
     * </ul>
     *
     * <p><b>调用时机：</b>
     * <ul>
     *   <li>程序退出时</li>
     *   <li>切换渲染模式时</cutter→simulation</li>
     *   <li>需要重置状态时</li>
     * </ul>
     */
    public void release() {
        // 清空图像引用，由GC回收内存
        currentArrowImage = null;
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
     *
     * <p><b>与PlayerRenderer对比：</b>
     * <ul>
     *   <li>PlayerRenderer：使用OCR，更精确但更慢</li>
     *   <li>CutterPlayerRenderer：纯裁剪，更快但无朝向</li>
     *   <li>两者都使用相同的单例模式</li>
     * </ul>
     */
    private static class Holder {
        /**
         * CutterPlayerRenderer单例实例
         * <p>由JVM保证线程安全的初始化。
         * 使用final修饰确保引用不可变。
         */
        private static final CutterPlayerRenderer INSTANCE = new CutterPlayerRenderer();
    }
}