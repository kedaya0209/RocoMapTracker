package com.luoke.app.model.ocr;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.bytedeco.opencv.opencv_core.Size;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR 服务极速版 (JavaCPP OpenCV 实现)
 */
@Slf4j
public class OcrService implements AutoCloseable {

    private static final int REC_STD_HEIGHT = 52;
    private static final float TEXT_HEAT_THRESHOLD = 0.20f;
    private static final int EXPAND_Y = 4;
    private static final int DET_ALIGNMENT = 32;
    private static final int REC_WIDTH_ALIGNMENT = 8;
    private static final int BINARY_THRESHOLD = 150;

    private Mat fullMat;
    private int fullW = -1, fullH = -1;
    private final Mat detResizedMat = new Mat();
    private final Mat letterboxMat = new Mat();
    private final Mat recResizedMat = new Mat();
    private final Mat tempResized = new Mat();
    private FloatBuffer recFloatBuffer;
    private FloatBuffer detFloatBuffer;
    private byte[] rowCache = new byte[8192];
    private float[] dataCache = new float[0];

    private OnnxDetManager detManager;
    private OnnxRecManager recManager;
    private volatile boolean isClosed = false;

    public OcrService() {
    }

    public void init() throws Exception {
        this.detManager = new OnnxDetManager(AppConfig.OCR_DET_MODEL);
        this.recManager = new OnnxRecManager(AppConfig.OCR_REC_MODEL);

        this.detFloatBuffer = ByteBuffer.allocateDirect(3 * 1024 * 1024 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        this.recFloatBuffer = ByteBuffer.allocateDirect(3 * 52 * 2048 * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();

        log.info("OCR 服务原生版初始化完成 (JavaCPP)");
    }

    public List<String> recognizeAll(byte[] grayData, int width, int height) {
        if (isClosed || grayData == null) return Collections.emptyList();

        if (fullMat == null || width != fullW || height != fullH) {
            if (fullMat != null) fullMat.close();
            fullMat = new Mat(height, width, opencv_core.CV_8UC1);
            fullW = width;
            fullH = height;
        }
        fullMat.data().put(grayData);

        try {
            int detW = alignTo(width, DET_ALIGNMENT);
            int detH = alignTo(height, DET_ALIGNMENT);

            LetterboxInfo info = new LetterboxInfo();
            updateLetterbox(fullMat, detW, detH, info);

            FloatBuffer detBuffer = fastBuildTensor(letterboxMat, detW, detH, true);
            float[] heatMap = detManager.detect(detBuffer, detH, detW);

            List<Rect> boxes = extractTextLineBoxes(heatMap, detH, detW, width, height, info);
            if (boxes.isEmpty()) return Collections.emptyList();

            List<String> resultList = new ArrayList<>(boxes.size());
            for (Rect box : boxes) {
                // try-with-resources 确保子图 Mat 在 nopointergc 下被释放
                try (Mat lineHeader = fullMat.apply(box)) {
                    if (lineHeader.empty()) continue;

                    int recW = alignTo((int) (lineHeader.cols() * (double) REC_STD_HEIGHT / lineHeader.rows()), REC_WIDTH_ALIGNMENT);
                    FloatBuffer recBuffer = fastBuildTensor(lineHeader, recW, REC_STD_HEIGHT, false);
                    String text = recManager.recognize(recBuffer, REC_STD_HEIGHT, recW);

                    text = text.replaceAll("[^\\u4e00-\\u9fa5xX×*0-9a-zA-Z]", "").trim();
                    if (!text.isEmpty()) resultList.add(text);
                }
            }
            return resultList;

        } catch (Exception e) {
            log.error("OCR 匹配链路异常", e);
            return Collections.emptyList();
        }
    }

    private void updateLetterbox(Mat src, int dstW, int dstH, LetterboxInfo info) {
        int srcW = src.cols();
        int srcH = src.rows();
        double scale = Math.min((double) dstW / srcW, (double) dstH / srcH);
        int newW = (int) Math.round(srcW * scale);
        int newH = (int) Math.round(srcH * scale);

        int padX = (dstW - newW) / 2;
        int padY = (dstH - newH) / 2;

        opencv_imgproc.resize(src, tempResized, new Size(newW, newH));
        opencv_core.copyMakeBorder(tempResized, letterboxMat, padY, dstH - newH - padY,
                padX, dstW - newW - padX, opencv_core.BORDER_CONSTANT, new Scalar(0));
    }

    private FloatBuffer fastBuildTensor(Mat src, int tw, int th, boolean isDet) {
        Mat targetMat = isDet ? detResizedMat : recResizedMat;
        FloatBuffer buffer = isDet ? detFloatBuffer : recFloatBuffer;

        opencv_imgproc.resize(src, targetMat, new Size(tw, th), 0, 0, opencv_imgproc.INTER_LINEAR);

        int size = tw * th;
        buffer.clear();

        if (rowCache.length < tw) rowCache = new byte[tw + 512];

        int dataLen = 3 * size;
        if (dataCache.length < dataLen) {
            dataCache = new float[dataLen];
        }

        for (int y = 0; y < th; y++) {
            new BytePointer(targetMat.ptr(y, 0)).get(rowCache, 0, tw);
            for (int x = 0; x < tw; x++) {
                int gray = rowCache[x] & 0xFF;
                float val = (gray > BINARY_THRESHOLD) ? 0.0f : 1.0f;
                int idx = y * tw + x;
                float norm = (val - 0.5f) / 0.5f;
                dataCache[idx] = norm;
                dataCache[size + idx] = norm;
                dataCache[2 * size + idx] = norm;
            }
        }

        buffer.put(dataCache);
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

        if (fullMat != null) fullMat.close();
        detResizedMat.close();
        recResizedMat.close();
        letterboxMat.close();
        tempResized.close();

        detFloatBuffer = null;
        recFloatBuffer = null;

        log.info("OCR 服务已安全关闭");
    }

    private static class LetterboxInfo {
        int srcNewW, srcNewH, padX, padY;
    }
}
