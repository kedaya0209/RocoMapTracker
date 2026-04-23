package com.luoke.app.map.loader;

import com.luoke.app.utils.ResourceUtils;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

import java.lang.ref.SoftReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高性能图标加载器
 * 解决透明背景变白问题，并实现高质量（无损感官）缩放
 */
public class ImageLoader {
    private static final ImageLoader INSTANCE = new ImageLoader();
    // ====================== 【压缩参数】======================
    private static final int MAX_ICON_SIZE = 32;
    private static final boolean SMOOTH_SCALE = true; // 开启平滑插值，实现无损观感
    // 使用软引用缓存，防止 20 小时连续开发导致的堆内存溢出 (OOM)
    private final Map<String, SoftReference<Image>> imageCache = new ConcurrentHashMap<>();

    private ImageLoader() {
    }

    public static ImageLoader getInstance() {
        return INSTANCE;
    }

    /**
     * 加载并进行高质量压缩
     *
     * @param resourcePath 资源路径
     * @return 缩放后的 Image 对象
     */
    public Image loadScaledIcon(String resourcePath) {
        // 1. 检查缓存
        SoftReference<Image> ref = imageCache.get(resourcePath);
        if (ref != null) {
            Image cached = ref.get();
            if (cached != null) return cached;
        }

        try (var ins = ResourceUtils.getResourceStream(resourcePath)) {
            // 2. 直接在加载流时缩放 (JavaFX 底层原生缩放，效率最高)
            // 参数：流, 目标宽, 目标高, 保持比例, 是否平滑
            Image result = new Image(ins, MAX_ICON_SIZE, MAX_ICON_SIZE, true, SMOOTH_SCALE);

            if (result.isError()) {
                System.err.println("图片加载失败: " + resourcePath);
                return null;
            }

            // 3. 放入缓存
            imageCache.put(resourcePath, new SoftReference<>(result));
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 【备用方法】手动对已有 Image 进行高质量缩放并保持透明
     * 解决你之前遇到的 snapshot 变白问题
     */
    public Image resizeImage(Image original, int maxSize) {
        if (original == null || original.isError()) return original;
        if (original.getWidth() <= maxSize && original.getHeight() <= maxSize) return original;

        double scale = Math.min((double) maxSize / original.getWidth(), (double) maxSize / original.getHeight());
        int targetW = (int) Math.max(1, original.getWidth() * scale);
        int targetH = (int) Math.max(1, original.getHeight() * scale);

        ImageView imageView = new ImageView(original);
        imageView.setFitWidth(targetW);
        imageView.setFitHeight(targetH);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true); // 核心：开启抗锯齿

        // 关键：解决背景变白
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT); // 设置快照填充色为透明

        WritableImage output = new WritableImage(targetW, targetH);
        return imageView.snapshot(params, output);
    }

    /**
     * 显式清理缓存，建议在切换大地图时调用
     */
    public void clearCache() {
        imageCache.clear();
    }
}