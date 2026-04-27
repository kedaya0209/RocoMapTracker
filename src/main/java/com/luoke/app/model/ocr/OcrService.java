package com.luoke.app.model.ocr;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR 服务极速版 (原生 OpenCV 实现)
 * 核心设计：
 * 1. 成员变量复用：Mat 对象在初始化时创建，close 时销毁，运行期间不产生新对象。
 * 2. 显式释放：对循环内产生的子 Mat（如 lineHeader）进行严格的 release()。
 * 3. 零拷贝思想：利用 Mat.put 直接填充灰度字节流。
 */
@Slf4j
public class OcrService implements AutoCloseable {

    private static final int REC_STD_HEIGHT = 52;
    private static final float TEXT_HEAT_THRESHOLD = 0.20f;
    private static final int EXPAND_Y = 4;
    private static final int DET_ALIGNMENT = 32;
    private static final int REC_WIDTH_ALIGNMENT = 8;
    private static final int BINARY_THRESHOLD = 150;

    // 预创建 Mat 容器，避免在 recognizeAll 中反复申请 Native 内存
    private final Mat detResizedMat = new Mat();
    private final Mat letterboxMat = new Mat();
    private final Mat recResizedMat = new Mat();
    private final Mat tempResized = new Mat();
    private FloatBuffer recFloatBuffer;
    // --- 长期占用的复用资源 ---
    private FloatBuffer detFloatBuffer;
    // 线程内复用字节数组，减少 GC 压力
    private byte[] rowCache = new byte[8192];

    private OnnxDetManager detManager;
    private OnnxRecManager recManager;
    private volatile boolean isClosed = false;

    public OcrService() {
    }

