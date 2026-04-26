package com.luoke.app.map.loader;

import com.luoke.app.utils.ResourceUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高性能图片加载器
 *
 * <p>该类专门用于加载和缓存应用中的图标资源，提供高性能的图片加载能力。</p>
 * <p>主要功能包括：</p>
 * <ul>
 *   <li>图片资源的自动缩放和压缩</li>
 *   <li>透明背景的正确处理（避免变白问题）</li>
 *   <li>使用软引用缓存，防止内存溢出</li>
 *   <li>支持高质量的平滑插值缩放</li>
 * </ul>
 *
 * <h3>核心特性：</h3>
 * <ul>
 *   <li><strong>软引用缓存</strong>：使用SoftReference缓存已加载的图片，
 *       当内存紧张时可以被GC自动回收，防止长时间运行导致OOM</li>
 *   <li><strong>透明背景处理</strong>：正确处理PNG等透明图片，
 *       避免在缩放或截图时背景变白的问题</li>
 *   <li><strong>高质量缩放</strong>：开启平滑插值，实现无损观感的缩放效果</li>
 *   <li><strong>自动压缩</strong>：所有图标统一缩放到指定尺寸，减少内存占用</li>
 *   <li><strong>线程安全</strong>：使用ConcurrentHashMap保证并发访问的安全性</li>
 * </ul>
 *
 * <h3>Native资源管理：</h3>
 * <p>JavaFX的Image对象底层使用Native内存，需要注意：</p>
 * <ul>
 *   <li>Native内存不会自动被Java的GC回收</li>
 *   <li>需要及时释放不再使用的Image对象</li>
 *   <li>使用软引用缓存可以在内存紧张时被GC回收，间接释放Native资源</li>
 *   <li>切换大型地图时建议手动清理缓存</li>
 * </ul>
 *
 * <h3>内存生命周期：</h3>
 * <ul>
 *   <li>图片加载后存入缓存，使用SoftReference包装</li>
 *   <li>内存充足时：图片可以长期存在于缓存中，提高加载速度</li>
 *   <li>内存紧张时：SoftReference会被GC回收，缓存失效，需要重新加载</li>
 *   <li>可以手动调用clearCache()清理缓存，释放所有图片资源</li>
 * </ul>
 *
 * <h3>性能优化：</h3>
 * <ul>
 *   <li>使用缓存减少重复加载，提高响应速度</li>
 *   <li>在加载流时直接缩放，利用JavaFX底层的原生缩放，效率最高</li>
 *   <li>统一尺寸压缩，减少内存占用</li>
 *   <li>使用ConcurrentHashMap支持高并发访问</li>
 * </ul>
 *
 * <h3>设计模式：</h3>
 * <ul>
 *   <li>单例模式：确保全局只有一个ImageLoader实例</li>
 *   <li>缓存模式：使用SoftReference实现自动失效的缓存</li>
 *   <li>策略模式：提供两种缩放方式（加载时缩放、手动缩放）</li>
 * </ul>
 *
 * @author 可达鸭
 * @since 1.0.0
 */
public class ImageLoader {

    // ==================== 单例实例 ====================

    /**
     * 单例实例，使用饿汉式初始化
     *
     * <p>饿汉式单例的优点：</p>
     * <ul>
     *   <li>实现简单，无需加锁</li>
     *   <li>线程安全，由JVM保证</li>
     *   <li>性能好，无额外开销</li>
     * </ul>
     *
     * <p>饿汉式单例的缺点：</p>
     * <ul>
     *   <li>类加载时就创建实例，延迟初始化的优势丢失</li>
     *   <li>如果初始化耗时较长，会影响应用启动速度</li>
     * </ul>
     *
     * <p>对于ImageLoader，初始化开销很小，使用饿汉式是合适的选择。</p>
     */
    private static final ImageLoader INSTANCE = new ImageLoader();

    // ==================== 压缩参数配置 ====================

