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
import java.util.List;

/**
 * ONNX文本识别模型管理器
 *
 * <p>负责加载和管理文本识别ONNX模型，执行OCR字符序列识别。
 * 使用DJL（Deep Java Library）加载ONNX模型，底层使用ONNX Runtime引擎。
 *
 * <p>识别流程：
 * <ol>
 *   <li>将图像输入识别模型，得到字符概率序列</li>
 *   <li>使用CTC（Connectionist Temporal Classification）解码算法</li>
 *   <li>将字符索引映射为实际字符</li>
 *   <li>过滤重复字符和空白字符</li>
 * </ol>
 *
 * <p>Native资源管理：
 * <ul>
 *   <li>使用NDManager子管理器实现资源自动释放</li>
 *   <li>每个推理请求使用独立的subManager，推理完成后立即释放</li>
 *   <li>避免NDArray内存累积导致的OOM问题</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
public class OnnxRecManager implements AutoCloseable {
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
     * 字符字典列表，用于将字符索引映射为实际字符
     *
     * <p>字典说明：
     * <ul>
     *   <li>索引0：空白字符（CTC解码时跳过）</li>
     *   <li>索引1~N：实际字符（中文、数字等）</li>
     *   <li>从资源文件读取：AppConfig.PPOCR_KEYS</li>
     * </ul>
     */
    private final List<String> dict;

    /**
     * 构造函数，加载文本识别ONNX模型和字符字典
     *
     * <p>模型配置说明：
     * <ul>
     *   <li>使用ONNX Runtime推理引擎，性能优于默认引擎</li>
     *   <li>interOpNumThreads=1：并行操作数设为1，避免多线程竞争</li>
     *   <li>intraOpNumThreads=2：内部运算线程数设为2，利用多核加速</li>
     *   <li>NoopTranslator：直接传递NDList，避免序列化开销</li>
     * </ul>
     *
     * @param modelName ONNX模型文件名（不包含路径前缀）
     * @throws Exception 当模型加载失败或字典读取失败时抛出异常
     */
    public OnnxRecManager(String modelName) throws Exception {
        // 加载字符字典（用于索引到字符的映射）
        this.dict = ResourceUtils.readResourceLines(AppConfig.MODEL_DIR + AppConfig.PPOCR_KEYS);

        // 获取模型文件完整路径
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);

        // 构建模型加载条件
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)  // 输入输出类型都是NDList
                .optEngine("OnnxRuntime")               // 使用ONNX Runtime引擎
                .optModelPath(Path.of(path))            // 指定模型文件路径
                .optOption("interOpNumThreads", "1")    // 并行操作线程数
                .optOption("intraOpNumThreads", "2")    // 内部运算线程数
                .optTranslator(new NoopTranslator())    // 使用无操作翻译器
                .build();

        // 加载模型并创建预测器
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    /**
     * 执行文本识别推理
     *
     * <p>方法执行流程：
     * <ol>
     *   <li>创建NDManager子管理器（自动管理Native资源）</li>
     *   <li>将FloatBuffer转换为NDArray张量</li>
     *   <li>调用模型进行前向推理，得到字符概率序列</li>
     *   <li>使用CTC解码算法将概率序列转换为文本</li>
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
     * @param buffer 图像数据缓冲区，NCHW格式的FloatBuffer
     *   预期形状：[1, 3, height, width]，值范围[-1, 1]
     * @param h 图像高度（固定为52）
     * @param w 图像宽度（根据长宽比计算）
     * @return 识别出的文本字符串，经过CTC解码和去重处理
     * @throws Exception 当推理失败时抛出异常
     */
    public String recognize(FloatBuffer buffer, int h, int w) throws Exception {
        // 使用try-with-resources自动管理NDManager子管理器
        // 确保推理后Native内存立即释放
        try (NDManager sub = model.getNDManager().newSubManager()) {
            // 创建输入张量，形状为 [1, 3, h, w]（batch, channel, height, width）
            NDArray array = sub.create(buffer, new Shape(1, 3, h, w));

            // 执行推理，结果自动使用try-with-resources管理
            try (NDList output = predictor.predict(new NDList(array))) {
                // 获取输出张量（字符概率序列）
                NDArray outTensor = output.getFirst();

                // 将NDArray转换为Java数组
                float[] flat = outTensor.toFloatArray();

                // 获取输出张量形状：[1, num_classes, sequence_length]
                long[] shape = outTensor.getShape().getShape();

                // 使用CTC解码算法将概率序列转换为文本
                return decodeCtc(flat, (int) shape[1], (int) shape[2]);
            }
        }
    }

    /**
     * CTC解码算法
     *
     * <p>CTC（Connectionist Temporal Classification）解码原理：
     * <ul>
     *   <li>模型输出的是每个时间步的字符概率分布</li>
     *   <li>选择概率最高的字符，跳过重复字符</li>
     *   <li>过滤掉空白字符（索引0）</li>
     *   <li>过滤掉低概率字符（概率 < 0.35）</li>
     * </ul>
     *
     * <p>算法步骤：
     * <ol>
     *   <li>对每个时间步，选择概率最高的字符</li>
     *   <li>如果字符与上一步相同，则跳过（去重）</li>
     *   <li>如果字符是空白或概率太低，则跳过</li>
     *   <li>将字符索引映射为实际字符</li>
     * </ol>
     *
     * @param flat 模型输出的展平数组
     *   数组排列顺序：[num_classes, sequence_length]，行优先存储
     * @param steps 序列长度（时间步数）
     * @param charSize 字符集大小（包含空白字符）
     * @return 解码后的文本字符串
     */
    private String decodeCtc(float[] flat, int steps, int charSize) {
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1; // 记录上一次选择的字符索引，用于去重

        // 遍历每个时间步
        for (int i = 0; i < steps; i++) {
            // 在当前时间步的字符概率分布中，找到概率最高的字符
            int maxIdx = 0;
            float maxVal = flat[i * charSize]; // 第一个字符的概率（空白字符）

            // 遍历所有字符，找到最大概率
            for (int j = 1; j < charSize; j++) {
                if (flat[i * charSize + j] > maxVal) {
                    maxVal = flat[i * charSize + j];
                    maxIdx = j;
                }
            }

            // CTC解码规则：
            // 1. 跳过空白字符（maxIdx == 0）
            // 2. 跳过重复字符（maxIdx == lastIdx）
            // 3. 跳过低概率字符（maxVal < 0.35）
            if (maxIdx > 0 && maxIdx != lastIdx && maxVal > 0.35f) {
                // 将字符索引映射为实际字符（索引-1，因为索引0是空白）
                if (maxIdx - 1 < dict.size()) {
                    sb.append(dict.get(maxIdx - 1));
                }
            }

            // 更新上一次选择的字符索引
            lastIdx = maxIdx;
        }

        return sb.toString();
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
