package com.luoke.app.model.cnn;

import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.ResourceUtils;
import lombok.Getter;

import java.nio.file.Path;

/**
 * 负责 ONNX 模型的加载与 Predictor 维护
 */
@Getter
public class ArrowOnnxManager implements AutoCloseable {
    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;

    public ArrowOnnxManager(String modelName) throws Exception {
        // 获取模型外部路径
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);

        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optEngine("OnnxRuntime")
                .optModelPath(Path.of(path))
                // 优化选项：对于 64x64 小模型，单线程通常延迟更低且更稳定
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "1")
                .optOption("optimizationLevel", "ORT_ENABLE_ALL")
                .optTranslator(new NoopTranslator())
                .build();

        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    public NDManager getNDManager() {
        return model.getNDManager();
    }

    @Override
    public void close() {
        if (predictor != null) predictor.close();
        if (model != null) model.close();
    }
}