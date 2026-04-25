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

public class OnnxDetManager implements AutoCloseable {
    private final ZooModel<NDList, NDList> model;
    private final Predictor<NDList, NDList> predictor;

    public OnnxDetManager(String modelName) throws Exception {
        String path = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + modelName, true);
        Criteria<NDList, NDList> criteria = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optEngine("OnnxRuntime")
                .optModelPath(Path.of(path))
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "2")
                .optOption("optimizationLevel", "ALL")
                .optTranslator(new NoopTranslator())
                .build();
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    public float[][] detect(FloatBuffer buffer, int h, int w) throws Exception {
        // 使用 subManager 确保单帧推理后 NDArray 立即释放
        try (NDManager sub = model.getNDManager().newSubManager()) {
            NDArray array = sub.create(buffer, new Shape(1, 3, h, w));
            try (NDList output = predictor.predict(new NDList(array))) {
                NDArray heatMapArr = output.getFirst();
                float[] flat = heatMapArr.toFloatArray();

                float[][] res = new float[h][w];
                for (int i = 0; i < h; i++) {
                    System.arraycopy(flat, i * w, res[i], 0, w);
                }
                return res;
            }
        }
    }

    @Override
    public void close() {
        predictor.close();
        model.close();
    }
}