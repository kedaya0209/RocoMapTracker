package com.luoke.app.macher.map;

import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.utils.CacheUtil;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.global.opencv_calib3d;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.*;
import org.bytedeco.opencv.opencv_features2d.FlannBasedMatcher;
import org.bytedeco.opencv.opencv_features2d.SIFT;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;

import static org.bytedeco.opencv.global.opencv_core.CV_32FC2;
import static org.bytedeco.opencv.global.opencv_core.CV_8UC4;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_GRAYSCALE;
import static org.bytedeco.opencv.global.opencv_imgproc.*;

/**
 * 基于SIFT算法的地图匹配器实现类
 *
 * <p>该类使用SIFT（尺度不变特征变换）算法实现小图在大图中的精确定位。
 * 针对Native Image环境进行了深度优化，重点解决了内存泄漏和资源管理问题。</p>
 *
 * <h3>核心特性：</h3>
 * <ul>
 *   <li>使用SIFT特征点提取和FLANN快速匹配</li>
 *   <li>支持特征缓存机制，避免重复计算大图特征</li>
 *   <li>优化的Native资源生命周期管理，防止内存泄漏</li>
 *   <li>支持多种图像格式输入（文件路径、字节数组、BufferedImage）</li>
 * </ul>
 *
 * <h3>Native资源管理策略：</h3>
 * <ul>
 *   <li>核心算法对象（SIFT、Matcher）在构造时初始化，destroy时释放</li>
 *   <li>大图特征（cachedKp2、cachedDes2）常驻内存，避免重复加载</li>
 *   <li>临时中间变量全部使用局部变量 + try-with-resources 确保帧级释放</li>
 *   <li>严禁在循环中创建Native对象，防止指针堆积</li>
 * </ul>
 *
 * <h3>性能优化点：</h3>
 * <ul>
 *   <li>特征缓存：首次提取后保存到文件，后续直接加载</li>
 *   <li>FLANN索引预构建：加速KNN匹配过程</li>
 *   <li>图像缩放：统一缩放因子减少计算量</li>
 *   <li>Lowe's Ratio Test：过滤低质量匹配点对</li>
 *   <li>RANSAC单应性矩阵计算：提高鲁棒性</li>
 * </ul>
 *
 * <h3>使用示例：</h3>
 * <pre>{@code
 * SiftMapMatcher matcher = new SiftMapMatcher();
 * matcher.init("/path/to/large_map.png");  // 初始化大图
 *
 * // 方式1：从文件匹配
 * double[][] corners = matcher.match("/path/to/small_image.png");
 *
 * // 方式2：从字节数组匹配（BGRA格式）
 * double[][] corners = matcher.match(bgraBytes, width, height);
 *
 * // 方式3：从BufferedImage匹配
 * double[][] corners = matcher.match(bufferedImage);
 *
 * matcher.destroy();  // 使用完毕后必须释放资源
 * }</pre>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class SiftMapMatcher implements MapMatcher {

    /**
     * 图像缩放因子配置
     * <p>统一缩放可以降低计算量，提高匹配速度，同时保持足够的精度</p>
     */
    private static final double SCALE_FACTOR = AppConfig.SCALE_FACTOR;

    /**
     * Lowe's Ratio Test 阈值
     * <p>用于筛选高质量匹配点对，值越小匹配越严格</p>
     */
    private static final float RATIO_THRESHOLD = AppConfig.MATCH_RATIO_THRESHOLD;

    /**
     * 最小有效匹配点数
     * <p>低于此数量的匹配点将被视为匹配失败</p>
     */
    private static final int MIN_MATCH_COUNT = AppConfig.MATCH_MIN_COUNT;

    // ==================== Native资源生命周期管理 ====================

    /**
     * SIFT特征提取器
     * <p>核心算法对象，在构造时初始化，destroy时释放</p>
     */
    private final SIFT sift;

    /**
     * FLANN快速匹配器
     * <p>基于KD树的快速近似最近邻匹配器，支持索引预构建</p>
     */
    private final FlannBasedMatcher matcher;

    // ==================== 静态大图特征缓存 ====================

    /**
     * 大图特征点向量缓存
     * <p>在init时提取并缓存，避免每次匹配时重复提取大图特征</p>
     * <p>这是Native资源，需要在destroy时显式释放</p>
     */
    private final KeyPointVector cachedKp2 = new KeyPointVector();

    /**
     * 大图特征描述符矩阵缓存
     * <p>包含所有特征点的128维描述符向量，在init时计算并缓存</p>
     * <p>这是Native资源，需要在destroy时显式释放</p>
     */
    private final Mat cachedDes2 = new Mat();

    /**
     * 空掩码矩阵
     * <p>用于SIFT特征提取时指定感兴趣区域，空掩码表示全图提取</p>
     * <p>无特殊内容，跟随类生命周期自动释放</p>
     */
    private final Mat mask = new Mat();

    // ==================== 无状态转换器（可安全复用）====================

    /**
     * Java2D到OpenCV Frame的转换器
     * <p>轻量级无状态对象，可安全复用，无需频繁创建</p>
     */
    private final Java2DFrameConverter j2dConverter = new Java2DFrameConverter();

    /**
     * Frame到OpenCV Mat的转换器
     * <p>轻量级无状态对象，可安全复用，无需频繁创建</p>
     */
    private final OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

    /**
     * 初始化状态标志
     * <p>true表示大图特征已成功加载，可以进行匹配操作</p>
     */
    private boolean isInitialized = false;

    /**
     * 构造函数
     * <p>初始化SIFT算法对象和FLANN匹配器，使用配置参数优化性能</p>
     *
     * <p>SIFT参数说明：</p>
     * <ul>
     *   <li>nFeatures: 特征点数量上限</li>
     *   <li>nOctaveLayers: 每组金字塔的层数</li>
     *   <li>contrastThreshold: 对比度阈值，过滤低对比度特征点</li>
     *   <li>edgeThreshold: 边缘阈值，过滤边缘响应过强的特征点</li>
     *   <li>sigma: 初始高斯模糊的标准差</li>
     *   <li>enable128: 是否使用128维描述符（false为64维）</li>
     * </ul>
     */
    public SiftMapMatcher() {
        // 创建SIFT特征提取器，使用配置参数优化性能
        this.sift = SIFT.create(
                AppConfig.SIFT_N_FEATURES,      // 特征点数量上限
                AppConfig.SIFT_N_OCTAVE_LAYERS, // 金字塔层级数
                AppConfig.SIFT_CONTRAST_THRESHOLD, // 对比度阈值
                AppConfig.SIFT_EDGE_THRESHOLD,   // 边缘响应阈值
                AppConfig.SIFT_SIGMA,           // 初始高斯模糊标准差
                AppConfig.SIFT_ENABLE_128       // 是否使用128维描述符
        );
        // 创建FLANN快速匹配器
        this.matcher = new FlannBasedMatcher();
    }

    /**
     * 初始化匹配器，预加载大图特征
     *
     * <p>该方法会尝试从缓存文件加载大图特征，如果缓存不存在或损坏，则重新提取特征并保存缓存。</p>
     * <p>为了性能优化，建议在应用启动时调用一次，后续的match操作会复用缓存的特征。</p>
     *
     * <h3>缓存机制说明：</h3>
     * <ul>
     *   <li>特征数据缓存：largeMapPath + ".sift.zst" (ZSTD压缩格式)</li>
     *    li>FLANN索引缓存：largeMapPath + ".sift.zst.idx"</li>
     *   <li>缓存命中可避免重复的SIFT特征提取，大幅提升初始化速度</li>
     * </ul>
     *
     * <h3>Native资源管理：</h3>
     * <ul>
     *   <li>使用try-with-resources管理大图Mat对象，确保及时释放</li>
     *   <li>缓存的特征存储在成员变量中，生命周期跟随类实例</li>
     * </ul>
     *
     * @param largeMapPath 大图的资源路径（支持classpath和外部文件）
     * @throws RuntimeException 如果无法读取大图或特征提取失败
     */
    @Override
    public void init(String largeMapPath) {
        long start = System.currentTimeMillis();
        try {
            // 构造缓存文件路径（ZSTD压缩格式）
            File cacheFile = ResourceUtils.getExternalFile(largeMapPath + ".sift.zst");
            String cachePath = cacheFile.getAbsolutePath();
            String indexPath = cachePath + ".idx"; // FLANN索引文件路径

            // 尝试从缓存加载特征（性能优化点）
            if (cacheFile.exists()) {
                log.info("检测到特征缓存，正在并发加载...");
                if (CacheUtil.loadFeatures(cachePath, cachedDes2, cachedKp2)) {
                    // 加载或构建FLANN索引
                    loadOrBuildMatcher(indexPath);
                    this.isInitialized = true;
                    return; // 缓存加载成功，直接返回
                }
                log.warn("缓存损坏，重新提取特征...");
            }

            // 缓存不存在或损坏，需要重新提取特征
            log.info("提取大图特征 (Scale: {})...", SCALE_FACTOR);

            // 优化点：使用 try-with-resources 严格管理大图内存，防止Native内存泄漏
            try (InputStream is = ResourceUtils.getResourceStream(largeMapPath);
                 Mat img2 = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE);  // 加载为灰度图
                 Mat resizedImg = new Mat()) {

                // 检查大图是否成功加载
                if (img2.empty()) throw new RuntimeException("无法读取大图: " + largeMapPath);

                // 缩放大图（性能优化：降低计算量）
                resize(img2, resizedImg, new Size((int) (img2.cols() * SCALE_FACTOR), (int) (img2.rows() * SCALE_FACTOR)));

                // 提取SIFT特征点（cachedKp2）和描述符（cachedDes2）
                // 关键：特征存储在成员变量中，供后续match操作复用
                sift.detectAndCompute(resizedImg, mask, cachedKp2, cachedDes2);

                // 保存特征到缓存文件（ZSTD压缩）
                CacheUtil.saveFeatures(cachePath, cachedDes2, cachedKp2);

                // 加载或构建FLANN索引
                loadOrBuildMatcher(indexPath);

                this.isInitialized = true;
            }
        } catch (Exception e) {
            log.error("SIFT 初始化失败", e);
        } finally {
            log.info("SIFT 初始化总耗时：{}ms", System.currentTimeMillis() - start);
        }
    }

    /**
     * 加载或构建FLANN匹配器的索引
     *
     * <p>FLANN匹配器使用KD树加速最近邻搜索。该方法会尝试从文件加载预构建的索引，
     * 如果不存在则重新训练并保存索引。索引的存在可以大幅提升KNN匹配速度。</p>
     *
     * <h3>索引构建流程：</h3>
     * <ol>
     *   <li>清空匹配器中的训练数据</li>
     *   <li>将大图特征描述符添加到匹配器</li>
     *   <li>如果索引文件存在，直接加载</li>
     *   <li>否则，训练匹配器构建KD树索引并保存</li>
     * </ol>
     *
     * @param indexPath FLANN索引文件的路径
     */
    private void loadOrBuildMatcher(String indexPath) {
        // 清空匹配器中已有的训练数据
        matcher.clear();

        // 将大图特征描述符添加到匹配器（MatVector会在try-with-resources块结束时自动释放）
        try (MatVector desVector = new MatVector(cachedDes2)) {
            matcher.add(desVector);

            File idxFile = new File(indexPath);
            if (idxFile.exists()) {
                // 索引文件存在，直接加载（性能优化点）
                log.info("加载预构建的索引文件: {}", idxFile.getName());
                matcher.read(indexPath);
            } else {
                // 索引文件不存在，需要重新训练和构建
                log.info("构建 FLANN 索引树 (这可能需要一些时间)...");
                matcher.train();  // 训练匹配器，构建KD树索引
                matcher.write(indexPath);  // 保存索引到文件
            }
        }
    }

    /**
     * 从文件路径执行小图匹配
     *
     * <p>读取指定路径的图像文件，提取特征并与大图进行匹配，返回小图在大图中的4个角点坐标。</p>
     *
     * <h3>坐标说明：</h3>
     * <ul>
     *   <li>返回4个坐标点，按顺时针或逆时针顺序排列</li>
     *   <li>坐标是基于原始大图尺寸的像素坐标（已除以缩放因子）</li>
     *   <li>如果匹配失败返回null</li>
     * </ul>
     *
     * @param smallImgPath 待匹配的小图路径
     * @return 匹配到的4个角点坐标数组，格式为[x,y][4]，失败返回null
     */
    @Override
    public double[][] match(String smallImgPath) {
        // 使用try-with-resources管理加载的图像资源
        try (InputStream is = ResourceUtils.getResourceStream(smallImgPath);
             Mat img = ImageUtil.loadToMat(is, IMREAD_GRAYSCALE)) {
            return processMat(img);
        } catch (Exception e) {
            log.error("匹配失败: {}", smallImgPath, e);
            return null;
        }
    }

    /**
     * 从BGRA格式字节数组执行匹配
     *
     * <p>该方法适用于屏幕截图或视频帧等实时图像源。字节数组为BGRA格式（4字节/像素），
     * 会先转换为灰度图再进行匹配。</p>
     *
     * <h3>格式说明：</h3>
     * <ul>
     *   <li>像素格式：BGRA（蓝、绿、红、透明度）</li>
     *   <li>每个像素占4字节</li>
     *   <li>数据顺序：从左到右、从上到下</li>
     * </ul>
     *
     * @param imageBytes BGRA格式的像素字节数组
     * @param width 图像宽度（像素）
     * @param height 图像高度（像素）
     * @return 匹配到的4个角点坐标数组，失败返回null
     */
    @Override
    public double[][] match(byte[] imageBytes, int width, int height) {
        if (imageBytes == null) return null;

        // 创建BGRA格式的Mat对象，然后转换为灰度图
        try (BytePointer ptr = new BytePointer(imageBytes);  // 包装Java数组为Native指针
             Mat bgraMat = new Mat(height, width, CV_8UC4, ptr);  // 不复制数据，直接引用
             Mat grayMat = new Mat()) {
            // 将BGRA转换为灰度图（减少数据量，提高匹配速度）
            cvtColor(bgraMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    /**
     * 从BufferedImage对象执行匹配
     *
     * <p>该方法适用于AWT/Swing等Java标准库生成的图像。需要经过两次转换：
     * BufferedImage → Frame → Mat，过程较为复杂，需要注意资源管理。</p>
     *
     * <h3>资源管理注意：</h3>
     * <ul>
     *   <li>Frame对象是Native资源，必须使用try-with-resources管理</li>
     *   <li>Mat对象也是Native资源，必须显式释放</li>
     *   <li>未使用try-with-resources可能导致严重的内存泄漏</li>
     * </ul>
     *
     * @param image 待匹配的BufferedImage对象
     * @return 匹配到的4个角点坐标数组，失败返回null
     */
    @Override
    public double[][] match(BufferedImage image) {
        if (image == null) return null;

        // 优化点：修复 Frame 转换时可能引发的隐性泄漏
        // 关键：必须使用try-with-resources管理Frame和Mat对象
        try (Frame cvFrame = j2dConverter.convert(image);  // BufferedImage转Frame（Native资源）
             Mat colorMat = matConverter.convert(cvFrame);  // Frame转Mat（Native资源）
             Mat grayMat = new Mat()) {
            if (colorMat == null || colorMat.empty()) return null;
            // 将颜色图转换为灰度图（减少数据量，提高匹配速度）
            cvtColor(colorMat, grayMat, COLOR_BGRA2GRAY);
            return processMat(grayMat);
        }
    }

    /**
     * 核心匹配处理逻辑
     *
     * <p>该方法实现了完整的SIFT匹配流程，包括特征提取、KNN匹配、Lowe's Ratio Test筛选、
     * 单应性矩阵计算和坐标变换。</p>
     *
     * <h3>匹配流程：</h3>
     * <ol>
     *   <li>缩放小图（与大图保持一致的缩放因子）</li>
     *   <li>提取小图的SIFT特征点和描述符</li>
     *   <li>使用FLANN匹配器进行KNN匹配（每个特征点找2个最近邻）</li>
     *   <li>Lowe's Ratio Test：保留高质量匹配点对</li>
     *   <li>计算单应性矩阵（使用RANSAC提高鲁棒性）</li>
     *   <li>透视变换获取小图在大图中的4个角点坐标</li>
     *   <li>坐标还原到原始大图尺寸（除以缩放因子）</li>
     * </ol>
     *
     * <h3>性能优化点：</h3>
     * <ul>
     *   <li>所有中间变量使用局部变量 + try-with-resources 确保帧级释放</li>
     *   <li>严格防止在循环中创建Native对象</li>
     *   <li>使用FloatIndexer直接操作矩阵数据，避免频繁的getter调用</li>
     * </ul>
     *
     * @param img1 待匹配的小图Mat对象（灰度图）
     * @return 匹配到的4个角点坐标数组，失败返回null
     */
    private double[][] processMat(Mat img1) {
        // 前置检查
        if (img1 == null || img1.empty() || !isInitialized) return null;

        // 优化点：所有的中间变量全改为局部变量，配合 try-with-resources 实现帧级释放
        // 这样可以确保即使发生异常，Native资源也能被及时释放
        try (Mat processedSmall = new Mat();  // 缩放后的小图
             KeyPointVector localKp1 = new KeyPointVector();  // 小图特征点
             Mat localDes1 = new Mat();  // 小图特征描述符
             DMatchVectorVector localKnnMatches = new DMatchVectorVector();  // KNN匹配结果
             DMatchVector localGoodMatches = new DMatchVector()) {  // 筛选后的高质量匹配

            // 1. 缩放小图（与大图保持一致的缩放因子，确保匹配对齐）
            resize(img1, processedSmall, new Size((int) (img1.cols() * SCALE_FACTOR), (int) (img1.rows() * SCALE_FACTOR)));

            // 2. 提取小图的SIFT特征点和描述符
            sift.detectAndCompute(processedSmall, mask, localKp1, localDes1);
            if (localDes1.empty()) return null;  // 未提取到特征点，匹配失败

            // 3. KNN匹配：每个小图特征点在大图特征中找2个最近邻
            // 第1个最近邻通常是对应点，第2个用于Lowe's Ratio Test判断匹配质量
            matcher.knnMatch(localDes1, localKnnMatches, 2);

            // 4. Lowe's Ratio Test：筛选高质量匹配点对
            // 原理：如果第1最近邻距离远小于第2最近邻距离，则认为是高质量匹配
            // 注意：内层Vector和Match对象也需要及时释放，防止内存泄漏
            for (long i = 0; i < localKnnMatches.size(); i++) {
                try (DMatchVector m = localKnnMatches.get(i)) {
                    if (m.size() >= 2) {
                        try (DMatch m1 = m.get(0);  // 第1最近邻
                             DMatch m2 = m.get(1)) {  // 第2最近邻
                            // Lowe's Ratio Test：距离比值小于阈值则保留
                            if (m1.distance() < RATIO_THRESHOLD * m2.distance()) {
                                localGoodMatches.push_back(m1);
                            }
                        }
                    }
                }
            }

            // 5. 如果有效匹配点数量达到阈值，计算坐标
            if (localGoodMatches.size() >= MIN_MATCH_COUNT) {
                return calculateCoordinates(processedSmall, localKp1, localGoodMatches);
            }
        } catch (Exception e) {
            log.error("SIFT 匹配过程异常", e);
        }
        return null;  // 匹配失败
    }

    /**
     * 计算小图在大图中的坐标
     *
     * <p>该方法基于筛选后的高质量匹配点对，计算单应性矩阵，然后通过透视变换
     * 获取小图的4个角点在大图中的坐标。</p>
     *
     * <h3>计算流程：</h3>
     * <ol>
     *   <li>从匹配点对中提取小图和大图对应的特征点坐标</li>
     *   <li>使用RANSAC算法计算单应性矩阵（提高鲁棒性，过滤离群点）</li>
     *   <li>定义小图的4个角点（左上、左下、右下、右上）</li>
     *   <li>使用单应性矩阵进行透视变换，计算在大图中的对应位置</li>
     *   <li>坐标还原到原始大图尺寸（除以缩放因子）</li>
     * </ol>
     *
     * <h3>RANSAC参数说明：</h3>
     * <ul>
     *   <li>REPROJ_THRESHOLD: 重投影误差阈值，用于判断内点和外点</li>
     *   <li>MAX_ITERS: 最大迭代次数</li>
     *   <li>CONFIDENCE: 置信度，确定迭代次数</li>
     * </ul>
     *
     * <h3>性能优化点：</h3>
     * <ul>
     *   <li>局部申请矩阵，在闭包内使用FloatIndexer直接操作数据</li>
     *   <li>关键修复：循环中产生的KeyPoint和Point2f指针必须及时释放，防止堆积</li>
     * </ul>
     *
     * @param img1 小图Mat对象（缩放后）
     * @param localKp1 小图的特征点向量
     * @param localGoodMatches 筛选后的高质量匹配点对
     * @return 匹配到的4个角点坐标数组，失败返回null
     */
    private double[][] calculateCoordinates(Mat img1, KeyPointVector localKp1, DMatchVector localGoodMatches) {
        int n = (int) localGoodMatches.size();

        // 优化点：局部申请矩阵，并在闭包内使用 FloatIndexer
        // 这样可以避免频繁的getter调用，提高性能
        try (Mat localObjPoints = new Mat(n, 1, CV_32FC2);  // 小图特征点坐标矩阵（n×2）
             Mat localScenePoints = new Mat(n, 1, CV_32FC2);  // 大图特征点坐标矩阵（n×2）
             FloatIndexer objIdx = localObjPoints.createIndexer();  // 小图坐标索引器
             FloatIndexer sceneIdx = localScenePoints.createIndexer()) {  // 大图坐标索引器

            // 填充特征点坐标矩阵
            for (long i = 0; i < n; i++) {
                // 致命漏洞修复：防止循环中DMatch、KeyPoint和Point2f指针堆积
                // 每个get()调用都会返回新的Native对象，必须及时释放
                try (DMatch m = localGoodMatches.get(i);
                     KeyPoint k1 = localKp1.get(m.queryIdx());  // 小图中的特征点
                     Point2f p1 = k1.pt();  // 特征点坐标
                     KeyPoint k2 = cachedKp2.get(m.trainIdx());  // 大图中的特征点
                     Point2f p2 = k2.pt()) {  // 特征点坐标

                    // 填充小图坐标
                    objIdx.put(i, 0, 0, p1.x());
                    objIdx.put(i, 0, 1, p1.y());
                    // 填充大图坐标
                    sceneIdx.put(i, 0, 0, p2.x());
                    sceneIdx.put(i, 0, 1, p2.y());
                }
            }

            // 7. 查找单应性矩阵 (RANSAC)
            // 单应性矩阵描述了两幅图像之间的透视变换关系
            try (Mat inliers = new Mat();  // Ransac的内点标记（中间变量也需释放）
                 Mat H = opencv_calib3d.findHomography(localObjPoints, localScenePoints, opencv_calib3d.RANSAC,
                         AppConfig.RANSAC_REPROJ_THRESHOLD, inliers, AppConfig.RANSAC_MAX_ITERS, AppConfig.RANSAC_CONFIDENCE)) {

                if (H == null || H.empty()) return null;  // 无法计算单应性矩阵

                // 定义小图的4个角点并执行透视变换
                // 角点顺序：左上、左下、右下、右上
                try (Mat localObjCorners = new Mat(4, 1, CV_32FC2);  // 小图4个角点坐标
                     Mat localSceneCorners = new Mat(4, 1, CV_32FC2);  // 变换后的大图坐标
                     FloatIndexer cIdx = localObjCorners.createIndexer()) {

                    // 设置小图的4个角点坐标（像素坐标）
                    cIdx.put(0, 0, 0, 0);
                    cIdx.put(0, 0, 1, 0);                    // 左上角 (0, 0)
                    cIdx.put(1, 0, 0, 0);
                    cIdx.put(1, 0, 1, img1.rows());        // 左下角 (0, height)
                    cIdx.put(2, 0, 0, img1.cols());
                    cIdx.put(2, 0, 1, img1.rows());        // 右下角 (width, height)
                    cIdx.put(3, 0, 0, img1.cols());
                    cIdx.put(3, 0, 1, 0);                    // 右上角 (width, 0)

                    // 使用单应性矩阵进行透视变换，计算在大图中的坐标
                    opencv_core.perspectiveTransform(localObjCorners, localSceneCorners, H);

                    // 提取变换后的坐标并还原到原始大图尺寸
                    double[][] result = new double[4][2];
                    try (FloatIndexer sIdx = localSceneCorners.createIndexer()) {
                        for (int i = 0; i < 4; i++) {
                            // 还原回原始大图尺寸坐标（除以缩放因子）
                            result[i][0] = sIdx.get(i, 0, 0) / SCALE_FACTOR;  // x坐标
                            result[i][1] = sIdx.get(i, 0, 1) / SCALE_FACTOR;  // y坐标
                        }
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("计算单应性矩阵异常", e);
            return null;
        }
    }

    /**
     * 释放Native资源
     *
     * <p>该方法释放所有Native资源，包括核心算法对象、缓存的特征数据和转换器。</p>
     * <p>在Native Image环境中，显式释放Native资源尤为重要，否则会导致内存泄漏。</p>
     *
     * <h3>释放的资源列表：</h3>
     * <ul>
     *   <li>cachedDes2：大图特征描述符矩阵</li>
     *   <li>cachedKp2：大图特征点向量</li>
     *   <li>mask：掩码矩阵</li>
     *   <li>sift：SIFT特征提取器</li>
     *   <li>matcher：FLANN匹配器</li>
     *   <li>j2dConverter：Java2D到Frame转换器</li>
     *   <li>matConverter：Frame到Mat转换器</li>
     * </ul>
     *
     * <h3>使用建议：</h3>
     * <ul>
     *   <li>在应用退出前调用此方法</li>
     *   <li>不再需要匹配器时调用此方法</li>
     *   <li>调用后此对象将不可用</li>
     * </ul>
     */
    @Override
    public void destroy() {
        // 只需释放一直存活的静态资源（生命周期跟随类实例的资源）
        if (cachedDes2 != null) cachedDes2.close();  // 释放大图特征描述符矩阵
        if (cachedKp2 != null) cachedKp2.close();  // 释放大图特征点向量
        if (mask != null) mask.close();  // 释放掩码矩阵
        if (sift != null) sift.close();  // 释放SIFT特征提取器
        if (matcher != null) matcher.close();  // 释放FLANN匹配器
        if (j2dConverter != null) j2dConverter.close();  // 释放转换器
        if (matConverter != null) matConverter.close();  // 释放转换器

        isInitialized = false;  // 标记为未初始化状态
        log.info("SIFT 静态资源已彻底释放");
    }
}
