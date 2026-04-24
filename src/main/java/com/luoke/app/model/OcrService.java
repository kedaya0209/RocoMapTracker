package com.luoke.app.model;

import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_imgproc;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Slf4j
public class OcrService implements AutoCloseable {

    // ====================== 【优化后精度参数】 ======================
    private static final int REC_STD_HEIGHT = 52;           // 48 → 52 小字更清晰
    private static final float TEXT_HEAT_THRESHOLD = 0.35f; // 0.5 → 0.35 抓半透明文字
    private static final int EXPAND_X = 6;
    private static final int EXPAND_Y = 4;

    private OnnxDetManager detManager;
    private OnnxRecManager recManager;

    public void init() throws Exception {
        Loader.load(opencv_imgproc.class);
        log.info("✅ JavaCV 本地库加载成功");
        byte[] detBytes = ResourceUtils.readResourceBytes(AppConfig.MODEL_DIR + AppConfig.OCR_DET_MODEL);
        byte[] recBytes = ResourceUtils.readResourceBytes(AppConfig.MODEL_DIR + AppConfig.OCR_REC_MODEL);
        List<String> dict = ResourceUtils.readResourceLines(AppConfig.MODEL_DIR + AppConfig.PPOCR_KEYS);

        this.detManager = new OnnxDetManager(detBytes);
        this.recManager = new OnnxRecManager(recBytes, dict);
        log.info("✅ OCR 服务初始化完成（优化精度版）");
    }

    public List<String> recognizeAll(byte[] imageBytes) {
        try {
            BufferedImage fullImg = readImage(imageBytes);
            int srcW = fullImg.getWidth();
            int srcH = fullImg.getHeight();

            int dynamicDetW = align32(srcW);
            int dynamicDetH = align32(srcH);

            float[] detTensor = buildDetTensor(fullImg, dynamicDetW, dynamicDetH);
            float[][] heatMap = detManager.detect(detTensor, dynamicDetH, dynamicDetW);

            List<Rectangle> lineBoxes = extractTextLineBox(heatMap, srcW, srcH);
            if (lineBoxes.isEmpty()) return Collections.emptyList();

            List<String> resultList = new ArrayList<>();
            for (Rectangle box : lineBoxes) {
                int y = Math.max(0, box.y - EXPAND_Y);
                int h = Math.min(srcH - y, box.height + EXPAND_Y * 2);
                int x = Math.max(0, box.x - EXPAND_X);
                int w = Math.min(srcW - x, box.width + EXPAND_X * 2);

                BufferedImage lineCrop = fullImg.getSubimage(x, y, w, h);

                int recW = (int) (lineCrop.getWidth() * REC_STD_HEIGHT / (double) lineCrop.getHeight());
                float[] recTensor = buildRecTensor(lineCrop, recW, REC_STD_HEIGHT);

                String text = recManager.recognize(recTensor, REC_STD_HEIGHT, recW);

                // 只保留中文、数字、×
                text = text.replaceAll("[^\\u4e00-\\u9fa5xX×*0-9]", "").trim();
                if (!text.isEmpty()) resultList.add(text);
            }
            return resultList;
        } catch (Exception e) {
            log.error("识别异常", e);
            return Collections.emptyList();
        }
    }

    private int align32(int size) {
        return (int) Math.ceil(size / 32.0) * 32;
    }

    private float[] buildDetTensor(BufferedImage src, int targetW, int targetH) {
        return buildTensorCommon(src, targetW, targetH, 0.485f, 0.229f, 0.456f, 0.224f, 0.406f, 0.225f);
    }

    private float[] buildRecTensor(BufferedImage src, int targetW, int targetH) {
        return buildTensorCommon(src, targetW, targetH, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
    }

    private float[] buildTensorCommon(BufferedImage src, int tw, int th, float mr, float sr, float mg, float sg, float mb, float sb) {
        BufferedImage res = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = res.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();

        int[] pixels = ((DataBufferInt) res.getRaster().getDataBuffer()).getData();
        float[] data = new float[3 * tw * th];

        for (int i = 0; i < pixels.length; i++) {
            int rgb = pixels[i];
            int r = (rgb >> 16) & 0xFF;
            int g_val = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            // 灰度化
            float gray = (r * 0.299f + g_val * 0.587f + b * 0.114f);

            // 【核心增强】：如果亮度低于 115，认为是背景噪声，设为 0（黑色）
            // 亮色文字将被显著突出，边缘笔画更清晰，减少错别字
            float finalVal = (gray > 115) ? gray / 255.0f : 0.0f;

            data[i] = (finalVal - mr) / sr;
            data[tw * th + i] = (finalVal - mg) / sg;
            data[2 * tw * th + i] = (finalVal - mb) / sb;
        }
        return data;
    }

    private BufferedImage readImage(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    private List<Rectangle> extractTextLineBox(float[][] heatMap, int srcW, int srcH) {
        List<Rectangle> boxList = new ArrayList<>();
        int mapH = heatMap.length;
        int mapW = heatMap[0].length;
        float scaleY = (float) srcH / mapH;

        Integer startY = null;
        for (int y = 0; y < mapH; y++) {
            boolean hasText = false;
            for (int x = 0; x < mapW; x++) {
                if (heatMap[y][x] >= TEXT_HEAT_THRESHOLD) {
                    hasText = true;
                    break;
                }
            }
            if (hasText && startY == null) startY = y;
            else if (!hasText && startY != null) {
                boxList.add(new Rectangle(0, (int) (startY * scaleY), srcW, (int) ((y - startY) * scaleY)));
                startY = null;
            }
        }
        if (startY != null)
            boxList.add(new Rectangle(0, (int) (startY * scaleY), srcW, srcH - (int) (startY * scaleY)));
        return boxList;
    }

    @Override
    public void close() throws Exception {
        if (detManager != null) detManager.close();
        if (recManager != null) recManager.close();
    }
}