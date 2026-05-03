package com.luoke.app.model.cnn;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.player.Player;
import lombok.extern.slf4j.Slf4j;

/**
 * 箭头方向识别服务 - Native内存零泄漏版
 *
 * 核心优化：
 * 1. 使用单一 NDManager 成员变量，不复用每帧 newSubManager，杜绝管理器膨胀
 * 2. NDArray 显式 close，不依赖 try-with-resources 的隐式清理
 * 3. 每 500 帧重置 NDManager，释放 DJL 内部累积的引用跟踪结构
 * 4. 输入 NDList 显式关闭
 */
@Slf4j
public class ArrowPredictService implements AutoCloseable {

    private static final int CNN_INPUT_SIZE = 64;
    private static final int PIXEL_COUNT = CNN_INPUT_SIZE * CNN_INPUT_SIZE;

    // 预分配缓冲，减少 GC 抖动
    private final float[] floatBuffer = new float[PIXEL_COUNT];

    private ArrowOnnxManager modelManager;
    private NDManager predictManager;
    private volatile boolean isClosed = false;
    private int predictCount = 0;

    public void init() throws Exception {
        this.modelManager = new ArrowOnnxManager(AppConfig.ARROW_MODEL_NAME);
        // 创建专用 NDManager，不再每帧 newSubManager
        this.predictManager = modelManager.newSubManager();
        log.info("箭头预测服务初始化完成 (Native零泄漏版)");
    }

    public Player predict(byte[] grayData, int width, int height) {
        if (isClosed || predictManager == null || grayData == null || width < CNN_INPUT_SIZE || height < CNN_INPUT_SIZE) {
            return new Player(false, 0);
        }

        // 1. 提取并归一化数据到预分配 float 缓冲
        fastExtractAndNormalize(grayData, width, height);

        NDArray inputArr = null;
        NDList inputList = null;
        NDList output = null;

        try {
            // 2. 创建输入 NDArray（挂载到 predictManager）
            inputArr = predictManager.create(floatBuffer, new Shape(1, 1, CNN_INPUT_SIZE, CNN_INPUT_SIZE));

            // 3. 包装为 NDList 并执行推理
            inputList = new NDList(inputArr);
            output = modelManager.getPredictor().predict(inputList);

            if (output.isEmpty()) return new Player(false, 0);

            float[] result = output.getFirst().toFloatArray();

            // 4. 解析回归向量 [sin, cos] 为角度
            double angleRad = Math.atan2(result[0], result[1]);
            double angleDeg = Math.toDegrees(angleRad);
            double heading = (angleDeg + 360) % 360;

            return new Player(true, heading);
        } catch (Exception e) {
            log.error("箭头推理异常: {}", e.getMessage());
            return new Player(false, 0);
        } finally {
            // 显式释放，不依赖 GC
            if (output != null) output.close();
            if (inputList != null) inputList.close();
            if (inputArr != null) inputArr.close();

            // 每 200 帧重置 NDManager，释放 DJL 内部累积的引用跟踪结构
            if (++predictCount % 200 == 0) {
                resetManager();
            }
            // 每 300 帧重建 ONNX Session，重置 ORT Arena 分配器
            if (predictCount % 300 == 0) {
                rebuildSession();
            }
        }
    }

    /**
     * 重置 NDManager：关闭旧的并创建新的，切断累积的引用链。
     */
    private synchronized void resetManager() {
        try {
            NDManager old = this.predictManager;
            this.predictManager = modelManager.newSubManager();
            if (old != null) {
                old.close();
            }
            log.debug("ArrowPredictService NDManager 已重置 (frame={})", predictCount);
        } catch (Exception e) {
            log.error("重置 NDManager 失败", e);
        }
    }

    /**
     * 重建 ONNX Session：关闭旧的 Model/Predictor 并重建，
     * 释放 ONNX Runtime 内部 Arena 分配器积累的内存。
     */
    private synchronized void rebuildSession() {
        try {
            // 先关闭旧的 NDManager（子管理器必须在父管理器之前关闭）
            NDManager oldManager = this.predictManager;
            this.predictManager = null;
            if (oldManager != null) {
                oldManager.close();
            }

            // 重建 model（这会创建新的 OrtSession，释放旧 session 的 Arena）
            modelManager.rebuild();

            // 从新 model 创建新 NDManager
            this.predictManager = modelManager.newSubManager();

            System.gc();

            log.info("ArrowPredictService ONNX Session 已重建 (frame={})", predictCount);
        } catch (Exception e) {
            log.error("重建 ONNX Session 失败", e);
        }
    }

    /**
     * 高性能中心提取：直接从灰度流中切出 64x64 并归一化
     */
    private void fastExtractAndNormalize(byte[] src, int w, int h) {
        int startX = (w - CNN_INPUT_SIZE) / 2;
        int startY = (h - CNN_INPUT_SIZE) / 2;

        for (int y = 0; y < CNN_INPUT_SIZE; y++) {
            int srcPos = (startY + y) * w + startX;
            int destPos = y * CNN_INPUT_SIZE;
            for (int x = 0; x < CNN_INPUT_SIZE; x++) {
                floatBuffer[destPos + x] = (src[srcPos + x] & 0xFF) / 255.0f;
            }
        }
    }

    @Override
    public void close() {
        if (isClosed) return;
        isClosed = true;
        if (predictManager != null) {
            predictManager.close();
        }
        if (modelManager != null) {
            modelManager.close();
        }
    }
}