    /**
     * 图标最大尺寸（像素）
     *
     * <p>所有加载的图标都会被缩放到不超过这个尺寸。</p>
     * <p>设置统一尺寸的好处：</p>
     * <ul>
     *   <li>减少内存占用，提高应用性能</li>
     *   <li>保证UI显示的一致性</li>
     *   <li>避免大图加载导致的内存问题</li>
     * </ul>
     *
     * <p>32x32像素对于大多数图标来说是合适的尺寸，</p>
     * <p>既保证了清晰度，又控制了内存占用。</p>
     */
    private static final int MAX_ICON_SIZE = 32;

    /**
     * 是否开启平滑插值缩放
     *
     * <p>设置为true可以实现无损观感的缩放效果：</p>
     * <ul>
     *   <li>使用双线性插值算法</li>
     *   <li>避免锯齿和模糊</li>
     *   <li>提升视觉质量</li>
     * </ul>
     *
     * <p>缺点是计算量稍大，但对于图标这种小图片影响不大。</p>
     */
    private static final boolean SMOOTH_SCALE = true; // 开启平滑插值，实现无损观感

    // ==================== 缓存管理 ====================

    /**
     * 图片缓存，使用软引用和并发HashMap
     *
     * <p>数据结构说明：</p>
     * <ul>
     *   <li>键：资源路径（String），标识图片的唯一性</li>
     *   <li>值：SoftReference<Image>，使用软引用包装图片对象</li>
     *   <li>使用ConcurrentHashMap保证线程安全</li>
     * </ul>
     *
     * <p>软引用特性：</p>
     * <ul>
     *   <li>内存充足时：引用的图片对象会长期存在</li>
     *   <li>内存紧张时：GC可以回收SoftReference引用的对象</li>
     *   <li>适合用于缓存：提供缓存功能，又不会导致OOM</li>
     * </ul>
     *
     * <p>防止20小时连续开发导致的堆内存溢出(OOM)：</p>
     * <ul>
     *   <li>长期运行的Java应用会积累大量对象</li>
     *   <li>普通缓存（强引用）会阻止GC回收这些对象</li>
     *   <li>使用软引用可以让GC在内存紧张时自动回收缓存</li>
     *   <li>这特别适合IDE开发场景，IDE本身占用大量内存</li>
     * </ul>
     *
     * <p>线程安全性：</p>
     * <ul>
     *   <li>ConcurrentHashMap是线程安全的集合</li>
     *   <li>支持高并发读写操作</li>
     *   <li>性能接近HashMap，无需加锁</li>
     * </ul>
     */
    private final Map<String, SoftReference<Image>> imageCache = new ConcurrentHashMap<>();

    // ==================== 构造方法 ====================

    /**
     * 私有构造方法，防止外部实例化
     *
     * <p>该类设计为单例模式，只能通过getInstance()获取实例。</p>
     * <p>私有构造方法确保：</p>
     * <ul>
     *   <li>外部无法通过new关键字创建实例</li>
     *   <li>全局只存在一个ImageLoader实例</li>
     *   <li>所有请求共享同一个缓存和配置</li>
     * </ul>
     */
    private ImageLoader() {
    }

    // ==================== 公共API方法 ====================

    /**
     * 获取ImageLoader单例实例
     *
     * <p>该方法返回全局唯一的ImageLoader实例。</p>
     * <p>使用单例的好处：</p>
     * <ul>
     *   <li>共享缓存，提高资源利用率</li>
     *   <li>统一配置，便于管理和维护</li>
     *   <li>减少内存占用，避免重复创建</li>
     * </ul>
     *
     * <p>调用示例：</p>
     * <pre>{@code
     * // 获取单例实例
     * ImageLoader loader = ImageLoader.getInstance();
     *
     * // 加载图标
     * Image icon = loader.loadScaledIcon("/icons/marker.png");
     * }</pre>
     *
     * @return ImageLoader单例实例
     */
    public static ImageLoader getInstance() {
        return INSTANCE;
    }

