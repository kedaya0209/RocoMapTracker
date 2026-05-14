package com.luoke.app.map.loader;

import com.luoke.app.utils.ResourceUtils;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 原始图标字节缓存 — 只缓存 PNG byte[]，不做解码/缩放，由 UI 层负责渲染。
 */
public class ImageLoader {
    private static final ImageLoader INSTANCE = new ImageLoader();

    private final Map<String, byte[]> byteCache = new ConcurrentHashMap<>();

    private ImageLoader() {
    }

    public static ImageLoader getInstance() {
        return INSTANCE;
    }

    /**
     * 返回原始 PNG 字节，由 UI 层自行解码与缩放。
     */
    public byte[] loadIconBytes(String resourcePath) {
        return byteCache.computeIfAbsent(resourcePath, path -> {
            try (InputStream ins = ResourceUtils.getResourceStream(path)) {
                return ins.readAllBytes();
            } catch (Exception e) {
                return null;
            }
        });
    }

    public void clearCache() {
        byteCache.clear();
    }
}
