package com.luoke.app.capture.processor;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 调试用 — 将 ROI 帧直接保存为 PNG 文件到磁盘。
 */
@NotThreadSafe
@Slf4j
public class SaveImageProcessor implements RoiProcessor {

    private final int roiIndex;
    private final Path saveDir;
    private long lastSaveTime = 0;
    private int fileIndex = 0;

    public SaveImageProcessor(int roiIndex, Path saveDir) {
        this.roiIndex = roiIndex;
        this.saveDir = saveDir;
    }

    @Override
    public int targetRoiIndex() {
        return this.roiIndex;
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        if (data == null || data.length == 0) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSaveTime < 1000) return;
        lastSaveTime = currentTime;

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < width * height; i++) {
            int b = data[i * 4] & 0xFF;
            int g = data[i * 4 + 1] & 0xFF;
            int r = data[i * 4 + 2] & 0xFF;
            int a = data[i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);

        try {
            Files.createDirectories(saveDir);
            Path file = saveDir.resolve(String.format("roi_%d_%04d.png", roiIndex, fileIndex++));
            ImageIO.write(image, "png", file.toFile());
            log.debug("ROI 帧已保存: {}", file);
        } catch (IOException e) {
            log.error("保存 ROI 帧失败", e);
        }
    }

    @Override
    public ImageType requiredImageType() {
        return ImageType.BGRA;
    }

    @Override
    public ROIData getRoi() {
        return null;
    }
}