    /**
     * 加载并进行高质量缩放图标
     *
     * <p>该方法从资源路径加载图片，并缩放到指定尺寸。</p>
     * <p>执行流程：</p>
     * <ol>
     *   <li>检查缓存中是否已有该图片，如果有则直接返回</li>
     *   <li>从资源路径打开输入流</li>
     *   <li>在加载流时直接缩放（利用JavaFX底层的原生缩放）</li>
     *   <li>将缩放后的图片存入缓存</li>
     *   <li>返回图片对象</li>
     * </ol>
     *
     * <p>缓存机制：</p>
     * <ul>
     *   <li>使用SoftReference包装图片对象</li>
     *   <li>内存紧张时可以被GC回收</li>
     *   <li>缓存失效后会自动重新加载</li>
     * </ul>
     *
     * <p>缩放策略：</p>
     * <ul>
     *   <li>目标尺寸：MAX_ICON_SIZE（32像素）</li>
     *   <li>保持宽高比：true</li>
     *   <li>平滑缩放：true（SMOOTH_SCALE）</li>
     *   <li>自动透明度处理：true</li>
     * </ul>
     *
     * <p>性能优化：</p>
     * <ul>
     *   <li>在加载流时缩放，避免二次处理</li>
     *   <li>使用缓存减少重复加载</li>
     *   <li>使用try-with-resources确保流被正确关闭</li>
     * </ul>
     *
     * <p>错误处理：</p>
     * <ul>
     *   <li>图片加载失败：返回null，打印错误信息</li>
     *   <li>流打开失败：返回null，打印异常堆栈</li>
     * </ul>
     *
     * <h3>Native资源管理：</h3>
     * <ul>
     *   <li>Image对象底层使用Native内存</li>
     *   <li>使用软引用缓存，内存紧张时可以被GC回收</li>
     *   <li>GC回收软引用后，Native资源会被自动释放</li>
     *   <li>这解决了长时间运行导致的Native内存泄漏问题</li>
     * </ul>
     *
     * @param resourcePath 资源路径，支持classpath路径和文件路径
     *                    例如："/icons/marker.png" 或 "file:///path/to/icon.png"
     * @return 缩放后的Image对象，如果加载失败则返回null
     *         返回的Image对象保持透明背景，可以直接用于UI显示
     *
     * @see #clearCache() 清理缓存的方法
     * @see #resizeImage(Image, int) 手动缩放图片的方法
     */
    public Image loadScaledIcon(String resourcePath) {
        // 1. 检查缓存
        // 使用ConcurrentHashMap的get方法获取软引用
        // 线程安全，无需加锁
        SoftReference<Image> ref = imageCache.get(resourcePath);
        if (ref != null) {
            // 尝试从软引用中获取实际的图片对象
            Image cached = ref.get();
            if (cached != null) {
                // 缓存命中，直接返回图片对象
                // 避免重复加载，提高性能
                return cached;
            }
            // 如果cached为null，说明软引用已被GC回收
            // 继续执行后续的加载逻辑
        }

        // 缓存未命中，需要加载图片
        try (var ins = ResourceUtils.getResourceStream(resourcePath)) {
            // 2. 直接在加载流时缩放 (JavaFX 底层原生缩放，效率最高)
            // 构造函数参数说明：
            // - ins: 输入流，提供图片数据的来源
            // - MAX_ICON_SIZE: 目标宽度，不超过32像素
            // - MAX_ICON_SIZE: 目标高度，不超过32像素
            // - true: 保持宽高比，图片不会被拉伸变形
            // - SMOOTH_SCALE: 使用平滑插值，实现高质量缩放
            //
            // 性能优势：
            // - 在加载流时缩放，避免二次处理
            // - JavaFX底层使用原生代码进行缩放，效率最高
            // - 自动处理透明度，保持背景透明
            Image result = new Image(ins, MAX_ICON_SIZE, MAX_ICON_SIZE, true, SMOOTH_SCALE);

            // 检查图片是否加载成功
            if (result.isError()) {
                // 图片加载失败，可能是格式不支持或数据损坏
                System.err.println("图片加载失败: " + resourcePath);
                return null;
            }

            // 3. 放入缓存
            // 使用SoftReference包装图片对象，实现自动失效的缓存
            // 内存紧张时，GC可以回收这个软引用，释放Native内存
            // 这解决了长时间运行导致的内存泄漏问题
            imageCache.put(resourcePath, new SoftReference<>(result));

            // 返回缩放后的图片对象
            return result;

        } catch (Exception e) {
            // 捕获所有可能的异常（IO异常、解析异常等）
            // 打印异常堆栈，便于问题排查
            e.printStackTrace();

            // 返回null表示加载失败
            return null;
        }
    }

