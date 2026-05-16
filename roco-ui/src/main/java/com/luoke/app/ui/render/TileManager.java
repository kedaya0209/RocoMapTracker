package com.luoke.app.ui.render;

import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.ResourceUtils;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * 瓦片管理器 — 多分辨率金字塔瓦片的加载、缓存与视图管理。
 * 独立于渲染循环，仅负责瓦片的生命周期和层级选择。
 */
@Slf4j
public class TileManager {

    private static final int TILE_SIZE = 256;

    private final Group worldGroup;
    private final int mapW, mapH;
    private final Map<String, ImageView> activeTiles = new HashMap<>();

    @Setter
    @Getter
    private int lastLevel = -1;

    // 缩放稳定延迟：缩放期间保持当前层级瓦片（GPU 缩放），稳定后再切换精确层级
    private int scaleStableFrames = 0;
    private static final int SCALE_STABLE_THRESHOLD = 5; // ~165ms

    public TileManager(Group worldGroup, int mapW, int mapH) {
        this.worldGroup = worldGroup;
        this.mapW = mapW;
        this.mapH = mapH;
    }

    /** 当前是否缩放稳定（允许瓦片层级切换） */
    public boolean isScaleStable() {
        return scaleStableFrames >= SCALE_STABLE_THRESHOLD;
    }

    public void onScaleChanged() {
        scaleStableFrames = 0;
    }

    public void onStableFrame() {
        scaleStableFrames++;
    }

    public int getMapWidth() {
        return mapW;
    }

    public int getMapHeight() {
        return mapH;
    }

    /** 清空瓦片缓存，重置状态 */
    public void reset() {
        activeTiles.clear();
        lastLevel = -1;
        scaleStableFrames = 0;
    }

    // ==================== 层级选择 ====================

    /**
     * 选择最接近 1:1 屏幕像素比的层级，带磁滞防止振荡。
     * @param scale 当前缩放比
     * @return 瓦片层级 (0-4)
     */
    public int selectLevel(double scale) {
        int candidate;
        if (scale >= 0.7) candidate = 0;
        else if (scale >= 0.35) candidate = 1;
        else if (scale >= 0.175) candidate = 2;
        else if (scale >= 0.0875) candidate = 3;
        else candidate = 4;

        // 磁滞防止层级振荡
        if (lastLevel >= 0 && candidate != lastLevel) {
            double[] enterThresholds = {0.75, 0.38, 0.19, 0.095};
            double[] exitThresholds = {0.65, 0.32, 0.16, 0.08};

            if (candidate < lastLevel) {
                if (scale < enterThresholds[candidate]) {
                    return lastLevel;
                }
            } else {
                if (scale > exitThresholds[lastLevel]) {
                    return lastLevel;
                }
            }
        }
        return candidate;
    }

    // ==================== 瓦片加载与回收 ====================

    /**
     * 根据当前视口更新瓦片视图，加载新瓦片并移除不可见瓦片。
     */
    public void updateTiles(double ox, double oy, double scale, int level, double vw, double vh) {
        if (vw <= 0 || vh <= 0) return;

        double worldTileSize = TILE_SIZE * (1 << level);
        double buffer = 3 * worldTileSize;
        double minWorldX = -ox / scale - buffer;
        double minWorldY = -oy / scale - buffer;
        double maxWorldX = (-ox + vw) / scale + buffer;
        double maxWorldY = (-oy + vh) / scale + buffer;

        int minCol = Math.max(0, (int) Math.floor(minWorldX / worldTileSize));
        int minRow = Math.max(0, (int) Math.floor(minWorldY / worldTileSize));
        int maxCol = Math.min(
                (int) Math.ceil((double) mapW / worldTileSize) - 1,
                (int) Math.ceil(maxWorldX / worldTileSize));
        int maxRow = Math.min(
                (int) Math.ceil((double) mapH / worldTileSize) - 1,
                (int) Math.ceil(maxWorldY / worldTileSize));

        Set<String> needed = new HashSet<>();
        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                needed.add(key(level, row, col));
            }
        }

        // 移除不可见的瓦片
        Iterator<Map.Entry<String, ImageView>> it = activeTiles.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ImageView> entry = it.next();
            if (!needed.contains(entry.getKey())) {
                ImageView iv = entry.getValue();
                iv.setImage(null); // 断开 Image 引用，帮助 GC
                worldGroup.getChildren().remove(iv);
                it.remove();
            }
        }

        // 加载新瓦片（并行 I/O — 虚拟线程读磁盘，FX 线程解码 + 场景图插入）
        int loaded = 0, missed = 0;
        List<String> missingKeys = new ArrayList<>();
        for (String k : needed) {
            if (!activeTiles.containsKey(k)) {
                missingKeys.add(k);
            }
        }

        if (!missingKeys.isEmpty()) {
            Map<String, byte[]> loadedBytes = new ConcurrentHashMap<>();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String key : missingKeys) {
                    executor.submit(() -> {
                        byte[] data = loadTileBytes(level, key);
                        if (data != null) loadedBytes.put(key, data);
                    });
                }
            }
            for (String key : missingKeys) {
                byte[] data = loadedBytes.get(key);
                if (data != null) {
                    ImageView tileView = buildImageView(level, key, data);
                    activeTiles.put(key, tileView);
                    worldGroup.getChildren().addFirst(tileView);
                    loaded++;
                } else {
                    missed++;
                }
            }
        }
        if (loaded > 0 || missed > 0) {
            log.debug("L{} tiles: loaded={} missed={} active={} needed={}",
                    level, loaded, missed, activeTiles.size(), needed.size());
        }
    }

    /** 并行 I/O：从磁盘读取瓦片 PNG 字节（在虚拟线程中执行，不接触 JavaFX 对象） */
    private byte[] loadTileBytes(int level, String key) {
        String relativePath = level + "/" + key.substring(key.indexOf('_') + 1) + ".png";
        String resourcePath = ResourceConfigContext.getTilesDir() + "/" + relativePath;
        try (InputStream in = ResourceUtils.getResourceStream(resourcePath)) {
            return in.readAllBytes();
        } catch (Exception e) {
            log.warn("瓦片缺失: {}", resourcePath);
            return null;
        }
    }

    /** FX 线程：从字节数组解码 Image 并构造 ImageView */
    private ImageView buildImageView(int level, String key, byte[] data) {
        Image tileImage = new Image(new ByteArrayInputStream(data));
        String[] parts = key.split("_", 3);
        int row = Integer.parseInt(parts[1]);
        int col = Integer.parseInt(parts[2]);

        ImageView iv = new ImageView(tileImage);
        iv.setPreserveRatio(false);
        iv.setSmooth(false);
        iv.setMouseTransparent(true);
        iv.setPickOnBounds(false);

        double worldTileSize = TILE_SIZE * (1 << level);
        iv.setLayoutX(col * worldTileSize);
        iv.setLayoutY(row * worldTileSize);
        iv.setFitWidth(worldTileSize);
        iv.setFitHeight(worldTileSize);

        return iv;
    }

    private static String key(int level, int row, int col) {
        return level + "_" + row + "_" + col;
    }
}
