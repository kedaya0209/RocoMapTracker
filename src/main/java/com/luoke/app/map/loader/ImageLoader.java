package com.luoke.app.map.loader;

import com.luoke.app.utils.ResourceUtils;
import javafx.scene.image.Image;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ImageLoader {
    private static final ImageLoader INSTANCE = new ImageLoader();
    private static final int MAX_ICON_SIZE = 32;
    private static final boolean SMOOTH_SCALE = true;

    // 强引用缓存，不给 GC 回收图标的机会，确保渲染循环 0 延迟
    private final Map<String, Image> imageCache = new ConcurrentHashMap<>();

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

    public void clearCache() {
        imageCache.clear();
    }
}