package com.luoke.app.capture.processor.impl;

import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.RoiProcessor;
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

        // 优化采样逻辑：每 10 秒保存一张，而不是在整 10 秒的那一秒内疯狂保存
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSaveTime < 10000) {
            return;
        }
        lastSaveTime = currentTime;

        // 1. 创建灰度图类型的 BufferedImage
        // Rust 传过来的是 8-bit 灰度，对应 Java 的 TYPE_BYTE_GRAY
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);

        // 2. 将紧凑的灰度字节数组拷贝到 BufferedImage 的底层 Buffer
        byte[] targetPixels = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(data, 0, targetPixels, 0, data.length);

        // 3. 异步保存防止阻塞 Rust 的回调线程
        saveImageAsync(image, width, height);
    }

    private void saveImageAsync(BufferedImage image, int width, int height) {
        String fileName = String.format("gray_roi_%d_%s_%dx%d.png",
                roiIndex, LocalDateTime.now().format(formatter), width, height);
        File outputFile = new File(saveDir, fileName);

        // 开启新线程写入，保证回调接口的极致性能
        new Thread(() -> {
            try {
                ImageIO.write(image, "png", outputFile);
                log.info("灰度ROI已保存: {}", outputFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("保存灰度图失败: {}", e.getMessage());
            }
        }).start();
    }

    @Override
    public ROIData getRoi() {
        return null;
    }
}