package com.luoke.app.model.ocr;

import com.luoke.app.config.PathConfig;
import com.luoke.app.config.OcrConfig;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OCR 服务 — 纯 Java 实现 (无 JavaCPP 依赖)
 *
 * <p>替代 OpenCV Mat 操作：byte[] + 手动双线性插值 resize / copyMakeBorder / crop.
 */
@Slf4j
public class OcrService implements AutoCloseable {

    private byte[] fullPixels;
    private int fullW = -1, fullH = -1;

    private FloatBuffer recFloatBuffer;
    private FloatBuffer detFloatBuffer;
    private byte[] rowCache = new byte[8192];
    private float[] dataCache = new float[0];

    private OnnxDetManager detManager;
    private OnnxRecManager recManager;
    private volatile boolean isClosed = false;

    public OcrService() {
    }

    /**
     * 双线性插值缩放 (灰度图)
     */
    private static byte[] resize(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH];
        double scaleX = (double) srcW / dstW;
        double scaleY = (double) srcH / dstH;

        for (int y = 0; y < dstH; y++) {
            double srcY = y * scaleY;
            int y0 = (int) srcY;
            int y1 = Math.min(y0 + 1, srcH - 1);
            double dy = srcY - y0;

            for (int x = 0; x < dstW; x++) {
                double srcX = x * scaleX;
                int x0 = (int) srcX;
                int x1 = Math.min(x0 + 1, srcW - 1);
                double dx = srcX - x0;

                double v00 = src[y0 * srcW + x0] & 0xFF;
                double v01 = src[y0 * srcW + x1] & 0xFF;
                double v10 = src[y1 * srcW + x0] & 0xFF;
                double v11 = src[y1 * srcW + x1] & 0xFF;

                dst[y * dstW + x] = (byte) Math.round(
                        (1 - dy) * ((1 - dx) * v00 + dx * v01) +
                                dy * ((1 - dx) * v10 + dx * v11));
            }
        }
        return dst;
    }

    /**
     * 四周填充 + 中心放置 (替代 copyMakeBorder)
     */
    private static byte[] copyMakeBorder(byte[] src, int srcW, int srcH,
                                         int top, int bottom, int left, int right, byte fillValue) {
        int dstW = srcW + left + right;
        int dstH = srcH + top + bottom;
        byte[] dst = new byte[dstW * dstH];
        Arrays.fill(dst, fillValue);
        for (int y = 0; y < srcH; y++) {
            System.arraycopy(src, y * srcW, dst, (top + y) * dstW + left, srcW);
        }
        return dst;
    }

    /**
     * 裁剪 ROI 区域 (替代 Mat.apply)
     */
    private static byte[] crop(byte[] src, int srcW, int srcH, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > srcW || y + h > srcH || w <= 0 || h <= 0) {
            return new byte[0];
        }
        byte[] dst = new byte[w * h];
        for (int row = 0; row < h; row++) {
            System.arraycopy(src, (y + row) * srcW + x, dst, row * w, w);
        }
        return dst;
    }

    public void init() throws Exception {
        this.detManager = new OnnxDetManager(PathConfig.OCR_DET_MODEL);
        this.recManager = new OnnxRecManager(PathConfig.OCR_REC_MODEL);

        this.detFloatBuffer = ByteBuffer.allocateDirect(3 * 1024 * 1024 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        this.recFloatBuffer = ByteBuffer.allocateDirect(3 * 52 * 2048 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        log.info("OCR 服务纯 Java 版初始化完成");
    }

    // ================== 纯 Java 图像原语 ==================

    public List<String> recognizeAll(byte[] grayData, int width, int height) {
        if (isClosed || grayData == null) return Collections.emptyList();

        if (fullPixels == null || width != fullW || height != fullH) {
            fullPixels = new byte[width * height];
            fullW = width;
            fullH = height;
        }
        System.arraycopy(grayData, 0, fullPixels, 0, grayData.length);

        try {
            int detW = alignTo(width, OcrConfig.OCR_DET_ALIGNMENT);
            int detH = alignTo(height, OcrConfig.OCR_DET_ALIGNMENT);

            LetterboxInfo info = new LetterboxInfo();
            byte[] letterbox = createLetterbox(fullPixels, fullW, fullH, detW, detH, info);

            FloatBuffer detBuffer = fastBuildTensor(letterbox, detW, detH, detW, detH, true);
            float[] heatMap = detManager.detect(detBuffer, detH, detW);

            List<Rect> boxes = extractTextLineBoxes(heatMap, detH, detW, width, height, info);
            if (boxes.isEmpty()) return Collections.emptyList();

            List<String> resultList = new ArrayList<>(boxes.size());
            for (Rect box : boxes) {
                byte[] linePixels = crop(fullPixels, fullW, fullH, box.x, box.y, box.width, box.height);
                if (linePixels.length == 0) continue;

                int recW = alignTo((int) (box.width * (double) OcrConfig.OCR_REC_STD_HEIGHT / box.height), OcrConfig.OCR_REC_WIDTH_ALIGNMENT);
                FloatBuffer recBuffer = fastBuildTensor(linePixels, box.width, box.height, recW, OcrConfig.OCR_REC_STD_HEIGHT, false);
                String text = recManager.recognize(recBuffer, OcrConfig.OCR_REC_STD_HEIGHT, recW);

                text = text.replaceAll("[^\\u4e00-\\u9fa5xX×*0-9a-zA-Z]", "").trim();
                if (!text.isEmpty()) resultList.add(text);
            }
            return resultList;

        } catch (Exception e) {
            log.error("OCR 匹配链路异常", e);
            return Collections.emptyList();
        }
    }

    /**
     * 等比例缩放 + 居中填充 → letterbox.
     */
    private byte[] createLetterbox(byte[] src, int srcW, int srcH, int dstW, int dstH, LetterboxInfo info) {
        double scale = Math.min((double) dstW / srcW, (double) dstH / srcH);
        int newW = (int) Math.round(srcW * scale);
        int newH = (int) Math.round(srcH * scale);

        int padX = (dstW - newW) / 2;
        int padY = (dstH - newH) / 2;

        info.srcNewW = newW;
        info.srcNewH = newH;
        info.padX = padX;
        info.padY = padY;

        byte[] resized = resize(src, srcW, srcH, newW, newH);
        return copyMakeBorder(resized, newW, newH, padY, dstH - newH - padY,
                padX, dstW - newW - padX, (byte) 0);
    }

    /**
     * 双线性插值缩放 + 二值化 → FloatBuffer (3通道 norm=[-1,1]).
     *
     * @param src   输入灰度像素
     * @param srcW  输入宽度
     * @param srcH  输入高度
     * @param tw    目标宽度
     * @param th    目标高度
     * @param isDet true=检测, false=识别 (决定用哪个 FloatBuffer)
     */
    private FloatBuffer fastBuildTensor(byte[] src, int srcW, int srcH, int tw, int th, boolean isDet) {
        FloatBuffer buffer = isDet ? detFloatBuffer : recFloatBuffer;

        byte[] resized;
        if (srcW == tw && srcH == th) {
            resized = src;
        } else {
            resized = resize(src, srcW, srcH, tw, th);
        }

        int size = tw * th;
        buffer.clear();

        if (rowCache.length < tw) rowCache = new byte[tw + 512];

        int dataLen = 3 * size;
        if (dataCache.length < dataLen) {
            dataCache = new float[dataLen];
        }

        for (int y = 0; y < th; y++) {
            System.arraycopy(resized, y * tw, rowCache, 0, tw);
            for (int x = 0; x < tw; x++) {
                int gray = rowCache[x] & 0xFF;
                float val = (gray > OcrConfig.OCR_BINARY_THRESHOLD) ? 0.0f : 1.0f;
                int idx = y * tw + x;
                float norm = (val - 0.5f) / 0.5f;
                dataCache[idx] = norm;
                dataCache[size + idx] = norm;
                dataCache[2 * size + idx] = norm;
            }
        }

        buffer.put(dataCache, 0, dataLen);
        buffer.flip();
        return buffer;
    }

    // ================== 文本行提取 (逻辑不变) ==================

    private List<Rect> extractTextLineBoxes(float[] heatMap, int h, int w, int srcW, int srcH, LetterboxInfo info) {
        List<Rect> boxes = new ArrayList<>();
        float ratioH = (float) info.srcNewH / h;
        float invScale = (float) srcH / info.srcNewH;
        Integer startY = null;

        for (int y = 0; y < h; y++) {
            boolean hasText = false;
            int rowOffset = y * w;
            for (int x = 0; x < w; x++) {
                if (heatMap[rowOffset + x] >= OcrConfig.OCR_TEXT_HEAT_THRESHOLD) {
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

    private void addBox(List<Rect> boxes, int y1, int y2, LetterboxInfo info,
                        float invScale, float ratioH, int srcW, int srcH) {
        float realY1 = (y1 * ratioH - info.padY) * invScale;
        float realY2 = (y2 * ratioH - info.padY) * invScale;
        int rectY = Math.max(0, (int) realY1 - OcrConfig.OCR_EXPAND_Y);
        int rectBottom = Math.min(srcH, (int) realY2 + OcrConfig.OCR_EXPAND_Y);
        int rectHeight = rectBottom - rectY;
        if (rectHeight > 5) boxes.add(new Rect(0, rectY, srcW, rectHeight));
    }

    // ================== 工具方法 ==================

    private int alignTo(int size, int alignment) {
        return (size + alignment - 1) / alignment * alignment;
    }

    @Override
    public void close() {
        if (isClosed) return;
        isClosed = true;

        if (detManager != null) detManager.close();
        if (recManager != null) recManager.close();

        // 纯 Java byte[] 无需显式释放，GC 处理
        fullPixels = null;
        detFloatBuffer = null;
        recFloatBuffer = null;

        log.info("OCR 服务已安全关闭");
    }

    // ================== 内部类型 ==================

    private record Rect(int x, int y, int width, int height) {
    }

    private static class LetterboxInfo {
        int srcNewW, srcNewH, padX, padY;
    }
}
