package com.luoke.app.model.cnn;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 箭头方向识别服务 - Native内存零泄漏版
 * 核心优化：
 * 1. NDArray/NDList 使用 try-with-resources 显式关闭，不依赖 GC
 * 2. 每 500 帧重置 NDManager，释放 DJL 内部引用跟踪
 * 3. 每 1000 帧重建 ONNX Session，释放 ORT 内部 graph execution 分配器碎片
 * 4. 预分配 float 缓冲，减少 GC 抖动
 */
@Slf4j
public class ArrowPredictService implements AutoCloseable {

    private static final int CNN_INPUT_SIZE = 64;
    private static final int PIXEL_COUNT = CNN_INPUT_SIZE * CNN_INPUT_SIZE;
    private static final int MANAGER_RESET_INTERVAL = 500;
    private static final int SESSION_REBUILD_INTERVAL = 1000;

    // 预分配缓冲，减少 GC 抖动
    private final float[] floatBuffer = new float[PIXEL_COUNT];

    private ArrowOnnxManager modelManager;
    private NDManager predictManager;
    private volatile boolean isClosed = false;
    private int frameCount = 0;

    public void init() throws Exception {
        this.modelManager = new ArrowOnnxManager(AppConfig.ARROW_MODEL_NAME);
        // 创建专用 NDManager，不再每帧 newSubManager
        this.predictManager = modelManager.newSubManager();
        log.info("箭头预测服务初始化完成 (Native零泄漏版)");
    }

    public Double predict(byte[] grayData, int width, int height) {
        if (isClosed || predictManager == null || grayData == null || width < CNN_INPUT_SIZE || height < CNN_INPUT_SIZE) {
            return null;
        }

        // 每 500 帧重置 NDManager，释放 DJL 内部引用跟踪
        if (++frameCount % MANAGER_RESET_INTERVAL == 0) {
            resetManager();
        }
        // 每 1000 帧重建 ONNX Session，释放 ORT graph execution 分配器碎片
        if (frameCount % SESSION_REBUILD_INTERVAL == 0) {
            rebuildSession();
        }

        // 1. 提取并归一化数据到预分配 float 缓冲
        fastExtractAndNormalize(grayData, width, height);

        // 2. try-with-resources 确保 NDArray 显式释放，不依赖 GC
        //    inputList 关闭时会自动关闭内部 NDArray，无需单独声明
        NDArray inputArr = predictManager.create(floatBuffer,
                new Shape(1, 1, CNN_INPUT_SIZE, CNN_INPUT_SIZE));
        try (NDList inputList = new NDList(inputArr);
             NDList output = modelManager.getPredictor().predict(inputList)) {

            if (output.isEmpty()) return null;

            float[] result = output.getFirst().toFloatArray();

            // 3. 解析回归向量 [sin, cos] 为角度
            double angleRad = Math.atan2(result[0], result[1]);
            double angleDeg = Math.toDegrees(angleRad);

            return (angleDeg + 360) % 360;
        } catch (Exception e) {
            log.error("箭头推理异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 重置 NDManager — 关闭旧管理器并创建新的，释放累积的 DJL 引用跟踪
     */
    private void resetManager() {
        NDManager old = predictManager;
        try {
            predictManager = modelManager.newSubManager();
        } catch (Exception e) {
            log.error("重建 NDManager 失败", e);
            predictManager = old;
            return;
        }
        if (old != null) {
            old.close();
        }
        log.debug("NDManager 已重置 (frame={})", frameCount);
    }

    /**
     * 重建 ONNX Session — 关闭旧 Model/Predictor 并重新加载 ONNX 模型。
     * 这是唯一能释放 ONNX Runtime 内部 graph execution 分配器碎片的方式。
     */
    private void rebuildSession() {
        try {
            // 先关闭子 NDManager（必须在父管理器之前关闭）
            NDManager oldManager = predictManager;
            predictManager = null;
            if (oldManager != null) {
                oldManager.close();
            }

            // 重建 model → 创建新的 OrtSession，彻底释放旧 session 内部内存
            modelManager.rebuild();

            // 从新 model 创建新 NDManager
            predictManager = modelManager.newSubManager();

            // 建议 JVM 回收，加速 native 内存释放
            System.gc();

            log.info("ONNX Session 已重建 (frame={})", frameCount);
        } catch (Exception e) {
            log.error("重建 ONNX Session 失败", e);
        }
    }

    /**
     * 高性能中心提取：直接从灰度流中切出 64x64 并归一化
     */
    private void fastExtractAndNormalize(byte[] src, int w, int h) {
        // 1. 计算最大内接正方形的边长
        int squareSide = Math.min(w, h);

        // 2. 计算最大内接正方形的起始坐标（中心对齐）
        int squareStartX = (w - squareSide) / 2;
        int squareStartY = (h - squareSide) / 2;

        // 3. 在这个正方形内部，再提取中心 CNN_INPUT_SIZE * CNN_INPUT_SIZE 区域
        //    如果 CNN_INPUT_SIZE >= squareSide，则直接使用整个正方形（需缩放，但通常 CNN_INPUT_SIZE <= squareSide）
        int cropStartX = squareStartX + (squareSide - CNN_INPUT_SIZE) / 2;
        int cropStartY = squareStartY + (squareSide - CNN_INPUT_SIZE) / 2;

        // 4. 从原图中提取并归一化
        for (int y = 0; y < CNN_INPUT_SIZE; y++) {
            int srcPos = (cropStartY + y) * w + cropStartX;
            int destPos = y * CNN_INPUT_SIZE;
            for (int x = 0; x < CNN_INPUT_SIZE; x++) {
                floatBuffer[destPos + x] = (src[srcPos + x] & 0xFF) / 255.0f;
            }
        }
    }

    private void saveCenterCropAsPng(byte[] src, int w, int h, String outputPath) {
        // 1. 计算最大内接正方形的边长
        int squareSide = Math.min(w, h);

        // 2. 计算最大内接正方形的起始坐标（中心对齐）
        int squareStartX = (w - squareSide) / 2;
        int squareStartY = (h - squareSide) / 2;

        // 3. 在这个正方形内部，再提取中心 CNN_INPUT_SIZE × CNN_INPUT_SIZE 区域
        int cropStartX = squareStartX + (squareSide - CNN_INPUT_SIZE) / 2;
        int cropStartY = squareStartY + (squareSide - CNN_INPUT_SIZE) / 2;

        // 4. 创建 BufferedImage（灰度图）
        BufferedImage image = new BufferedImage(CNN_INPUT_SIZE, CNN_INPUT_SIZE, BufferedImage.TYPE_BYTE_GRAY);

        // 5. 填充像素数据
        for (int y = 0; y < CNN_INPUT_SIZE; y++) {
            int srcPos = (cropStartY + y) * w + cropStartX;
            for (int x = 0; x < CNN_INPUT_SIZE; x++) {
                int gray = src[srcPos + x] & 0xFF;   // 转为无符号值
                int rgb = (gray << 16) | (gray << 8) | gray; // 灰度转 RGB (R=G=B)
                image.setRGB(x, y, rgb);
            }
        }

        try {
            // 6. 写入文件
            ImageIO.write(image, "png", new File(outputPath));
        } catch (Exception ignore) {
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
