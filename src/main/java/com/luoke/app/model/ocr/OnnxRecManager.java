package com.luoke.app.model.ocr;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import com.luoke.app.config.AppConfig;
import com.luoke.app.model.BaseOnnxManager;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * ONNX 文本识别模型管理器。
 */
@Slf4j
public class OnnxRecManager extends BaseOnnxManager {

    private final List<String> dict;
    private NDManager recManager;
    private int recCount;

    public OnnxRecManager(String modelName) throws Exception {
        super(modelName);
        this.dict = ResourceUtils.readResourceLines(AppConfig.MODEL_DIR + AppConfig.PPOCR_KEYS);
        this.recManager = newSubManager();
    }

    @Override
    protected void configureCriteria(Criteria.Builder builder) {
        builder.optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "2");
    }

    public String recognize(FloatBuffer buffer, int h, int w) throws Exception {
        if (recManager == null) return "";
        NDArray array = null;
        NDList inputList = null;
        NDList output = null;

        try {
            array = recManager.create(buffer, new Shape(1, 3, h, w));
            inputList = new NDList(array);
            output = predictor.predict(inputList);

            NDArray outTensor = output.getFirst();
            long[] shape = outTensor.getShape().getShape();
            int steps, charSize;
            if (shape.length == 3) {
                steps = (int) shape[1];
                charSize = (int) shape[2];
            } else {
                steps = (int) shape[0];
                charSize = (int) shape[1];
            }

            FloatBuffer outBuffer = outTensor.toByteBuffer().asFloatBuffer();
            return decodeCtc(outBuffer, steps, charSize);
        } finally {
            if (output != null) output.close();
            if (inputList != null) inputList.close();
            if (array != null) array.close();

            if (++recCount % 200 == 0) resetSubManager();
            if (recCount % 300 == 0) rebuildSession();
        }
    }

    private void resetSubManager() {
        try {
            NDManager old = this.recManager;
            this.recManager = newSubManager();
            if (old != null) old.close();
            log.debug("OnnxRecManager NDManager 已重置 (frame={})", recCount);
        } catch (Exception e) {
            log.error("重置 RecManager NDManager 失败", e);
        }
    }

    private void rebuildSession() {
        try {
            NDManager oldManager = this.recManager;
            this.recManager = null;
            if (oldManager != null) oldManager.close();

            rebuild();
            this.recManager = newSubManager();
            System.gc();
            log.info("OnnxRecManager ONNX Session 已重建 (frame={})", recCount);
        } catch (Exception e) {
            log.error("重建 Rec ONNX Session 失败", e);
        }
    }

    private String decodeCtc(FloatBuffer outBuffer, int steps, int charSize) {
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1;

        for (int i = 0; i < steps; i++) {
            int maxIdx = 0;
            int offset = i * charSize;
            float maxVal = outBuffer.get(offset);

            for (int j = 1; j < charSize; j++) {
                float val = outBuffer.get(offset + j);
                if (val > maxVal) {
                    maxVal = val;
                    maxIdx = j;
                }
            }

            if (maxIdx > 0 && maxIdx != lastIdx && maxVal > 0.45f) {
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
        if (recManager != null) recManager.close();
        super.close();
    }
}
