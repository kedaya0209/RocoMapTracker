package com.luoke.app.model.ocr;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import com.luoke.app.model.BaseOnnxManager;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;

/**
 * ONNX 文本检测模型管理器。
 */
@Slf4j
public class OnnxDetManager extends BaseOnnxManager {

    private final NDManager detManager;

    public OnnxDetManager(String modelName) throws Exception {
        super(modelName);
        this.detManager = newSubManager();
    }

    @Override
    protected void configureCriteria(Criteria.Builder<NDList, NDList> builder) {
        builder.optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "2")
                .optOption("optimizationLevel", "ALL");
    }

    public float[] detect(FloatBuffer buffer, int h, int w) throws Exception {
        if (detManager == null) return new float[0];

        try (NDArray array = detManager.create(buffer, new Shape(1, 3, h, w)); NDList inputList = new NDList(array); NDList output = predictor.predict(inputList)) {
            return output.getFirst().toFloatArray();
        }
    }

    @Override
    public void close() {
        if (detManager != null) detManager.close();
        super.close();
    }
}
