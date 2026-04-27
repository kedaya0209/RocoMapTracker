package com.luoke.app.model.cnn;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.player.Player;
import lombok.extern.slf4j.Slf4j;

/**
 * 箭头方向识别服务 - 极速兼容版
 */
@Slf4j
public class ArrowPredictService implements AutoCloseable {

    private static final int CNN_INPUT_SIZE = 64;
    private static final int PIXEL_COUNT = CNN_INPUT_SIZE * CNN_INPUT_SIZE;

    // 预分配缓冲，减少 GC 抖动
    private final float[] floatBuffer = new float[PIXEL_COUNT];

    private ArrowOnnxManager modelManager;
    private volatile boolean isClosed = false;

    public void init() throws Exception {
        this.modelManager = new ArrowOnnxManager(AppConfig.ARROW_MODEL_NAME);
        log.info("✅ 箭头预测服务初始化完成。");
    }

    public Player predict(byte[] grayData, int width, int height) {
        if (isClosed || grayData == null || width < CNN_INPUT_SIZE || height < CNN_INPUT_SIZE) {
            return new Player(false, 0);
        }

        // 使用 subManager 自动管理 NDArray 生命周期
        try (NDManager sub = modelManager.getNDManager().newSubManager()) {

            // 1. 提取并归一化数据到 float 缓冲
            fastExtractAndNormalize(grayData, width, height);

            // 2. 创建输入 NDArray
            // 大多数 ONNX 量化模型（PTQ）为了兼容性，输入层仍保持 Float32 接口
            // 内部卷积算子会自动切换到 INT8 运行。
            NDArray inputArr = sub.create(floatBuffer, new Shape(1, 1, CNN_INPUT_SIZE, CNN_INPUT_SIZE));

            // 3. 执行推理
            try (NDList output = modelManager.getPredictor().predict(new NDList(inputArr))) {
                if (output.isEmpty()) return new Player(false, 0);

                float[] result = output.getFirst().toFloatArray();

                // 4. 解析回归向量 [sin, cos] 为角度
                double angleRad = Math.atan2(result[0], result[1]);
                double angleDeg = Math.toDegrees(angleRad);

                // 转换到地图常用的 [0, 360) 坐标系
                double heading = (angleDeg + 360) % 360;

                return new Player(true, heading);
            }
        } catch (Exception e) {
            log.error("❌ 箭头推理异常: {}", e.getMessage());
            return new Player(false, 0);
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
                // byte 必须 & 0xFF 才能转为正确的无符号 int
                floatBuffer[destPos + x] = (src[srcPos + x] & 0xFF) / 255.0f;
            }
        }
    }

    /**
     * 如果你确定你的 ONNX 模型输入强制要求 UINT8 (Netron 查看为深绿色输入)
     * 则可以使用此方法代替 fastExtractAndNormalize
     */
    private void fastExtractInt8(byte[] src, int w, int h, byte[] target) {
        int startX = (w - CNN_INPUT_SIZE) / 2;
        int startY = (h - CNN_INPUT_SIZE) / 2;
        for (int y = 0; y < CNN_INPUT_SIZE; y++) {
            System.arraycopy(src, (startY + y) * w + startX, target, y * CNN_INPUT_SIZE, CNN_INPUT_SIZE);
        }
    }

    @Override
    public void close() {
        if (isClosed) return;
        isClosed = true;
        if (modelManager != null) {
            modelManager.close();
        }
    }
}