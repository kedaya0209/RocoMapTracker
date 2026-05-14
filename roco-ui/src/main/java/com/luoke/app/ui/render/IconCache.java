package com.luoke.app.ui.render;

import com.luoke.app.map.loader.ImageLoader;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI 层图标缓存 — 用 JavaFX 原生解码器将 byte[] 转为 Image，
 * 在期望尺寸 (32x32) 直接解码，质量优于 AWT Graphics2D 缩放。
 */
public class IconCache {

    private static final int SIZE = 32;

    private final Map<String, Image> colorCache = new ConcurrentHashMap<>();
    private final Map<String, Image> grayCache = new ConcurrentHashMap<>();

    private static final IconCache INSTANCE = new IconCache();

    private IconCache() {
    }

    public static IconCache getInstance() {
        return INSTANCE;
    }

    /**
     * 获取彩色图标（32x32，高质量缩放）
     */
    public Image getIcon(String path) {
        return colorCache.computeIfAbsent(path, k -> {
            byte[] bytes = ImageLoader.getInstance().loadIconBytes(k);
            if (bytes == null) return null;
            return new Image(new ByteArrayInputStream(bytes), SIZE, SIZE, true, true);
        });
    }

    /**
     * 获取灰度图标（32x32），灰度转换只做一次并缓存
     */
    public Image getGrayIcon(String path) {
        return grayCache.computeIfAbsent(path, k -> {
            Image color = getIcon(path);
            if (color == null) return null;
            return toGray(color);
        });
    }

    /** PixelReader 逐像素转灰度 */
    private static Image toGray(Image color) {
        int w = (int) color.getWidth();
        int h = (int) color.getHeight();
        PixelReader reader = color.getPixelReader();
        WritableImage gray = new WritableImage(w, h);
        PixelWriter writer = gray.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = reader.getArgb(x, y);
                int a = (argb >> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int lum = (int)(0.299 * r + 0.587 * g + 0.114 * b);
                writer.setArgb(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        return gray;
    }

    public void clear() {
        colorCache.clear();
        grayCache.clear();
    }
}
