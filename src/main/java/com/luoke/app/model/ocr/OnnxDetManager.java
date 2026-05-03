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

    private NDManager detManager;
    private int detectCount;

    public OnnxDetManager(String modelName) throws Exception {
        super(modelName);
        this.detManager = newSubManager();
    }

    @Override
    protected void configureCriteria(Criteria.Builder builder) {
        builder.optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "2")
                .optOption("optimizationLevel", "ALL");
    }

    public float[] detect(FloatBuffer buffer, int h, int w) throws Exception {
        if (detManager == null) return new float[0];
        NDArray array = null;
        NDList inputList = null;
        NDList output = null;

        try {
            array = detManager.create(buffer, new Shape(1, 3, h, w));
            inputList = new NDList(array);
            output = predictor.predict(inputList);
            return output.getFirst().toFloatArray();
        } finally {
            if (output != null) output.close();
            if (inputList != null) inputList.close();
            if (array != null) array.close();

            if (++detectCount % 200 == 0) resetSubManager();
            if (detectCount % 300 == 0) rebuildSession();
        }
    }

    private void resetSubManager() {
        try {
            NDManager old = this.detManager;
            this.detManager = newSubManager();
            if (old != null) old.close();
            log.debug("OnnxDetManager NDManager 已重置 (frame={})", detectCount);
        } catch (Exception e) {
            log.error("重置 DetManager NDManager 失败", e);
        }
    }

    private void rebuildSession() {
        try {
            NDManager oldManager = this.detManager;
            this.detManager = null;
            if (oldManager != null) oldManager.close();

            rebuild();
            this.detManager = newSubManager();
            System.gc();
            log.info("OnnxDetManager ONNX Session 已重建 (frame={})", detectCount);
        } catch (Exception e) {
            log.error("重建 Det ONNX Session 失败", e);
        }
    }

    @Override
    public void close() {
        if (detManager != null) detManager.close();
        super.close();
    }
}
