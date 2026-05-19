package com.luoke.app.ui.service;

import com.luoke.app.config.RenderConfig;
import com.luoke.app.map.loader.ImageLoader;
import javafx.scene.image.*;
import lombok.Getter;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UI 层图标缓存 — 用 JavaFX 原生解码器将 byte[] 转为 Image，
 * 在期望尺寸 (32x32) 直接解码，质量优于 AWT Graphics2D 缩放。
 *
 * <p>支持纹理图集模式：启动时调用 {@link #buildAtlas(Set)} 将所有图标
 * 合并为单张 WritableImage，全量重绘时减少 GPU 纹理绑定次数。
 */
public class IconCache {

    private static final int SIZE = (int) RenderConfig.ICON_SIZE;
    private static final IconCache INSTANCE = new IconCache();
    // 兜底缓存
    private final Map<String, Image> colorCache = new ConcurrentHashMap<>();
    private final Map<String, Image> grayCache = new ConcurrentHashMap<>();
    /**
     * -- GETTER --
     * 获取彩色图集
     */
    // 图集
    @Getter
    private volatile Image colorAtlas;
    /**
     * -- GETTER --
     * 获取灰度图集
     */
    @Getter
    private volatile Image grayAtlas;
    private volatile Map<String, AtlasSlot> slotMap;
    private volatile boolean atlasBuilt;

    private IconCache() {
    }

    public static IconCache getInstance() {
        return INSTANCE;
    }

    /**
     * PixelReader 逐像素转灰度
     */
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
                int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                writer.setArgb(x, y, (a << 24) | (lum << 16) | (lum << 8) | lum);
            }
        }
        return gray;
    }

    /**
     * 构建纹理图集。在资源点位加载完成后调用。
     *
     * @param iconPaths 所有图标路径的集合 (如 "/source/icon/ore_iron.png")
     */
    public void buildAtlas(Set<String> iconPaths) {
        if (iconPaths == null || iconPaths.isEmpty()) return;

        int count = iconPaths.size();
        int cols = (int) Math.ceil(Math.sqrt(count));
        int rows = (int) Math.ceil((double) count / cols);

        WritableImage colorImg = new WritableImage(cols * SIZE, rows * SIZE);
        WritableImage grayImg = new WritableImage(cols * SIZE, rows * SIZE);
        PixelWriter colorWriter = colorImg.getPixelWriter();
        PixelWriter grayWriter = grayImg.getPixelWriter();

        Map<String, AtlasSlot> map = new ConcurrentHashMap<>();
        int[] rowBuf = new int[SIZE];

        int idx = 0;
        for (String path : iconPaths) {
            byte[] bytes = ImageLoader.getInstance().loadIconBytes(path);
            if (bytes == null) {
                idx++;
                continue;
            }

            Image icon = new Image(new ByteArrayInputStream(bytes), SIZE, SIZE, false, true);
            PixelReader reader = icon.getPixelReader();
            int iw = (int) icon.getWidth();
            int ih = (int) icon.getHeight();

            int baseX = (idx % cols) * SIZE;
            int baseY = (idx / cols) * SIZE;

            for (int y = 0; y < ih; y++) {
                // 彩色像素行
                reader.getPixels(0, y, iw, 1, PixelFormat.getIntArgbPreInstance(), rowBuf, 0, iw);
                colorWriter.setPixels(baseX, baseY + y, iw, 1, PixelFormat.getIntArgbPreInstance(), rowBuf, 0, iw);

                // 灰度：逐像素计算亮度后写入
                for (int x = 0; x < iw; x++) {
                    int argb = rowBuf[x];
                    int a = (argb >> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    rowBuf[x] = (a << 24) | (lum << 16) | (lum << 8) | lum;
                }
                grayWriter.setPixels(baseX, baseY + y, iw, 1, PixelFormat.getIntArgbPreInstance(), rowBuf, 0, iw);
            }

            map.put(path, new AtlasSlot(baseX, baseY));
            idx++;
        }

        this.slotMap = map;
        this.colorAtlas = colorImg;
        this.grayAtlas = grayImg;
        this.atlasBuilt = true;
    }

    public boolean isAtlasReady() {
        return atlasBuilt;
    }

    /**
     * 获取图标在图集中的坐标，未找到返回 null
     */
    public AtlasSlot getSlot(String path) {
        return slotMap != null ? slotMap.get(path) : null;
    }

    /**
     * 获取彩色图标（32x32，高质量缩放）
     * 图集模式下返回独立 Image 作为兜底，优先使用图集 API。
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
        return grayCache.computeIfAbsent(path, _ -> {
            Image color = getIcon(path);
            if (color == null) return null;
            return toGray(color);
        });
    }

    /**
     * 灰度图标是否在缓存或图集中
     */
    public boolean hasGray(String path) {
        return grayCache.containsKey(path) || (slotMap != null && slotMap.containsKey(path));
    }

    /**
     * 彩色图标是否在缓存或图集中
     */
    public boolean hasIcon(String path) {
        return colorCache.containsKey(path) || (slotMap != null && slotMap.containsKey(path));
    }

    /**
     * 图集就绪后清除单图标缓存，释放内存
     */
    public void clearIndividualCaches() {
        colorCache.clear();
        grayCache.clear();
    }

    public void clear() {
        colorCache.clear();
        grayCache.clear();
        colorAtlas = null;
        grayAtlas = null;
        slotMap = null;
        atlasBuilt = false;
    }

    /**
     * 图集中的图标坐标
     */
    public static class AtlasSlot {
        public final int sx; // 源 X（在图集中的像素位置）
        public final int sy; // 源 Y

        AtlasSlot(int sx, int sy) {
            this.sx = sx;
            this.sy = sy;
        }
    }
}
