package com.luoke.app.capture.processor;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 将 ROI 帧通过 HTTP 发送到 DatasetGeneratorServer 进行箭头标注处理。
 */
@NotThreadSafe
@Slf4j
public class SaveImageProcessor implements RoiProcessor {

    private static final String UPLOAD_URL = "http://127.0.0.1:8080/upload";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final int roiIndex;
    private long lastSaveTime = 0;

    public SaveImageProcessor(int roiIndex) {
        this.roiIndex = roiIndex;
    }

    @Override
    public int targetRoiIndex() {
        return this.roiIndex;
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        if (data == null || data.length == 0) return;

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastSaveTime < 1000) {
            return;
        }
        lastSaveTime = currentTime;

        byte[] pngBytes = encodeToPNG(data, width, height);
        if (pngBytes.length == 0) return;

        Thread.ofPlatform().daemon(true).name("save-image-upload").start(() -> uploadAsync(pngBytes));
    }

    private byte[] encodeToPNG(byte[] bgra, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < width * height; i++) {
            int b = bgra[i * 4] & 0xFF;
            int g = bgra[i * 4 + 1] & 0xFF;
            int r = bgra[i * 4 + 2] & 0xFF;
            int a = bgra[i * 4 + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bos);
            return bos.toByteArray();
        } catch (IOException e) {
            log.error("PNG 编码失败", e);
            return new byte[0];
        }
    }

    private void uploadAsync(byte[] pngBytes) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(UPLOAD_URL))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(pngBytes))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                log.debug("上传成功: {}", response.body());
            } else {
                log.warn("上传失败: {} {}", response.statusCode(), response.body());
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("HTTP 上传异常: {}", e.getMessage());
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