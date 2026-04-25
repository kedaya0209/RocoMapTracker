package com.luoke.app.model;

import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class OcrService implements AutoCloseable {

    private static final int REC_STD_HEIGHT = 52;
    private static final float TEXT_HEAT_THRESHOLD = 0.35f;
    private static final int EXPAND_X = 6;
    private static final int EXPAND_Y = 4;

    // 缓存只扩容不缩容，防止 Rec 阶段因为行宽不一导致的频繁 GC
    private static final ThreadLocal<float[]> FLOAT_CACHE = new ThreadLocal<>();

    private OnnxDetManager detManager;
    private OnnxRecManager recManager;

    public void init() throws Exception {
        Loader.load(opencv_imgproc.class);
        this.detManager = new OnnxDetManager(AppConfig.OCR_DET_MODEL);
        this.recManager = new OnnxRecManager(AppConfig.OCR_REC_MODEL);
        log.info("✅ OCR 服务已切换为纯 Native 处理模式");
    }

    /**
     * 接收原始字节，内部负责转 Mat 并立即释放
     */
    public List<String> recognizeAll(byte[] imageBytes) {
        if (imageBytes == null) return Collections.emptyList();

        // 1. 谁创建，谁释放：使用 OpenCV 解码，替代 ImageIO
        try (Mat fullMat = opencv_imgcodecs.imdecode(new Mat(imageBytes), opencv_imgcodecs.IMREAD_COLOR)) {
            if (fullMat.empty()) return Collections.emptyList();

            int srcW = fullMat.cols();
            int srcH = fullMat.rows();
            int detW = align32(srcW);
            int detH = align32(srcH);

            // 2. 检测阶段
            FloatBuffer detBuffer = buildTensor(fullMat, detW, detH, 0.485f, 0.229f, 0.456f, 0.224f, 0.406f, 0.225f);
            float[][] heatMap = detManager.detect(detBuffer, detH, detW);

            List<Rect> boxes = extractTextLineBoxes(heatMap, srcW, srcH);
            if (boxes.isEmpty()) return Collections.emptyList();

            List<String> resultList = new ArrayList<>();
            for (Rect box : boxes) {
                // 3. 抠图与识别：每个 lineCrop 必须显式释放
                try (Mat lineCrop = fullMat.apply(box)) {
                    int recW = (int) (lineCrop.cols() * (double) REC_STD_HEIGHT / lineCrop.rows());

                    FloatBuffer recBuffer = buildTensor(lineCrop, recW, REC_STD_HEIGHT, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
                    String text = recManager.recognize(recBuffer, REC_STD_HEIGHT, recW);

                    text = text.replaceAll("[^\\u4e00-\\u9fa5xX×*0-9]", "").trim();
                    if (!text.isEmpty()) resultList.add(text);
                }
            }
            return resultList;
        } catch (Exception e) {
            log.error("OCR 流程异常", e);
            return Collections.emptyList();
        }
    }

    private FloatBuffer buildTensor(Mat src, int tw, int th, float mr, float sr, float mg, float sg, float mb, float sb) {
        // 缩放图由 try-with-resources 管理
        try (Mat resized = new Mat()) {
            opencv_imgproc.resize(src, resized, new Size(tw, th), 0, 0, opencv_imgproc.INTER_LINEAR);

            int size = tw * th;
            float[] data = getSafeFloatCache(3 * size);

            float invSr = 1.0f / sr, invSg = 1.0f / sg, invSb = 1.0f / sb;

            // 直接通过指针访问堆外内存，速度远快于 BufferedImage.getRGB
            try (BytePointer ptr = resized.ptr()) {
                for (int i = 0; i < size; i++) {
                    int b = ptr.get(i * 3L) & 0xFF;
                    int g = ptr.get(i * 3L + 1) & 0xFF;
                    int r = ptr.get(i * 3L + 2) & 0xFF;

                    float gray = (r * 0.299f + g * 0.587f + b * 0.114f) / 255.0f;
                    float val = gray > 0.1f ? Math.min(1.0f, gray * 1.3f) : 0.0f;

                    // NCHW 排列
                    data[i] = (val - mr) * invSr;
                    data[size + i] = (val - mg) * invSg;
                    data[2 * size + i] = (val - mb) * invSb;
                }
            }
            // 使用 wrap 确保 DJL 识别的是有界 Buffer
            return FloatBuffer.wrap(data, 0, 3 * size);
        }
    }

    private List<Rect> extractTextLineBoxes(float[][] heatMap, int srcW, int srcH) {
        List<Rect> boxes = new ArrayList<>();
        int h = heatMap.length, w = heatMap[0].length;
        float scaleY = (float) srcH / h;

        Integer startY = null;
        for (int y = 0; y < h; y++) {
            boolean hasText = false;
            for (int x = 0; x < w; x++) {
                if (heatMap[y][x] >= TEXT_HEAT_THRESHOLD) {
                    hasText = true;
                    break;
                }
            }
            if (hasText && startY == null) startY = y;
            else if (!hasText && startY != null) {
                int rectY = Math.max(0, (int) (startY * scaleY) - EXPAND_Y);
                int rectH = Math.min(srcH - rectY, (int) ((y - startY) * scaleY) + EXPAND_Y * 2);
                boxes.add(new Rect(0, rectY, srcW, rectH));
                startY = null;
            }
        }
        return boxes;
    }

    private float[] getSafeFloatCache(int size) {
        float[] cache = FLOAT_CACHE.get();
        // 关键优化：只扩容不缩容，消除 Rec 阶段的数组抖动
        if (cache == null || cache.length < size) {
            cache = new float[size + (size >> 1)]; // 预留 50% 冗余
            FLOAT_CACHE.set(cache);
        }
        return cache;
    }

    private int align32(int size) {
        return (size + 31) & ~31;
    }

    @Override
    public void close() throws Exception {
        if (detManager != null) detManager.close();
        if (recManager != null) recManager.close();
    }
}