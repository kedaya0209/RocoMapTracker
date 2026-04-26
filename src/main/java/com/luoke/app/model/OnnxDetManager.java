package com.luoke.app.model;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.ResourceUtils;

import java.nio.FloatBuffer;
import java.nio.file.Path;

/**
 * ONNX文本检测模型管理器
 *
 * <p>负责加载和管理文本检测ONNX模型，执行文本区域定位推理。
 * 使用DJL（Deep Java Library）加载ONNX模型，底层使用ONNX Runtime引擎。
 *
 * <p>Native资源管理：
 * <ul>
 *   <li>使用NDManager子管理器实现资源自动释放</li>
 *   <li>每个推理请求使用独立的subManager，推理完成后立即释放</li>
 *   <li>避免NDArray内存累积导致的OOM问题</li>
 * </ul>
 *
 * <p>性能优化：
 * <ul>
 *   <li>设置合理的线程数配置（interOpNumThreads=1, intraOpNumThreads=2）</li>
 *   <li>启用ONNX Runtime全量优化（optimizationLevel=ALL）</li>
 *   <li>使用NoopTranslator避免数据转换开销</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
public class OnnxDetManager implements AutoCloseable {
    /**
     * ONNX模型对象，持有模型权重和配置
     *
     * <p>注意：此对象占用大量Native内存，必须在close()方法中显式释放
     */
    private final ZooModel<NDList, NDList> model;

    /**
     * 模型预测器，用于执行推理
     *
     * <p>设计意图：
     * <ul>
     *   <li>Predictor内部持有模型会话，复用可减少初始化开销</li>
     *   <li>线程安全，可在多线程环境中共享使用</li>
     * </ul>
     */
    private final Predictor<NDList, NDList> predictor;

    /**
     * 构造函数，加载文本检测ONNX模型
     *
     * <p>模型配置说明：
     * <ul>
     *   <li>使用ONNX Runtime推理引擎，性能优于默认引擎</li>
     *   <li>interOpNumThreads=1：并行操作数设为1，避免多线程竞争</li>
     *   <li>intraOpNumThreads=2：内部运算线程数设为2，利用多核加速</li>
     *   <li>optimizationLevel=ALL：启用ONNX Runtime全量优化</li>
     *   <li>NoopTranslator：直接传递NDList，避免序列化开销</li>
     * </ul>
     *
     * @param modelName ONNX模型文件名（不包含路径前缀）
     * @throws Exception 当模型加载失败时抛出异常
     */
    public OnnxDetManager(String modelName) throws Exception {
        // 获取模型文件完整路径
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);

        // 构建模型加载条件
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)  // 输入输出类型都是NDList
                .optEngine("OnnxRuntime")               // 使用ONNX Runtime引擎
                .optModelPath(Path.of(path))            // 指定模型文件路径
                .optOption("interOpNumThreads", "1")    // 并行操作线程数
                .optOption("intraOpNumThreads", "2")    // 内部运算线程数
                .optOption("optimizationLevel", "ALL")  // 启用全量优化
                .optTranslator(new NoopTranslator())    // 使用无操作翻译器
                .build();

        // 加载模型并创建预测器
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    /**
     * 执行文本检测推理
     *
     * <p>方法执行流程：
     * <ol>
     *   <li>创建NDManager子管理器（自动管理Native资源）</li>
     *   <li>将FloatBuffer转换为NDArray张量</li>
     *   <li>调用模型进行前向推理</li>
     *   <li>将结果转换为二维热力图数组</li>
     *   <li>subManager自动释放所有NDArray资源</li>
     * </ol>
     *
     * <p>Native资源管理：
     * <ul>
     *   <li>使用try-with-resources自动管理NDManager子管理器</li>
     *   <li>所有NDArray对象在try块结束时自动释放</li>
     *   <li>确保推理后Native内存立即回收</li>
     * </ul>
     *
     * <p>内存生命周期：
     * <ul>
     *   <li>input NDArray：推理完成后立即释放</li>
     *   <li>output NDList：转换为float[]后立即释放</li>
     *   <li>float[][]：返回给调用者，由GC管理</li>
     * </ul>
     *
     * @param buffer 图像数据缓冲区，NCHW格式的FloatBuffer
     *   预期形状：[1, 3, height, width]，值范围[-1, 1]
     * @param h 图像高度
     * @param w 图像宽度
     * @return 文本热力图，二维数组表示每个位置的文本概率
     *   数组形状：[height][width]，值范围[0, 1]
     * @throws Exception 当推理失败时抛出异常
     */
    public float[][] detect(FloatBuffer buffer, int h, int w) throws Exception {
        // 使用 subManager 确保单帧推理后 NDArray 立即释放
        // 这是一个关键优化，防止Native内存累积
        try (NDManager sub = model.getNDManager().newSubManager()) {
            // 创建输入张量，形状为 [1, 3, h, w]（batch, channel, height, width）
            NDArray array = sub.create(buffer, new Shape(1, 3, h, w));

            // 执行推理，结果自动使用try-with-resources管理
            try (NDList output = predictor.predict(new NDList(array))) {
                // 获取输出张量（热力图）
                NDArray heatMapArr = output.getFirst();

                // 将NDArray转换为Java数组（从Native内存拷贝到Java堆内存）
                float[] flat = heatMapArr.toFloatArray();

                // 将一维数组转换为二维数组 [height][width]
                float[][] res = new float[h][w];
                for (int i = 0; i < h; i++) {
                    System.arraycopy(flat, i * w, res[i], 0, w);
                }
                return res;
            }
            // subManager离开作用域时，自动释放所有NDArray对象
        }
    }

    /**
     * 释放ONNX模型占用的Native资源
     *
     * <p>资源清理顺序：
     * <ol>
     *   <li>关闭预测器（释放ONNX Runtime会话）</li>
     *   <li>关闭模型（释放模型权重和配置）</li>
     * </ol>
     *
     * <p>注意：
     * <ul>
     *   <li>调用后不能再使用此对象</li>
     *   <li>多线程环境下需要确保没有正在进行的推理</li>
     * </ul>
     */
    @Override
    public void close() {
        predictor.close();
        model.close();
    }
}
