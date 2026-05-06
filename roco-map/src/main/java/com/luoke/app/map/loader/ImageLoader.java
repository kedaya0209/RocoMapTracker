package com.luoke.app.map.loader;

import com.luoke.app.utils.ResourceUtils;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ImageLoader {
    private static final ImageLoader INSTANCE = new ImageLoader();
    private static final int MAX_ICON_SIZE = 32;
    private static final boolean SMOOTH_SCALE = true;

    // 强引用缓存，不给 GC 回收图标的机会，确保渲染循环 0 延迟
    private final Map<String, Image> imageCache = new ConcurrentHashMap<>();
    private final Map<String, Image> imageGrayCache = new ConcurrentHashMap<>();

    private ImageLoader() {
    }

    public static ImageLoader getInstance() {
        return INSTANCE;
    }

    public Image loadScaledIcon(String resourcePath) {
        return imageCache.computeIfAbsent(resourcePath, path -> {
            try (var ins = ResourceUtils.getResourceStream(path)) {
                Image result = new Image(ins, MAX_ICON_SIZE, MAX_ICON_SIZE, true, SMOOTH_SCALE);
                if (result.isError()) return null;
                return result;
            } catch (Exception e) {
                return null;
            }
        });
    }

    /**
     * 加载预计算灰度版图标，避免渲染时使用 setEffect(GRAY_EFFECT) 创建 GPU 离屏缓冲区
     */
    public Image loadGrayIcon(String resourcePath) {
        return imageGrayCache.computeIfAbsent(resourcePath, path -> {
            Image colorIcon = loadScaledIcon(path);
            if (colorIcon == null) return null;
            return createGrayscale(colorIcon);
        });
    }

    private static Image createGrayscale(Image source) {
        int w = (int) source.getWidth();
        int h = (int) source.getHeight();
        WritableImage gray = new WritableImage(w, h);
        PixelReader reader = source.getPixelReader();
        PixelWriter writer = gray.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color c = reader.getColor(x, y);
                double lum = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
                writer.setColor(x, y, new Color(lum, lum, lum, c.getOpacity()));
            }
        }
        return gray;
    }

    public void clearCache() {
        imageCache.clear();
        imageGrayCache.clear();
    }
}