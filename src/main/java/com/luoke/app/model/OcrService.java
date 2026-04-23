package com.luoke.app.model;

import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OCR 核心服务类
 * 负责：图像预处理、文字行切割、识别调度以及结果的业务逻辑清洗
 */
@Slf4j
public class OcrService implements AutoCloseable {
    /**
     * DET_SIZE: 检测模型的输入分辨率。
     * 修改影响：必须是32的倍数。
     * - 调大（如416, 640）：检测精度更高，能发现更小的文字，但推理速度显著变慢，内存占用增加。
     * - 调小（如256）：速度极快，适合处理文字占比较大的游戏截图；若文字太细小可能会漏检。
     */
    private static final int DET_SIZE = 256;
    /**
     * REC_STD_HEIGHT: 识别模型的标准输入高度。
     * 修改影响：PaddleOCR官方模型通常在48px高度下训练，保持48识别率最高。修改会导致字符形变，识别率大幅下降。
     */
    private static final int REC_STD_HEIGHT = 48;
    /**
     * TEXT_HEAT_THRESHOLD: 检测模型热力图的二值化阈值。
     * 修改影响：
     * - 调低（如0.3）：会让检测更“敏感”，能框选出模糊的边缘，但容易受到背景杂色干扰。
     * - 调高（如0.7）：更“严谨”，只有字迹非常清晰才会被框选，可减少误报。
     */
    private static final float TEXT_HEAT_THRESHOLD = 0.5f;
    /**
     * TEXT_MIN_PIXEL: 判定为文字的最小像素面积阈值。
     * 修改影响：防止背景里的噪点（如一个光点）被误认为是一个文字。
     */
    private static final int TEXT_MIN_PIXEL = 30;
    private OnnxDetManager detManager;
    private OnnxRecManager recManager;

