package com.luoke.app.capture.processor;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class SaveImageProcessor implements RoiProcessor {

    private final int roiIndex;
    private final String saveDir;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private long lastSaveTime = 0;

    private final int count = 0;

    public SaveImageProcessor(int roiIndex, String saveDir) {
        this.roiIndex = roiIndex;
        this.saveDir = saveDir;
        new File(saveDir).mkdirs();
    }

    @Override
    public int targetRoiIndex() {
        return this.roiIndex;
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        if (data == null || data.length == 0) return;
        boolean a = true;
        if (a) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSaveTime < 1000) {
            return;
        }
        lastSaveTime = currentTime;

        // 创建灰度图
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(data, 0, targetPixels, 0, data.length);

        // 异步保存完整图和中心裁剪64x64
        saveImageAsync(image, width, height);
    }

    private void saveImageAsync(BufferedImage image, int width, int height) {
        String baseName = String.format("gray_roi_%d_%s_%dx%d",
                roiIndex, LocalDateTime.now().format(formatter), width, height);
        File fullFile = new File(saveDir, baseName + ".png");

        Thread.ofVirtual().start(() -> {
            try {
                // 保存完整图
                ImageIO.write(image, "png", fullFile);
//                log.info("灰度ROI已保存: {}", fullFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("保存灰度图失败: {}", e.getMessage());
            }
        });
    }

    @Override
    public ROIData getRoi() {
        return null;
    }
}