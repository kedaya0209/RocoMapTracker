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
    // --- 核心模型参数 ---
    private static final int DET_SIZE = 256;
    private static final int REC_STD_HEIGHT = 48;
    private static final float TEXT_HEAT_THRESHOLD = 0.5f;
    private static final int TEXT_MIN_PIXEL = 30;

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
        log.info("✅ OCR 服务初始化完成（已集成 JavaCV 预检测拦截器）");
    }

    public List<String> recognizeAll(byte[] imageBytes) {
        try {
            //读取原始图片
            BufferedImage fullImg = readImage(imageBytes);

            //【检测模型】使用专属 Tensor 构造器 (ImageNet 归一化)
            float[] detTensor = buildDetTensor(fullImg, DET_SIZE, DET_SIZE);
            float[][] heatMap = detManager.detect(detTensor, DET_SIZE, DET_SIZE);

            List<Rectangle> lineBoxes = extractTextLineBox(heatMap, fullImg.getWidth(), fullImg.getHeight());
            if (lineBoxes.isEmpty()) return Collections.emptyList();

            List<String> resultList = new ArrayList<>();
            for (Rectangle box : lineBoxes) {
                // 扩边处理
                int expandY = 2; // 稍微加大，保证笔画完整
                int expandX = 4;
                int y = Math.max(0, box.y - expandY);
                int h = Math.min(fullImg.getHeight() - y, box.height + expandY * 2);
                int x = Math.max(0, box.x - expandX);
                int w = Math.min(fullImg.getWidth() - x, box.width + expandX * 2);

                BufferedImage lineCrop = fullImg.getSubimage(x, y, w, h);

                // 第四步：【识别模型】使用专属 Tensor 构造器 (0.5 归一化)
                int recW = (int) (lineCrop.getWidth() * REC_STD_HEIGHT / (double) lineCrop.getHeight());
                float[] recTensor = buildRecTensor(lineCrop, recW, REC_STD_HEIGHT);

                String text = recManager.recognize(recTensor, REC_STD_HEIGHT, recW);

                // 业务正则清洗
                text = text.replaceAll("(?<=[xX×*]\\d).*$", "").trim();
                if (!text.isEmpty()) resultList.add(text);
            }
            return resultList;
        } catch (Exception e) {
            log.error("识别异常", e);
            return Collections.emptyList();
        }
    }

    /**
     * 【检测模型专用】均值/方差：ImageNet 标准 (0.485, 0.456, 0.406)
     */
    private float[] buildDetTensor(BufferedImage src, int targetW, int targetH) {
        return buildTensorCommon(src, targetW, targetH, 0.485f, 0.229f, 0.456f, 0.224f, 0.406f, 0.225f);
    }

    /**
     * 【识别模型专用】均值/方差：固定 0.5 (这是 PaddleOCR 识别模型的标准)
     */
    private float[] buildRecTensor(BufferedImage src, int targetW, int targetH) {
        return buildTensorCommon(src, targetW, targetH, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
    }

    private float[] buildTensorCommon(BufferedImage src, int tw, int th, float mr, float sr, float mg, float sg, float mb, float sb) {
        BufferedImage res = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = res.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();

        int[] pixels = ((DataBufferInt) res.getRaster().getDataBuffer()).getData();
        float[] data = new float[3 * tw * th];
        for (int i = 0; i < pixels.length; i++) {
            data[i] = (((pixels[i] >> 16) & 0xFF) / 255.0f - mr) / sr;
            data[tw * th + i] = (((pixels[i] >> 8) & 0xFF) / 255.0f - mg) / sg;
            data[2 * tw * th + i] = ((pixels[i] & 0xFF) / 255.0f - mb) / sb;
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