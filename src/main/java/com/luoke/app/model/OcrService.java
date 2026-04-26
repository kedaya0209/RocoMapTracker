package com.luoke.app.model;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR服务主类，负责文本检测和识别的完整流程
 *
 * <p>该类实现了AutoCloseable接口，确保Native资源资源正确释放。
 * 采用三阶段处理流程：
 * <ol>
 *   <li>图像解码：使用OpenOpenCV解码原始字节为Mat对象</li>
 *   <li>文本检测：使用ONNX模型检测文本区域位置</li>
 *   <li>文本识别：使用ONNX模型识别具体文本内容</li>
 * </ol>
 *
 * <p>性能优化策略：
 * <ul>
 *   <li>ThreadLocal缓存：减少数组分配开销</li>
 *   <li>资源自动管理：使用try-with-resources确保Native资源及时释放</li>
 *   <li>内存复用：FloatBuffer缓存避免频繁GC</li>
 * </ul>
 *
 * <p>Native资源管理：
 * <ul>
 *   <li>OpenCV Mat对象通过try-with-resources自动释放</li>
 *   <li>ONNX与其他模型在close()方法中显式关闭</li>
 *   <li>BytePointer直接访问堆外内存，避免Java-Native边界拷贝</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class OcrService implements AutoCloseable {

    /**
     * 文本识别标准高度
     * 所有文本行都会缩放到这个高度进行识别，保证模型输入的一致性
     */
    private static final int REC_STD_HEIGHT = 52;

    /**
     * 文本热力图阈值
     * 当热力图值超过此阈值时，判定该位置存在文本
     */
    private static final float TEXT_HEAT_THRESHOLD = 0.35f;

    /**
     * 水平方向扩展像素数
     * 用于文本检测后扩展文本区域边界
     */
    private static final int EXPAND_X = 6;

    /**
     * 垂直方向扩展像素数
     * 用于文本检测后扩展文本区域边界
     */
    private static final int EXPAND_Y = 4;

    /**
     * 浮点数组缓存，使用ThreadLocal实现线程隔离
     *
     * <p>设计意图：
     * <ul>
     *   <li>缓存只扩容不缩容，防止Rec阶段因行宽不一导致的频繁GC</li>
     *   <li>ThreadLocal确保多线程环境下每个线程有自己的缓存，避免竞争</li>
     *   <li>减少内存分配次数，提升OCR识别性能</li>
     * </ul>
     */
    private static final ThreadLocal<float[]> FLOAT_CACHE = new ThreadLocal<>();

    /**
     * 文本检测管理器，负责ONNX检测模型的加载和推理
     */
    private OnnxDetManager detManager;

    /**
     * 文本识别管理器，负责ONNX识别模型的加载和推理
     */
    private OnnxRecManager recManager;

    /**
     * 初始化OCR服务
     *
     * <p>此方法会加载必要的Native库（OpenOpenCV）并初始化ONNX模型。
     * 必须在使用前调用，否则会导致NullPointerException。
     *
     * @throws Exception 当模型文件加载失败或初始化出错时抛出
     */
    public void init() throws Exception {
        // 加载OpenOpenCV Native库，确保后续Mat操作可用
        Loader.load(opencv_imgproc.class);

        // 初始化检测模型（负责定位文本位置）
        this.detManager = new OnnxDetManager(AppConfig.OCR_DET_MODEL);

        // 初始化识别模型（负责识别具体文字）
        this.recManager = new OnnxRecManager(AppConfig.OCR_REC_MODEL);

        log.info("✅ OCR 服务已切换为纯 Native 处理模式");
    }

    /**
     * 对图像执行完整的OCR识别流程（检测+识别）
     *
     * <p>方法内部负责：
     * <ol>
     *   <li>将字节数组解码为OpenOpenCV Mat对象</li>
     *   <li>使用检测模型定位所有文本行</li>
     *   <li>使用识别模型识别每个文本行的内容</li>
     *   <li>过滤非中文字符和数字，返回纯净结果</li>
     * </ol>
     *
     * <p>资源管理：
     * <ul>
     *   <li>所有Mat对象通过try-with-resources自动释放</li>
     *   <li>避免Native内存泄漏</li>
     * </ul>
     *
     * @param imageBytes 图像字节数组，支持常见格式（PNG、JPEG等）
     * @return 识别到的文本列表，按从上到下的顺序排列；如果失败返回空列表
     */
    public List<String> recognizeAll(byte[] imageBytes) {
        if (imageBytes == null) return Collections.emptyList();

        // 1. 资源管理原则：谁创建，谁释放
        // 使用OpenOpenCV解码替代ImageIO，性能更优且支持更多格式
        // fullMat会在try块结束时自动调用deallocator释放Native内存
        try (Mat fullMat = opencv_imgcodecs.imdecode(new Mat(imageBytes), opencv_imgcodecs.IMREAD_COLOR)) {
            if (fullMat.empty()) return Collections.emptyList();

            // 获取原始图像尺寸
            int srcW = fullMat.cols();
            int srcH = fullMat.rows();

            // 2. 将图像尺寸对齐到32的倍数，优化ONNX推理性能
            // ONNX Runtime在某些对齐尺寸下性能更好
            int detW = align32(srcW);
            int detH = align32(srcH);

            // 3. 检测阶段：构建张量并调用检测模型
            // 使用ImageNet标准化参数：mean=[0.485, 0.456, 0.406], std=[0.229, 0.224, 0.225]
            FloatBuffer detBuffer = buildTensor(fullMat, detW, detH, 0.485f, 0.229f, 0.456f, 0.224f, 0.406f, 0.225f);
            float[][] heatMap = detManager.detect(detBuffer, detH, detW);

            // 4. 从热力图提取文本行边界框
            List<Rect> boxes = extractTextLineBoxes(heatMap, srcW, srcH);
            if (boxes.isEmpty()) return Collections.emptyList();

            // 5. 识别阶段：对每个文本行进行文字识别
            List<String> resultList = new ArrayList<>();
            for (Rect box : boxes) {
                // 抠图与识别：每个lineCrop必须显式释放，避免内存泄漏
                try (Mat lineCrop = fullMat.apply(box)) {
                    // 计算缩放后的宽度，保持长宽比
                    int recW = (int) (lineCrop.cols() * (double) REC_STD_HEIGHT / lineCrop.rows());

                    // 构建识别输入张量，使用均值标准化
                    FloatBuffer recBuffer = buildTensor(lineCrop, recW, REC_STD_HEIGHT, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
                    String text = recManager.recognize(recBuffer, REC_STD_HEIGHT, recW);

                    // 6. 过滤非目标字符：只保留中文、数字和特定符号
                    text = text.replaceAll("[^\\u4e00-\\u9fa5xX×*0-9]", "").trim();
                    if (!text.isEmpty()) resultList.add(text);
                }
            }
            return resultList;
        } catch (Exception e) {
            log.error("OCR 流程异常", e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建ONNX模型输入张量
     *
     * <p>此方法执行以下操作：
     * <ol>
     *   <li>将图像缩放到目标尺寸</li>
     *   <li>通过BytePointer直接访问堆外内存，避免Java-Native拷贝</li>
     *   <li>应用灰度转换和对比度增强</li>
     *   <li>执行均值方差标准化</li>
     *   <li>按NCHW格式排列数据</li>
     * </ol>
     *
     * <p>性能优化：
     * <ul>
     *   <li>使用ThreadLocal缓存减少数组分配</li>
     *   <li>直接指针访问避免中间拷贝</li>
     *   <li>提前计算std倒数避免重复除法</li>
     * </ul>
     *
     * @param src 源图像Mat对象
     * @param tw 目标宽度
     * @param th 目标高度
     * @param mr 红色通道均值
     * @param sr 红色通道标准差
     * @param mg 绿色通道均值
     * @param sg 绿色通道标准差
     * @param mb 蓝色通道均值
     * @param sb 蓝色通道标准差
     * @return 标准化后的FloatBuffer，按NCHW格式排列
     */
    private FloatBuffer buildTensor(Mat src, int tw, int th, float mr, float sr, float mg, float sg, float mb, float sb) {
        // 缩放图由try-with-resources管理，确保Native内存及时释放
        try (Mat resized = new Mat()) {
            // 使用双线性插值进行缩放，平衡质量和速度
            opencv_imgproc.resize(src, resized, new Size(tw, th), 0, 0, opencv_imgproc.INTER_LINEAR);

            int size = tw * th;

            // 从ThreadLocal缓存获取浮点数组，避免频繁分配
            float[] data = getSafeFloatCache(3 * size);

            // 预先计算标准差的倒数，避免循环中重复除法
            float invSr = 1.0f / sr, invSg = 1.0f / sg, invSb = 1.0f / sb;

            // 直接通过指针访问堆外内存，速度远快于BufferedImage.getRGB
            // OpenOpenCV内部使用BGR存储格式，需要正确读取顺序
            try (BytePointer ptr = resized.ptr()) {
                for (int i = 0; i < size; i++) {
                    // BGR格式读取，注意字节顺序
                    int b = ptr.get(i * 3L) & 0xFF;
                    int g = ptr.get(i * 3L + 1) & 0xFF;
                    int r = ptr.get(i * 3L + 2) & 0xFF;

                    // 使用标准灰度公式转换为灰度值
                    float gray = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f;

                    // 对比度增强：暗色区域变黑，亮色区域适度增强
                    float val = gray > 0.1f ? Math.min(1.0f, gray * 1.3f) : 0.0f;

                    // NCHW排列：Channel-Height-Width格式
                    // 将灰度值复制到三个通道（检测模型使用单通道）
                    data[i] = (val - mr) * invSr;           // R通道位置
                    data[size + i] = (val - mg) * invSg;     // G通道位置
                    data[2 * size + i] = (val - mb) * invSb; // B通道位置
                }
            }
            // 使用wrap确保DJL识别的是有界Buffer，避免越界访问
            return FloatBuffer.wrap(data, 0, 3 * size);
        }
    }

    /**
     * 从热力图中提取文本行边界框
     *
     * <p>算法逻辑：
     * <ol>
     *   <li>逐行扫描热力图，检测是否存在文本</li>
     *   <li>连续文本行合并为一个矩形区域</li>
     *   <li>边界向外扩展EXPAND_Y像素，确保包含完整文本</li>
     * </ol>
     *
     * @param heatMap 文本检测热力图，二维数组表示每个位置的文本概率
     * @param srcW 原始图像宽度，用于计算缩放比例
     * @param srcH 原始图像高度，用于计算缩放比例
     * @return 文本行矩形列表，按从上到下顺序排列
     */
    private List<Rect> extractTextLineBoxes(float[][] heatMap, int srcW, int srcH) {
        List<Rect> boxes = new ArrayList<>();
        int h = heatMap.length, w = heatMap[0].length;

        // 计算Y轴缩放比例，将热力图坐标映射回原图坐标
        float scaleY = (float) srcH / h;

        Integer startY = null; // 记录当前文本行的起始Y坐标

        // 逐行扫描热力图
        for (int y = 0; y < h; y++) {
            boolean hasText = false;

            // 检查当前行是否有文本（任意列超过阈值即认为有文本）
            for (int x = 0; x < w; x++) {
                if (heatMap[y][x] >= TEXT_HEAT_THRESHOLD) {
                    hasText = true;
                    break;
                }
            }

            // 状态机逻辑：检测文本行的开始和结束
            if (hasText && startY == null) {
                // 进入新文本行：记录起始Y坐标
                startY = y;
            } else if (!hasText && startY != null) {
                // 离开文本行：生成矩形区域
                // 计算矩形坐标，进行边界扩展
                int rectY = Math.max(0, (int) (startY * scaleY) - EXPAND_Y);
                int rectH = Math.min(srcH - rectY, (int) ((y - startY) * scaleY) + EXPAND_Y * 2);
                boxes.add(new Rect(0, rectY, srcW, rectH));
                startY = null; // 重置起始坐标
            }
        }
        return boxes;
    }

    /**
     * 获取或创建浮点数组缓存
     *
     * <p>设计意图：
     * <ul>
     *   <li>关键优化：只扩容不缩容，消除Rec阶段的数组抖动</li>
     *   <li>预留50%冗余空间，减少后续扩容次数</li>
     *   <li>ThreadLocal保证线程安全，避免锁竞争</li>
     * </ul>
     *
     * <p>不缩容的原因：文本行宽度变化较大，频繁缩容会导致GC压力
     *
     * @param size 需要的数组大小
     * @return 可用的浮点数组，保证长度 >= size
     */
    private float[] getSafeFloatCache(int size) {
        float[] cache = FLOAT_CACHE.get();

        // 如果缓存不存在或太小，则创建新数组
        // 关键优化：只扩容不缩容，消除 Rec 阶段的数组抖动
        if (cache == null || cache.length < size) {
            // 预留 50% 冗余，减少后续扩容次数
            cache = new float[size + (size >> 1)];
            FLOAT_CACHE.set(cache);
        }
        return cache;
    }

    /**
     * 将尺寸对齐到32的倍数
     *
     * <p>设计意图：
     * <ul>
     *   <li>ONNX Runtime在某些对齐尺寸下推理性能更好</li>
     *   <li>使用位运算代替取模运算，性能更优</li>
     *   <li>位运算原理：(n + 31) & ~31 等价于 ceil(n/32) * 32</li>
     * </ul>
     *
     * @param size 原始尺寸
     * @return 对齐到32倍数的尺寸
     */
    private int align32(int size) {
        return (size + 31) & ~31;
    }

    /**
     * 释放OCR服务占用的所有Native资源
     *
     * <p>资源清理顺序：
     * <ol>
     *   <li>关闭文本检测管理器</li>
     *   <li>关闭文本识别管理器</li>
     * </ol>
     *
     * <p>必须在使用完毕后调用，否则会导致Native内存泄漏
     *
     * @throws Exception 关闭过程中发生异常
     */
    @Override
    public void close() throws Exception {
        if (detManager != null) detManager.close();
        if (recManager != null) recManager.close();
    }
}
