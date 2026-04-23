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

/**
 * 文字检测推理管理类
 */
@Slf4j
public class OnnxDetManager implements AutoCloseable {
    private final OrtEnvironment env;
    private final OrtSession detSession;

    public OnnxDetManager(byte[] detModelBytes) throws OrtException {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

        /**
         * setIntraOpNumThreads: 推理线程数。
         * 修改影响：根据 CPU 核心数调整（建议设为 4）。
         * - 设大：可以降低单次识别延迟，但核心数过多会导致线程切换开销变大，速度反而变慢。
         */
        options.setIntraOpNumThreads(4);
        this.detSession = env.createSession(detModelBytes, options);
    }

    public float[][] detect(float[] pixels, int h, int w) throws OrtException {
        long[] shape = {1, 3, h, w};

        /**
         * DirectBuffer 优化 (零拷贝):
         * 原理：Java 堆内存（float[]）在传递给 C++ 的 ONNX Runtime 时需要一次内存拷贝。
         * 效果：使用 allocateDirect 申请堆外内存，让推理引擎直接读取，减少垃圾回收压力和内存拷贝耗时。
         */
        FloatBuffer directBuffer = ByteBuffer.allocateDirect(pixels.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        directBuffer.put(pixels);
        directBuffer.rewind();

        OnnxTensor tensor = OnnxTensor.createTensor(env, directBuffer, shape);

        try (OrtSession.Result results = detSession.run(Collections.singletonMap("x", tensor))) {
            float[][][][] output = (float[][][][]) results.get(0).getValue();
            return output[0][0];
        } finally {
            tensor.close();
        }
    }

    @Override
    public void close() throws OrtException {
        if (detSession != null) detSession.close();
        if (env != null) env.close();
    }
}