    public void init() throws Exception {
        String detPath = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + AppConfig.OCR_DET_MODEL);
        String recPath = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + AppConfig.OCR_REC_MODEL);
        String keysPath = ResourceUtils.getExternalPath(AppConfig.MODEL_DIR + AppConfig.PPOCR_KEYS);

        byte[] detBytes = Files.readAllBytes(Paths.get(detPath));
        byte[] recBytes = Files.readAllBytes(Paths.get(recPath));
        List<String> dict = Files.readAllLines(Paths.get(keysPath));

        this.detManager = new OnnxDetManager(detBytes);
        this.recManager = new OnnxRecManager(recBytes, dict);
        log.info("✅ OCR 服务初始化完成 (高性能 DirectBuffer + 脏字正则过滤已就绪)");
    }

    public boolean hasText(byte[] imageBytes) {
        try {
            BufferedImage src = readImage(imageBytes);
            float[] detTensor = buildTensor(src, DET_SIZE, DET_SIZE);
            float[][] heatMap = detManager.detect(detTensor, DET_SIZE, DET_SIZE);
            return isHasText(heatMap);
        } catch (Exception e) {
            log.error("文字检测异常", e);
            return false;
        }
    }

    /**
     * 完整识别逻辑
     */
    public List<String> recognizeAll(byte[] imageBytes) {
        try {
            BufferedImage fullImg = readImage(imageBytes);
            float[] detTensor = buildTensor(fullImg, DET_SIZE, DET_SIZE);
            float[][] heatMap = detManager.detect(detTensor, DET_SIZE, DET_SIZE);

            List<Rectangle> lineBoxes = extractTextLineBox(heatMap, fullImg.getWidth(), fullImg.getHeight());
            if (lineBoxes.isEmpty()) {
                return Collections.emptyList();
            }

            List<String> resultList = new ArrayList<>();
            for (Rectangle box : lineBoxes) {
                /**
                 * 扩边处理 (expandY/expandX):
                 * 原因：检测算法生成的框往往非常紧凑，容易切掉文字的笔画边缘（如“石”字最上面的一横）。
                 * 修改影响：
                 * - expandY 过大：可能会把相邻行的文字也切进来，导致识别重影。
                 * - expandY=0：会导致文字笔画不全，识别率下降。这里取1-2像素是平衡点。
                 */
                int expandY = 1;
                int expandX = 4;
                int y = Math.max(0, box.y - expandY);
                int h = Math.min(fullImg.getHeight() - y, box.height + expandY * 2);
                int x = Math.max(0, box.x - expandX);
                int w = Math.min(fullImg.getWidth() - x, box.width + expandX * 2);

                BufferedImage lineCrop = fullImg.getSubimage(x, y, w, h);

                // 采用灰度化处理：在保留笔画细节的同时去除颜色背景干扰
//                BufferedImage enhanceImg = softEnhance(lineCrop);

                int recW = (int) (lineCrop.getWidth() * REC_STD_HEIGHT / (double) lineCrop.getHeight());
                float[] recTensor = buildTensor(lineCrop, recW, REC_STD_HEIGHT);
                String text = recManager.recognize(recTensor, REC_STD_HEIGHT, recW);

                /**
                 * 业务正则清理 (核心优化):
                 * 场景：专门解决游戏UI末尾识别出脏字符（如“雪”、“一”等）。
                 * 原理：查找“x数量”或“×数量”结构，利用正则“正向回顾”保留到数字，删除其后所有内容。
                 * 修改影响：如果你的道具名本身结尾就带数字且没有乘号，需修改正则逻辑。
                 */
                text = text.replaceAll("(?<=[xX×*]\\d).*$", "");

                if (!text.isBlank()) {
                    resultList.add(text);
                }
            }
            return resultList;
        } catch (Exception e) {
            log.error("全图识别异常", e);
            return Collections.emptyList();
        }
    }

    private BufferedImage readImage(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    /**
     * 高性能 Tensor 构造器
     * 修改点：放弃了速度极慢的 BufferedImage.getRGB(x,y)，直接抓取底层 DataBuffer 数组。
     * 效果：在处理大图时，图像预处理速度可提升 5 倍以上。
     */
    private float[] buildTensor(BufferedImage src, int targetW, int targetH) {
        BufferedImage resizeImg = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = resizeImg.createGraphics();
        // BILINEAR 插值在速度与平滑度间达到平衡
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(src, 0, 0, targetW, targetH, null);
        g2d.dispose();

        int total = targetW * targetH;
        float[] data = new float[3 * total];

        // 直接操作内存数组
        int[] pixels = ((DataBufferInt) resizeImg.getRaster().getDataBuffer()).getData();

        for (int i = 0; i < total; i++) {
            int rgb = pixels[i];
            // 归一化公式: (x / 255.0 - mean) / std
            data[i] = (((rgb >> 16) & 0xFF) / 255.0f - 0.485f) / 0.229f;
            data[total + i] = (((rgb >> 8) & 0xFF) / 255.0f - 0.456f) / 0.224f;
            data[2 * total + i] = ((rgb & 0xFF) / 255.0f - 0.406f) / 0.225f;
        }
        return data;
    }

    private boolean isHasText(float[][] heatMap) {
        int count = 0;
        for (float[] row : heatMap) {
            for (float val : row) {
                if (val >= TEXT_HEAT_THRESHOLD) {
                    count++;
                    if (count >= TEXT_MIN_PIXEL) return true;
                }
            }
        }
        return count >= TEXT_MIN_PIXEL;
    }

    private List<Rectangle> extractTextLineBox(float[][] heatMap, int srcW, int srcH) {
        List<Rectangle> boxList = new ArrayList<>();
        int mapH = heatMap.length;
        int mapW = heatMap[0].length;
        float scaleX = (float) srcW / mapW;
        float scaleY = (float) srcH / mapH;

        Integer lineStartY = null;
        for (int y = 0; y < mapH; y++) {
            boolean hasTextLine = false;
            for (int x = 0; x < mapW; x++) {
                if (heatMap[y][x] >= TEXT_HEAT_THRESHOLD) {
                    hasTextLine = true;
                    break;
                }
            }
            if (hasTextLine) {
                if (lineStartY == null) lineStartY = y;
            } else {
                if (lineStartY != null) {
                    int top = (int) (lineStartY * scaleY);
                    int bottom = (int) (y * scaleY);
                    boxList.add(new Rectangle(0, top, srcW, bottom - top));
                    lineStartY = null;
                }
            }
        }
        if (lineStartY != null) {
            int top = (int) (lineStartY * scaleY);
            boxList.add(new Rectangle(0, top, srcW, srcH - top));
        }
        return boxList;
    }

    private BufferedImage softEnhance(BufferedImage src) {
        // 使用标准的灰度转换，避免极高对比度导致的笔画断裂
        BufferedImage gray = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
        Graphics g = gray.getGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return gray;
    }

    @Override
    public void close() throws Exception {
        if (detManager != null) detManager.close();
        if (recManager != null) recManager.close();
    }
}