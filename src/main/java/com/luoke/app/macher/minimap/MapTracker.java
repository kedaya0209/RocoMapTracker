package com.luoke.app.macher.minimap;

import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.processor.MiniMapProcessor;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_imgproc.Vec3fVector;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_core.convertScaleAbs;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 小地图追踪器（单例模式）
 *
 * <p>该类负责在游戏屏幕中检测和提取小地图区域。使用霍夫圆变换检测圆形小地图，
 * 提取圆形区域的像素数据供后续处理。</p>
 *
 * <h3>核心功能：</h3>
 * <ul>
 *   <li>小地图定位：检测屏幕右上角的小地图圆形区域</li>
 *   <li>区域提取：提取小地图圆形区域的像素数据</li>
 *   <li>自适应调整：自动适应不同分辨率和窗口大小</li>
 * </ul>
 *
 * <h3>检测策略：</h3>
 * <ol>
 *   <li>ROI截取：只检测屏幕右上角89%-99% x、8%-23% y的区域</li>
 *   <li>图像预处理：缩放、灰度化、对比度增强、中值滤波</li>
 *   <li>霍夫圆变换：检测圆形区域</li>
 *   <li>圆形提取：提取检测到的圆形区域的像素数据</li>
 * </ol>
 *
 * <h3>性能优化：</h3>
 * <ul>
 *   <li>单例模式：全局共享实例，避免重复创建</li>
 *   <li>ROI截取：减少检测区域，提高速度</li>
 *   <li>图像缩放：降低分辨率，加快霍夫圆变换速度</li>
 *   <li>缓存机制：窗口尺寸不变时复用检测结果</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * // 获取单例实例
 * MapTracker tracker = MapTracker.getInstance();
 *
 * // 从屏幕帧中提取小地图
 * Frame screenFrame = captureScreen();
 * CaptureFrameRecord miniMap = tracker.getMiniMapImage(screenFrame);
 *
 * if (miniMap != null) {
 *     byte[] pixels = miniMap.getPixels();
 *     // 处理小地图像素数据...
 * }
 *
 * // 窗口大小改变时重置
 * tracker.reset();
 * }</pre>
 *
 * <h3>Native资源管理：</h3>
 * <ul>
 *   <li>所有临时Mat对象使用try-with-resources管理</li>
 *   <li>确保Native资源及时释放，避免内存泄漏</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class MapTracker {

    /**
     * 单例实例（使用volatile保证线程可见性）
     * <p>采用双重检查锁模式实现线程安全的懒加载单例</p>
     */
    private static volatile MapTracker instance;

    /**
     * 上一次检测的屏幕宽度
     * <p>用于检测窗口大小是否变化，如果变化则重新定位小地图</p>
     */
    private int lastW = -1;

    /**
     * 上一次检测的屏幕高度
     * <p>用于检测窗口大小是否变化，如果变化则重新定位小地图</p>
     */
    private int lastH = -1;

    /**
     * 小地图左上角X坐标
     * <p>在成功检测小地图后设置，用于后续快速提取</p>
     */
    private int mapX;

    /**
     * 小地图左上角Y坐标
     * <p>在成功检测小地图后设置，用于后续快速提取</p>
     */
    private int mapY;

    /**
     * 小地图尺寸（宽度和高度）
     * <p>小地图是正圆形，宽度和高度相等</p>
     */
    private int mapSize;

    /**
     * 图像缩放因子
     * <p>用于降低图像分辨率，加快霍夫圆变换速度</p>
     * <p>0.5表示缩放到原始大小的50%</p>
     */
    private static final double SCALE = 0.5;

    /**
     * 私有构造函数（单例模式）
     * <p>禁止外部直接创建实例</p>
     */
    private MapTracker() {}

    /**
     * 获取单例实例（双重检查锁模式）
     *
     * <p>使用双重检查锁模式确保线程安全的懒加载单例。</p>
     * <p>第一次检查避免不必要的同步，第二次检查防止多线程创建多个实例。</p>
     *
     * <h3>线程安全性：</h3>
     * <ul>
     *   <li>使用volatile保证instance的可见性</li>
     *   <li>使用synchronized保证创建实例的原子性</li>
     *   <li>双重检查避免每次获取实例都加锁</li>
     * </ul>
     *
     * @return MapTracker单例实例
     */
    public static MapTracker getInstance() {
        // 第一次检查：快速路径，避免不必要的同步
        if (instance == null) {
            synchronized (MapTracker.class) {
                // 第二次检查：防止多线程创建多个实例
                if (instance == null) instance = new MapTracker();
            }
        }
        return instance;
    }

    // ====================== ✅ 正确读取，无花屏 ======================

    /**
     * 从屏幕帧中提取小地图图像
     *
     * <p>该方法从游戏屏幕帧中检测并提取小地图区域的像素数据。</p>
     * <p>如果窗口尺寸变化，会重新检测小地图位置；否则复用之前的检测结果。</p>
     *
     * <h3>处理流程：</h3>
     * <ol>
     *   <li>前置检查：验证输入帧有效性</li>
     *   <li>尺寸检查：如果窗口尺寸变化，重新检测小地图位置</li>
     *   <li>区域提取：从检测到的位置提取圆形小地图像素</li>
     *   <li>圆形掩码：应用圆形掩码，只保留圆形区域内的像素</li>
     * </ol>
     *
     * <h3>缓存机制：</h3>
     * <ul>
     *   <li>如果窗口尺寸不变，直接使用之前检测的mapX、mapY、mapSize</li>
     *   <li>如果窗口尺寸变化，重新调用detectStrictUpperRight()检测</li>
     *   <li>这样可以避免每次都进行霍夫圆变换，大幅提高性能</li>
     * </ul>
     *
     * <h3>圆形掩码说明：</h3>
     * <ul>
     *   <li>使用MiniMapProcessor.extractCircleMaskMiniMapBytes()提取</li>
     *   <li>圆形区域外的像素被设置为透明（或黑色）</li>
     *   <li>这样可以排除小地图周围的UI干扰</li>
     * </ul>
     *
     * @param frame 屏幕帧对象（BGRA格式）
     * @return 小地图记录对象，包含小地图像素数据、宽度和高度，失败返回null
     */
    public CaptureFrameRecord getMiniMapImage(Frame frame) {
        // 前置检查：确保输入帧有效
        if (frame == null || frame.data() == null) return null;

        // 获取当前屏幕尺寸
        int w = frame.width();
        int h = frame.height();

        // 获取像素数据（BGRA格式）
        byte[] pixels = frame.getPixels();

        // 检查窗口尺寸是否变化
        // 如果变化，需要重新检测小地图位置
        if (w != lastW || h != lastH) {
            reset();  // 重置缓存状态
            if (!detectStrictUpperRight(frame)) return null;  // 重新检测小地图位置
            lastW = w;  // 更新缓存的宽度
            lastH = h;  // 更新缓存的高度
        }

        // 提取小地图圆形区域的像素数据
        // 使用MiniMapProcessor处理圆形掩码，只保留圆形区域内的像素
        return MiniMapProcessor.extractCircleMaskMiniMapBytes(
                pixels, w, h, mapX, mapY, mapSize, mapSize
        );
    }

    /**
     * 检测屏幕右上角的小地图位置
     *
     * <p>使用霍夫圆变换检测小地图圆形区域，计算小地图的位置和尺寸。</p>
     * <p>只检测屏幕右上角区域，提高检测速度和准确性。</p>
     *
     * <h3>ROI区域说明：</h3>
     * <ul>
     *   <li>起始X：89%（小地图通常在右上角）</li>
     *   <li>起始Y：8%</li>
     *   <li>宽度：10%（99% - 89%）</li>
     *   <li>高度：15%（23% - 8%）</li>
     * </ul>
     *
     * <h3>图像预处理：</h3>
     * <ol>
     *   <li>缩放：缩放到50%，降低分辨率，加快霍夫圆变换速度</li>
     *   <li>灰度化：BGRA → 灰度图</li>
     *   <li>对比度增强：convertScaleAbs，增强圆的边缘对比度</li>
     *   <li>中值滤波：medianBlur，去除噪声，平滑图像</li>
     * </ol>
     *
     * <h3>霍夫圆变换参数：</h3>
     * <ul>
     *   <li>方法：HOUGH_GRADIENT（基于梯度的霍夫圆变换）</li>
     *   <li>累加器分辨率：1.0（与图像分辨率相同）</li>
     *   <li>最小圆心距离：180 * SCALE（避免检测到重叠的圆）</li>
     *   <li>Canny边缘检测高阈值：150</li>
     *   <li>累加器阈值：28（累加器值大于该值才认为是圆）</li>
     *   <li>最小半径：70 * SCALE</li>
     *   <li>最大半径：190 * SCALE</li>
     * </ul>
     *
     * <h3>坐标计算：</h3>
     * <ul>
     *   <li>霍夫圆变换返回的是缩放图像中的坐标</li>
     *   <li>需要除以SCALE还原到原始图像坐标</li>
     *   <li>小地图左上角坐标 = ROI起始坐标 + 圆心坐标 - 半径</li>
     * </ul>
     *
     * <h3>Native资源管理：</h3>
     * <ul>
     *   <li>所有Mat对象使用try-with-resources管理</li>
     *   <li>确保Native资源及时释放</li>
     * </ul>
     *
     * @param frame 屏幕帧对象（BGRA格式）
     * @return true表示检测成功，false表示检测失败
     */
    private boolean detectStrictUpperRight(Frame frame) {
        // 获取屏幕尺寸
        int sw = frame.width();
        int sh = frame.height();
        byte[] data = frame.getPixels();

        // 定义ROI区域（屏幕右上角）
        int roiX = (int) (sw * 0.89);          // 起始X：89%
        int roiY = (int) (sh * 0.08);          // 起始Y：8%
        int roiW = sw - roiX - ((int) (sw * 0.01)); // 宽度：10%
        int roiH = (int) (sh * 0.15);          // 高度：15%

        // 使用try-with-resources管理所有Native资源
        try (Mat screenMat = new Mat(sh, sw, CV_8UC4, new BytePointer(data));  // 原始屏幕图像
             Mat roiMat = new Mat(screenMat, new org.bytedeco.opencv.opencv_core.Rect(roiX, roiY, roiW, roiH)); // ROI图像
             Mat smallMat = new Mat();    // 缩放后的图像
             Mat grayMat = new Mat();     // 灰度图
             Mat enhancedMat = new Mat()) { // 增强后的图像

            // 1. 缩放图像（降低分辨率，加快霍夫圆变换速度）
            resize(roiMat, smallMat, new Size(0, 0), SCALE, SCALE, INTER_LINEAR);

            // 2. 转换为灰度图（霍夫圆变换只需要灰度图）
            cvtColor(smallMat, grayMat, COLOR_BGR2GRAY);

            // 3. 对比度增强（增强圆的边缘对比度，提高检测准确性）
            // 系数2.0表示对比度增强一倍，偏移15避免过暗
            convertScaleAbs(grayMat, enhancedMat, 2.0, 15);

            // 4. 中值滤波（去除噪声，平滑图像，提高霍夫圆变换的鲁棒性）
            // 核大小为3（必须是奇数）
            medianBlur(enhancedMat, enhancedMat, 3);

            // 5. 霍夫圆变换（检测圆形区域）
            try (Vec3fVector circles = new Vec3fVector()) {
                // 霍夫圆变换参数
                double minDist = 180 * SCALE;    // 最小圆心距离
                int minR = (int) (70 * SCALE);   // 最小半径
                int maxR = (int) (190 * SCALE);  // 最大半径

                // 执行霍夫圆变换
                HoughCircles(enhancedMat, circles, HOUGH_GRADIENT,
                        1, minDist, 150, 28, minR, maxR);

                // 检查是否检测到圆
                if (circles.size() > 0) {
                    // 获取第一个圆（通常只有一个圆）
                    float x = circles.get(0).get(0);  // 圆心X（缩放图像坐标）
                    float y = circles.get(0).get(1);  // 圆心Y（缩放图像坐标）
                    float r = circles.get(0).get(2);  // 半径（缩放图像坐标）

                    // 还原到原始图像坐标（除以缩放因子）
                    int cx = (int) (x / SCALE);      // 圆心X（原始坐标）
                    int cy = (int) (y / SCALE);      // 圆心Y（原始坐标）
                    int radius = (int) (r / SCALE);  // 半径（原始坐标）

                    // 计算小地图左上角坐标和尺寸
                    // 左上角 = ROI起始坐标 + 圆心坐标 - 半径
                    this.mapX = roiX + cx - radius;
                    this.mapY = roiY + cy - radius;
                    this.mapSize = radius * 2;  // 小地图是正圆形，宽度和高度相等

                    log.info("✅ 小地图稳定定位：x={} y={} size={}", mapX, mapY, mapSize);
                    return true;
                }
            }
        }
        // 检测失败（注释掉，避免日志过多）
        // log.warn("❌ 小地图检测失败");
        return false;
    }

    /**
     * 重置检测状态
     *
     * <p>清除缓存的检测结果，强制下次调用getMiniMapImage()时重新检测小地图位置。</p>
     *
     * <h3>使用场景：</h3>
     * <ul>
     *   <li>窗口大小改变时</li>
     *   <li>检测失败需要重试时</li>
     *   <li>游戏UI布局改变时</li>
     * </ul>
     */
    public void reset() {
        lastW = -1;  // 清除缓存的宽度
        lastH = -1;  // 清除缓存的高度
        // 注意：不清除mapX、mapY、mapSize，保留它们可能有用
    }
}
