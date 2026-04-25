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
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "2")
                .optTranslator(new NoopTranslator())
                .build();
        this.model = criteria.loadModel();
        this.predictor = model.newPredictor();
    }

    public String recognize(FloatBuffer buffer, int h, int w) throws Exception {
        try (NDManager sub = model.getNDManager().newSubManager()) {
            NDArray array = sub.create(buffer, new Shape(1, 3, h, w));
            try (NDList output = predictor.predict(new NDList(array))) {
                NDArray outTensor = output.getFirst();
                float[] flat = outTensor.toFloatArray();
                long[] shape = outTensor.getShape().getShape();
                return decodeCtc(flat, (int) shape[1], (int) shape[2]);
            }
        }
    }

    private String decodeCtc(float[] flat, int steps, int charSize) {
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1;
        for (int i = 0; i < steps; i++) {
            int maxIdx = 0;
            float maxVal = flat[i * charSize];
            for (int j = 1; j < charSize; j++) {
                if (flat[i * charSize + j] > maxVal) {
                    maxVal = flat[i * charSize + j];
                    maxIdx = j;
                }
            }
            if (maxIdx > 0 && maxIdx != lastIdx && maxVal > 0.35f) {
                if (maxIdx - 1 < dict.size()) sb.append(dict.get(maxIdx - 1));
            }
            lastIdx = maxIdx;
        }
        return sb.toString();
    }

    @Override
    public void close() {
        predictor.close();
        model.close();
    }
}