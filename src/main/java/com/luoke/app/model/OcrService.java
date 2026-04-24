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
    // 删除了固定的 DET_SIZE，改为动态计算
    private static final int REC_STD_HEIGHT = 48;
    private static final float TEXT_HEAT_THRESHOLD = 0.5f;

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
        log.info("✅ OCR 服务初始化完成（动态输入尺寸版）");
    }

    public List<String> recognizeAll(byte[] imageBytes) {
        try {
            BufferedImage fullImg = readImage(imageBytes);
            int srcW = fullImg.getWidth();
            int srcH = fullImg.getHeight();

            // --- 【核心修改：动态计算检测尺寸】 ---
            // 按照 32 倍数对齐。例如 210 -> 224
            int dynamicDetW = align32(srcW);
            int dynamicDetH = align32(srcH);

            // 【检测模型】传入动态计算的宽和高
            float[] detTensor = buildDetTensor(fullImg, dynamicDetW, dynamicDetH);
            // 推理时同样传入动态的高和宽（注意参数顺序：通常是 det(tensor, h, w)）
            float[][] heatMap = detManager.detect(detTensor, dynamicDetH, dynamicDetW);

            // extractTextLineBox 已经支持比例缩放，无需修改
            List<Rectangle> lineBoxes = extractTextLineBox(heatMap, srcW, srcH);
            if (lineBoxes.isEmpty()) return Collections.emptyList();

            List<String> resultList = new ArrayList<>();
            for (Rectangle box : lineBoxes) {
                int expandY = 2;
                int expandX = 4;
                int y = Math.max(0, box.y - expandY);
                int h = Math.min(srcH - y, box.height + expandY * 2);
                int x = Math.max(0, box.x - expandX);
                int w = Math.min(srcW - x, box.width + expandX * 2);

                BufferedImage lineCrop = fullImg.getSubimage(x, y, w, h);

                // 识别模型（CRNN）本身就是动态宽度的，保持不变
                int recW = (int) (lineCrop.getWidth() * REC_STD_HEIGHT / (double) lineCrop.getHeight());
                float[] recTensor = buildRecTensor(lineCrop, recW, REC_STD_HEIGHT);

                String text = recManager.recognize(recTensor, REC_STD_HEIGHT, recW);
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
     * 将尺寸向上对齐到 32 的倍数
     */
    private int align32(int size) {
        return (int) Math.ceil(size / 32.0) * 32;
    }

    // --- 剩下的辅助方法根据动态参数微调 ---

    private float[] buildDetTensor(BufferedImage src, int targetW, int targetH) {
        return buildTensorCommon(src, targetW, targetH, 0.485f, 0.229f, 0.456f, 0.224f, 0.406f, 0.225f);
    }

    private float[] buildRecTensor(BufferedImage src, int targetW, int targetH) {
        return buildTensorCommon(src, targetW, targetH, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f);
    }

    private float[] buildTensorCommon(BufferedImage src, int tw, int th, float mr, float sr, float mg, float sg, float mb, float sb) {
        // 创建动态大小的中间图片
        BufferedImage res = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = res.createGraphics();
        // 小图拉伸到 32 倍数建议用 BILINEAR，如果是为了清晰度可以尝试 BICUBIC
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
        // 注意：如果宽度不是 1:1，scaleX 也可以根据需要计算，
        // 但你目前的逻辑是取整行，所以 srcW 保持不变是正确的。
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