    public void init() throws Exception {
        // DJL 环境下通常会自动加载原生库，此处初始化 ONNX 管理器
        this.detManager = new OnnxDetManager(AppConfig.OCR_DET_MODEL);
        this.recManager = new OnnxRecManager(AppConfig.OCR_REC_MODEL);

        // 预分配 Direct FloatBuffer (堆外内存)，防止频繁分配导致的碎片
        this.detFloatBuffer = ByteBuffer.allocateDirect(3 * 1024 * 1024 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        this.recFloatBuffer = ByteBuffer.allocateDirect(3 * 52 * 2048 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        log.info("✅ OCR 服务原生版初始化完成");
    }

    public List<String> recognizeAll(byte[] grayData, int width, int height) {
        if (isClosed || grayData == null) return Collections.emptyList();

        // 1. 创建顶层 Mat 包装输入数据
        Mat fullMat = new Mat(height, width, CvType.CV_8UC1);
        fullMat.put(0, 0, grayData);

        try {
            int detW = alignTo(width, DET_ALIGNMENT);
            int detH = alignTo(height, DET_ALIGNMENT);

            LetterboxInfo info = new LetterboxInfo();
            // 2. 更新 letterboxMat (成员变量复用)
            updateLetterbox(fullMat, detW, detH, info);

            // 3. 检测阶段
            FloatBuffer detBuffer = fastBuildTensor(letterboxMat, detW, detH, true);
            float[] heatMap = detManager.detect(detBuffer, detH, detW);

            List<Rect> boxes = extractTextLineBoxes(heatMap, detH, detW, width, height, info);
            if (boxes.isEmpty()) return Collections.emptyList();

            // 4. 识别阶段
            List<String> resultList = new ArrayList<>(boxes.size());
            for (Rect box : boxes) {
                // 裁剪行子图（创建的是 Header，必须 release）
                Mat lineHeader = new Mat(fullMat, box);
                try {
                    if (lineHeader.empty()) continue;

                    int recW = alignTo((int) (lineHeader.cols() * (double) REC_STD_HEIGHT / lineHeader.rows()), REC_WIDTH_ALIGNMENT);

                    FloatBuffer recBuffer = fastBuildTensor(lineHeader, recW, REC_STD_HEIGHT, false);
                    String text = recManager.recognize(recBuffer, REC_STD_HEIGHT, recW);

                    // 简单清洗结果
                    text = text.replaceAll("[^\\u4e00-\\u9fa5xX×*0-9a-zA-Z]", "").trim();
                    if (!text.isEmpty()) resultList.add(text);
                } finally {
                    lineHeader.release(); // 显式释放临时子 Mat
                }
            }
            return resultList;

        } catch (Exception e) {
            log.error("OCR 匹配链路异常", e);
            return Collections.emptyList();
        } finally {
            fullMat.release(); // 显式释放顶层输入 Mat
        }
    }

    /**
     * 核心优化：直接修改成员变量 Mat，不创建新的 Mat 对象
     */
    private void updateLetterbox(Mat src, int dstW, int dstH, LetterboxInfo info) {
        int srcW = src.cols();
        int srcH = src.rows();
        double scale = Math.min((double) dstW / srcW, (double) dstH / srcH);
        int newW = (int) Math.round(srcW * scale);
        int newH = (int) Math.round(srcH * scale);

        int padX = (dstW - newW) / 2;
        int padY = (dstH - newH) / 2;

        Imgproc.resize(src, tempResized, new Size(newW, newH));
        Core.copyMakeBorder(tempResized, letterboxMat, padY, dstH - newH - padY,
                padX, dstW - newW - padX, Core.BORDER_CONSTANT, new Scalar(0));

        info.srcNewW = newW;
        info.srcNewH = newH;
        info.padX = padX;
        info.padY = padY;
    }

    private FloatBuffer fastBuildTensor(Mat src, int tw, int th, boolean isDet) {
        Mat targetMat = isDet ? detResizedMat : recResizedMat;
        FloatBuffer buffer = isDet ? detFloatBuffer : recFloatBuffer;

        Imgproc.resize(src, targetMat, new Size(tw, th), 0, 0, Imgproc.INTER_LINEAR);

        int size = tw * th;
        buffer.clear();

        if (rowCache.length < tw) rowCache = new byte[tw + 512];

        float[] data = new float[3 * size];
        for (int y = 0; y < th; y++) {
            // 批量获取行数据，效率远高于逐像素调用 ptr()
            targetMat.get(y, 0, rowCache);
            for (int x = 0; x < tw; x++) {
                int gray = rowCache[x] & 0xFF;
                float val = (gray > BINARY_THRESHOLD) ? 0.0f : 1.0f;
                int idx = y * tw + x;
                float norm = (val - 0.5f) / 0.5f;
                // 填充 CHW 格式 Tensor
                data[idx] = norm;
                data[size + idx] = norm;
                data[2 * size + idx] = norm;
            }
        }

        buffer.put(data);
        buffer.flip();
        return buffer;
    }

    private List<Rect> extractTextLineBoxes(float[] heatMap, int h, int w, int srcW, int srcH, LetterboxInfo info) {
        List<Rect> boxes = new ArrayList<>();
        float ratioH = (float) info.srcNewH / h;
        float invScale = (float) srcH / info.srcNewH;
        Integer startY = null;

        for (int y = 0; y < h; y++) {
            boolean hasText = false;
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                if (heatMap[rowOffset + x] >= TEXT_HEAT_THRESHOLD) {
                    hasText = true;
                    break;
                }
            }
            if (hasText && startY == null) {
                startY = y;
            } else if (!hasText && startY != null) {
                addBox(boxes, startY, y - 1, info, invScale, ratioH, srcW, srcH);
                startY = null;
            }
        }
        if (startY != null) addBox(boxes, startY, h - 1, info, invScale, ratioH, srcW, srcH);
        return boxes;
    }

    private void addBox(List<Rect> boxes, int y1, int y2, LetterboxInfo info, float invScale, float ratioH, int srcW, int srcH) {
        float realY1 = (y1 * ratioH - info.padY) * invScale;
        float realY2 = (y2 * ratioH - info.padY) * invScale;
        int rectY = Math.max(0, (int) realY1 - EXPAND_Y);
        int rectBottom = Math.min(srcH, (int) realY2 + EXPAND_Y);
        int rectHeight = rectBottom - rectY;
        if (rectHeight > 5) boxes.add(new Rect(0, rectY, srcW, rectHeight));
    }

    private int alignTo(int size, int alignment) {
        return (size + alignment - 1) / alignment * alignment;
    }

    @Override
    public void close() {
        if (isClosed) return;
        isClosed = true;

        if (detManager != null) detManager.close();
        if (recManager != null) recManager.close();

        // 显式销毁长期持有的 Native 资源
        detResizedMat.release();
        recResizedMat.release();
        letterboxMat.release();
        tempResized.release();

        detFloatBuffer = null;
        recFloatBuffer = null;

        log.info("✅ OCR 服务已安全关闭");
    }

    private static class LetterboxInfo {
        int srcNewW, srcNewH, padX, padY;
    }
}