    /**
     * 【备用方法】手动对已有Image进行高质量缩放并保持透明
     *
     * <p>该方法用于对已加载的Image对象进行缩放处理。</p>
     * <p>通常场景下应优先使用loadScaledIcon()方法，该方法仅作为备用。</p>
     *
     * <p>执行流程：</p>
     * <ol>
     *   <li>检查输入图片的有效性</li>
     *   <li>如果图片尺寸已符合要求，直接返回</li>
     *   <li>计算缩放比例和目标尺寸</li>
     *   <li>使用ImageView进行缩放操作</li>
     *   <li>设置透明背景，避免背景变白</li>
     *   <li>生成快照并返回结果</li>
     * </ol>
     *
     * <p>解决背景变白问题：</p>
     * <p>使用ImageView.snapshot()方法时，默认背景色是白色（不透明）。</p>
     * <p>对于透明图片，这会导致背景变白，失去透明效果。</p>
     * <p>解决方案：</p>
     * <ul>
     *   <li>设置SnapshotParameters的fill属性为Color.TRANSPARENT</li>
     *   <li>这样快照的背景就是透明的</li>
     *   <li>透明图片的透明部分保持透明效果</li>
     * </ul>
     *
     * <p>高质量缩放：</p>
     * <ul>
     *   <li>开启平滑插值（setSmooth(true)）</li>
     *   <li>使用双线性插值算法</li>
     *   <li>避免锯齿和模糊，提升视觉质量</li>
     * </ul>
     *
     * <p>适用场景：</p>
     * <ul>
     *   <li>需要缩放已加载的Image对象</li>
     *   <li>需要保留原始图片，生成多个不同尺寸的版本</li>
     *   <li>需要特殊的缩放处理（非标准缩放）</li>
     * </ul>
     *
     * <p>注意事项：</p>
     * <ul>
     *   <li>该方法不会修改原始图片，返回的是新图片</li>
     *   <li>需要调用方管理返回图片的生命周期</li>
     *   <li>性能不如loadScaledIcon()，尽量避免频繁调用</li>
     * </ul>
     *
     * <h3>Native资源管理：</h3>
     * <ul>
     *   <li>ImageView和WritableImage都使用Native内存</li>
     *   <li>方法调用会创建新的Native资源</li>
     *   <li>调用方需要确保返回的Image对象被正确释放</li>
     *   <li>对于频繁使用的缩放图片，建议使用loadScaledIcon()的缓存</li>
     * </ul>
     *
     * @param original 原始图片对象，不能为null
     * @param maxSize 最大尺寸（像素），图片会被缩放到不超过这个尺寸
     *                宽度和高度都会被缩放，保持宽高比
     * @return 缩放后的Image对象，如果输入无效则返回原始图片
     *         返回的图片保持透明背景，不会出现背景变白的问题
     *
     * @see #loadScaledIcon(String) 加载并缩放图标的方法
     */
    public Image resizeImage(Image original, int maxSize) {
        // 检查输入图片的有效性
        // 如果图片为null或加载失败，直接返回
        if (original == null || original.isError()) return original;

        // 检查是否需要缩放
        // 如果图片尺寸已经小于或等于目标尺寸，直接返回原图
        // 避免不必要的缩放操作，提高性能
        if (original.getWidth() <= maxSize && original.getHeight() <= maxSize) return original;

        // 计算缩放比例
        // 保持宽高比，选择较小的缩放比例，确保图片不超过目标尺寸
        double scale = Math.min((double) maxSize / original.getWidth(),
                               (double) maxSize / original.getHeight());

        // 计算目标尺寸
        // 使用Math.max(1, ...)确保尺寸至少为1像素
        int targetW = (int) Math.max(1, original.getWidth() * scale);
        int targetH = (int) Math.max(1, original.getHeight() * scale);

        // 创建ImageView并设置原始图片
        // ImageView是JavaFX的图片显示控件，支持缩放、旋转等变换
        ImageView imageView = new ImageView(original);

        // 设置显示尺寸
        // ImageView会自动根据这些尺寸缩放图片
        imageView.setFitWidth(targetW);
        imageView.setFitHeight(targetH);

        // 保持宽高比
        // 设置为true后，图片会被缩放以适应目标尺寸，但不会变形
        imageView.setPreserveRatio(true);

        // 开启平滑缩放（抗锯齿）
        // 这是核心设置，实现高质量缩放的关键
        // 使用双线性插值算法，避免锯齿和模糊
        imageView.setSmooth(true); // 核心：开启抗锯齿

        // 关键：解决背景变白问题
        // SnapshotParameters用于配置快照的各种参数
        SnapshotParameters params = new SnapshotParameters();

        // 设置快照填充色为透明
        // 这是最关键的设置，确保透明图片的背景保持透明
        // 默认值是Color.WHITE（不透明白色），会导致透明背景变白
        params.setFill(Color.TRANSPARENT); // 设置快照填充色为透明

        // 创建可写图片对象，用于存储缩放后的结果
        // WritableImage是Image的子类，可以修改像素数据
        WritableImage output = new WritableImage(targetW, targetH);

        // 生成快照并返回结果
        // snapshot方法会根据ImageView的当前状态生成快照
        // 结果是一个新的Image对象，背景是透明的
        return imageView.snapshot(params, output);
    }

