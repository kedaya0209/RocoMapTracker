package com.luoke.app.model;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Collections;
import java.util.List;

/**
 * 文字识别推理管理类
 */
@Slf4j
public class OnnxRecManager implements AutoCloseable {
    /**
     * CONFIDENCE_THRESHOLD: CTC 解码置信度阈值。
     * 修改影响：
     * - 0.35 (当前)：平衡值，能识别模糊文字，并能通过正则过滤掉概率更低的干扰符。
     * - 调高（如0.6）：非常严格，能彻底解决脏字符问题，但会导致漏字（如“石”字认不出来）。
     */
    private static final float CONFIDENCE_THRESHOLD = 0.35f;
    private final OrtEnvironment env;
    private final OrtSession recSession;
    private final List<String> dict;

    public OnnxRecManager(byte[] recModelBytes, List<String> dict) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        options.setIntraOpNumThreads(4);

        this.recSession = env.createSession(recModelBytes, options);
        this.dict = dict;
    }

    public String recognize(float[] pixels, int h, int w) throws OrtException {
        long[] shape = {1, 3, h, w};

        // 同样使用 DirectBuffer 性能优化
        FloatBuffer directBuffer = ByteBuffer.allocateDirect(pixels.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        directBuffer.put(pixels);
        directBuffer.rewind();

        OnnxTensor tensor = OnnxTensor.createTensor(env, directBuffer, shape);

        try (OrtSession.Result results = recSession.run(Collections.singletonMap("x", tensor))) {
            return parseCtc(results);
        } finally {
            tensor.close();
        }
    }

    private String parseCtc(OrtSession.Result results) throws OrtException {
        float[][][] output = (float[][][]) results.get(0).getValue();
        float[][] steps = output[0];
        StringBuilder sb = new StringBuilder();
        int lastIdx = -1;

        for (float[] probArr : steps) {
            int maxIdx = 0;
            float maxVal = probArr[0];
            for (int i = 1; i < probArr.length; i++) {
                if (probArr[i] > maxVal) {
                    maxVal = probArr[i];
                    maxIdx = i;
                }
            }

            /**
             * 过滤低概率字符：只有当模型认为该字符的概率大于阈值时才保留。
             */
            if (maxIdx > 0 && maxIdx != lastIdx && maxVal > CONFIDENCE_THRESHOLD) {
                int charIdx = maxIdx - 1;
                if (charIdx < dict.size()) {
                    sb.append(dict.get(charIdx));
                }
            }
            lastIdx = maxIdx;
        }
        return sb.toString().trim();
    }

    @Override
    public void close() throws OrtException {
        if (recSession != null) recSession.close();
        if (env != null) env.close();
    }
}