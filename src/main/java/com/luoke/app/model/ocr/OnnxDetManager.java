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

/**
 * ONNX文本检测模型管理器 - 零拷贝优化版
 */
@Slf4j
public class OnnxDetManager implements AutoCloseable {
    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;

    public OnnxDetManager(String modelName) throws Exception {
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);

        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optEngine("OnnxRuntime")
                .optModelPath(Path.of(path))
                .optOption("interOpNumThreads", "1") // 建议设为 1，减少上下文切换
                .optOption("intraOpNumThreads", "2")
                .optOption("optimizationLevel", "ALL")
                .optTranslator(new NoopTranslator())
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    /**
     * 执行检测推理
     * @return 返回一维展平的概率数组，减少二维数组创建开销
     */
    public float[] detect(FloatBuffer buffer, int h, int w) throws Exception {
        try (NDManager sub = model.getNDManager().newSubManager()) {
            NDArray array = sub.create(buffer, new Shape(1, 3, h, w));

            try (NDList output = predictor.predict(new NDList(array))) {
                NDArray heatMapArr = output.getFirst();

                // 💡 优化 1：直接返回一维数组，不构建 float[][]
                // toFloatArray 会从 Native 拷贝到 Java 堆，这是必须的一次拷贝
                return heatMapArr.toFloatArray();
            }
        }
    }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }
}