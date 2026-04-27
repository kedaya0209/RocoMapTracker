package com.luoke.app.model.ocr;

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
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.List;

/**
 * ONNX文本识别模型管理器 - 高性能修正版
 */
@Slf4j
public class OnnxRecManager implements AutoCloseable {
    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;
    private final List<String> dict;

    public OnnxRecManager(String modelName) throws Exception {
        this.dict = ResourceUtils.readResourceLines(AppConfig.MODEL_DIR + AppConfig.PPOCR_KEYS);
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);

        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optEngine("OnnxRuntime")
                .optModelPath(Path.of(path))
                // 💡 针对 24/7 运行，建议限制线程数，防止 CPU 爆表导致界面卡顿
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "2")
                .optTranslator(new NoopTranslator())
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    /**
     * 执行推理
     */
    public String recognize(FloatBuffer buffer, int h, int w) throws Exception {
        // 使用 subManager 确保推理过程产生的中间变量 NDArray 被立即释放
        try (NDManager sub = model.getNDManager().newSubManager()) {
            NDArray array = sub.create(buffer, new Shape(1, 3, h, w));

            try (NDList output = predictor.predict(new NDList(array))) {
                NDArray outTensor = output.getFirst();

                // 💡 性能优化点：获取形状
                // PaddleOCR 输出通常是 [steps, num_classes] 或 [1, steps, num_classes]
                long[] shape = outTensor.getShape().getShape();
                int steps, charSize;

                if (shape.length == 3) { // [1, steps, charSize]
                    steps = (int) shape[1];
                    charSize = (int) shape[2];
                } else { // [steps, charSize]
                    steps = (int) shape[0];
                    charSize = (int) shape[1];
                }

                // 💡 关键优化：直接获取 FloatBuffer 避免 toFloatArray() 的额外拷贝开销
                // toFloatBuffer() 返回的是对 Native 内存的直接视图或高效拷贝
                FloatBuffer outBuffer = outTensor.toByteBuffer().asFloatBuffer();

                return decodeCtc(outBuffer, steps, charSize);
            }
        }
    }

    /**
     * CTC 解码 - 修正了 FloatBuffer 的读取逻辑
     */
    private String decodeCtc(FloatBuffer outBuffer, int steps, int charSize) {
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1;

        for (int i = 0; i < steps; i++) {
            int maxIdx = 0;
            // 获取当前 step 的偏移
            int offset = i * charSize;
            float maxVal = outBuffer.get(offset);

            for (int j = 1; j < charSize; j++) {
                float val = outBuffer.get(offset + j);
                if (val > maxVal) {
                    maxVal = val;
                    maxIdx = j;
                }
            }

            // CTC 解码逻辑
            if (maxIdx > 0 && maxIdx != lastIdx && maxVal > 0.45f) { // 阈值稍微调高一点可以减少误识别
                if (maxIdx - 1 < dict.size()) {
                    sb.append(dict.get(maxIdx - 1));
                }
            }
            lastIdx = maxIdx;
        }

        return sb.toString();
    }

    @Override
    public void close() {
        // 💡 严谨的关闭顺序
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }
}