    /**
     * 显式清理缓存
     *
     * <p>该方法会清空所有缓存的图片引用，释放相关资源。</p>
     * <p>清理缓存的时机：</p>
     * <ul>
     *   <li>切换大型地图时：释放旧地图的图片资源</li>
     *   <li>应用进入后台时：释放资源，降低内存占用</li>
   *   <li>收到低内存警告时：主动释放缓存</li>
     *   <li>测试或调试时：重置应用状态</li>
     * </ul>
     *
     * <p>清理效果：</p>
     * <ul>
     *   <li>所有SoftReference引用都会被清除</li>
     *   <li>关联的Image对象可以被GC回收</li>
     *   <li>底层Native资源会被释放</li>
     *   <li>后续加载会重新从资源加载图片</li>
     * </ul>
     *
     * <h3>Native资源管理：</h3>
     * <ul>
     *   <li>清除SoftReference后，Image对象可以被GC回收</li>
     *   <li>GC回收Image对象时，会释放底层Native资源</li>
     *   <li>这是一种主动的内存管理策略</li>
     *   <li>适合在内存压力大的场景使用</li>
     * </ul>
     *
     * <p>调用示例：</p>
     * <pre>{@code
     * // 切换地图前清理缓存
     * ImageLoader.getInstance().clearCache();
     *
     * // 加载新地图
     * loadNewMap();
     * }</pre>
     *
     * <p>注意事项：</p>
     * <ul>
     *   <li>清理后需要重新加载图片，会有短暂的加载延迟</li>
     *   <li>如果图片资源文件被删除或移动，重新加载会失败</li>
     *   <li>方法调用是线程安全的，可以被多个线程同时调用</li>
     * </ul>
     */
    public void clearCache() {
        // 清空缓存Map，移除所有键值对
        // ConcurrentHashMap的clear方法是线程安全的
        // 调用后，所有SoftReference引用都会被清除
        // Image对象可以被GC回收，Native资源被释放
        imageCache.clear();
    }

    // ==================== 未来的扩展可能 ====================

    /**
     * 未来可以考虑添加的功能：
     *
     * <ul>
     *   <li>支持异步加载：使用后台线程加载图片，避免阻塞UI</li>
     *   <li>支持预加载：提前加载常用图片，提高响应速度</li>
     *   <li>支持多尺寸缓存：缓存同一图片的多个尺寸版本</li>
     *   <li>支持LRU缓存：限制缓存数量，自动淘汰最久未使用的图片</li>
     *   <li>支持图片格式转换：自动将大图转换为WebP等高效格式</li>
     *   <li>支持进度回调：提供加载进度信息</li>
     * </ul>
     */
}
