package io.github.kedaya0209.roco.app.ui.service.resource;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 瓦片管理器 — 多分辨率金字塔瓦片的加载、缓存与视图管理。
 * <p>
 * 始终以单子图尺寸（8192x8192）渲染：
 * <ul>
 *   <li>普通模式：显示当前活跃子图的瓦片</li>
 *   <li>洞穴模式：大陆瓦片作为半透明底图 + 当前洞穴瓦片叠加在上层</li>
 * </ul>
 * 瓦片从子图专属目录以局部行列号加载，不感知拼接坐标。
 */
@Slf4j
@NotThreadSafe
public class TileManager {

    private static final int TILE_SIZE = RenderConfig.TILE_SIZE;
    private static final int SUB_IMAGE_SIZE = 8192;

    private final Group worldGroup;
    private final int mapW, mapH;
    // 主瓦片集（当前活跃子图）
    private final Map<String, ImageView> activeTiles = new HashMap<>();
    // 洞穴模式底图瓦片集（大陆 = 子图 0）
    private final Map<String, ImageView> bgTiles = new HashMap<>();
    private final Set<String> needed = new HashSet<>();
    private final List<String> missingKeys = new ArrayList<>();
    private final Map<String, byte[]> loadedBytes = new ConcurrentHashMap<>();
    private final ExecutorService tileExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Setter
    @Getter
    private int lastLevel = -1;

    // 缩放稳定延迟
    private int scaleStableFrames = 0;

    // 当前活跃子图
    private int activeSubIndex = 0;
    private String activeTileDir;
    private String mainlandTileDir;
    private boolean hasMultiMap = false;

    // 洞穴模式状态
    private boolean caveMode = false;
    private int activeCaveIndex = -1;
    private String caveTileDir;

    public TileManager(Group worldGroup, int mapW, int mapH) {
        this.worldGroup = worldGroup;
        this.mapW = mapW;
        this.mapH = mapH;
    }

    /**
     * 使用默认子图（大陆, 索引 0）的瓦片目录初始化。
     */
    public void initFromMetadata(CompositeMapMetadata metadata) {
        if (metadata == null || metadata.subImages().isEmpty()) return;
        this.hasMultiMap = true;
        // 大陆 = 子图 0
        var mainland = metadata.subImages().get(0);
        this.mainlandTileDir = normalizeDir(mainland.tileDir());
        this.activeTileDir = this.mainlandTileDir;
        this.activeSubIndex = 0;
    }

    /** 切换活跃子图（改变瓦片加载源） */
    public void setActiveSubImage(int index, String tileDir) {
        if (index == this.activeSubIndex && Objects.equals(tileDir, this.activeTileDir)) return;
        this.activeSubIndex = index;
        this.activeTileDir = normalizeDir(tileDir);
        // 清空当前瓦片，下次帧循环重新加载
        clearTiles(activeTiles);
    }

    private static String normalizeDir(String dir) {
        if (dir == null || dir.isEmpty()) return dir;
        return dir.endsWith("/") ? dir : dir + "/";
    }

    public void dispose() {
        tileExecutor.shutdownNow();
    }

    private static String key(int level, int row, int col) {
        return level + "_" + row + "_" + col;
    }

    public boolean isScaleStable() {
        return scaleStableFrames >= RenderConfig.SCALE_STABLE_THRESHOLD;
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

    // ==================== 层级选择 ====================

    public void reset() {
        clearTiles(activeTiles);
        clearTiles(bgTiles);
        lastLevel = -1;
        scaleStableFrames = 0;
    }

    private void clearTiles(Map<String, ImageView> tileMap) {
        for (ImageView iv : tileMap.values()) {
            iv.setImage(null);
            worldGroup.getChildren().remove(iv);
        }
        tileMap.clear();
    }

    // ==================== 洞穴叠加 ====================

    public void setCaveMode(boolean enabled, int caveIdx, String caveDir) {
        if (this.caveMode == enabled && this.activeCaveIndex == caveIdx) return;
        this.caveMode = enabled;
        this.activeCaveIndex = enabled ? caveIdx : -1;
        this.caveTileDir = enabled ? normalizeDir(caveDir) : null;

        if (!enabled) {
            // 退出洞穴模式：清空底图，恢复为主瓦片集
            clearTiles(bgTiles);
            activeTileDir = mainlandTileDir;
            activeSubIndex = 0;
        } else {
            // 进入洞穴模式：主瓦片集切到洞穴，底图用大陆
            activeTileDir = this.caveTileDir;
            activeSubIndex = caveIdx;
        }
        // 标记需要重新加载
        clearTiles(activeTiles);
        clearTiles(bgTiles);
    }

    // ==================== 瓦片加载与回收 ====================

    public int selectLevel(double scale) {
        int candidate;
        if (scale >= 0.7) candidate = 0;
        else if (scale >= 0.35) candidate = 1;
        else if (scale >= 0.175) candidate = 2;
        else if (scale >= 0.0875) candidate = 3;
        else candidate = 4;

        if (lastLevel >= 0 && candidate != lastLevel) {
            double[] enterThresholds = {0.75, 0.38, 0.19, 0.095};
            double[] exitThresholds = {0.65, 0.32, 0.16, 0.08};
            if (candidate < lastLevel) {
                if (scale < enterThresholds[candidate]) return lastLevel;
            } else {
                if (scale > exitThresholds[lastLevel]) return lastLevel;
            }
        }
        return candidate;
    }

    /**
     * 更新瓦片视图。所有坐标均为子图局部坐标（0..8192）。
     */
    public void updateTiles(double ox, double oy, double scale, int level, double vw, double vh) {
        if (vw <= 0 || vh <= 0) return;

        double worldTileSize = TILE_SIZE * (1 << level);
        double buffer = RenderConfig.TILE_BUFFER_MULTIPLIER * worldTileSize;
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

        needed.clear();
        for (int row = minRow; row <= maxRow; row++) {
            for (int col = minCol; col <= maxCol; col++) {
                needed.add(key(level, row, col));
            }
        }

        if (caveMode && hasMultiMap) {
            // 洞穴模式：两趟加载 — 大陆底图（后层）+ 洞穴前景（前层）
            updateTileLayer(needed, bgTiles, mainlandTileDir, level,
                    RenderConfig.CAVE_MAINLAND_OPACITY, false);
            updateTileLayer(needed, activeTiles, caveTileDir, level,
                    1.0, true);
        } else {
            // 普通模式：单趟加载
            updateTileLayer(needed, activeTiles, activeTileDir, level,
                    1.0, true);
        }
    }

    /**
     * 加载/回收单个瓦片层。
     *
     * @param tileMap  该层对应的瓦片映射
     * @param tileDir  瓦片目录（classpath 路径）
     * @param opacity  该层瓦片透明度
     * @param front    true = 添加到场景图前方（append），false = 后方（addFirst）
     */
    private void updateTileLayer(Set<String> needed, Map<String, ImageView> tileMap,
                                  String tileDir, int level, double opacity,
                                  boolean front) {
        if (tileDir == null || tileDir.isEmpty()) return;

        // 找出缺失的瓦片 key
        missingKeys.clear();
        for (String k : needed) {
            if (!tileMap.containsKey(k)) {
                missingKeys.add(k);
            }
        }

        // 先加载新瓦片，再加入场景图，最后才移除旧瓦片 → 避免闪白
        if (!missingKeys.isEmpty()) {
            loadedBytes.clear();
            List<Future<?>> futures = new ArrayList<>();
            for (String key : missingKeys) {
                futures.add(tileExecutor.submit(() -> {
                    byte[] data = loadTileBytes(level, key, tileDir);
                    if (data != null) loadedBytes.put(key, data);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException | ExecutionException e) {
                    log.warn("瓦片加载任务异常", e);
                }
            }

            for (String key : missingKeys) {
                byte[] data = loadedBytes.get(key);
                if (data != null) {
                    ImageView tileView = buildImageView(level, key, data);
                    tileView.setOpacity(opacity);
                    if (front) {
                        worldGroup.getChildren().add(tileView);
                    } else {
                        worldGroup.getChildren().addFirst(tileView);
                    }
                    tileMap.put(key, tileView);
                }
            }
        }

        // 新瓦片已加入场景图，现在安全移除视野外的旧瓦片
        Iterator<Map.Entry<String, ImageView>> it = tileMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ImageView> entry = it.next();
            if (!needed.contains(entry.getKey())) {
                ImageView iv = entry.getValue();
                iv.setImage(null);
                worldGroup.getChildren().remove(iv);
                it.remove();
            }
        }
    }

    /**
     * 从指定瓦片目录加载 PNG 字节，使用局部行列号。
     */
    private byte[] loadTileBytes(int level, String key, String tileDir) {
        String[] parts = key.split("_", 3);
        int row = Integer.parseInt(parts[1]);
        int col = Integer.parseInt(parts[2]);
        String path = tileDir + level + "/" + row + "_" + col + ".png";
        if (log.isTraceEnabled()) {
            log.trace("加载瓦片: {}", path);
        }
        return tryLoad(path);
    }

    private byte[] tryLoad(String resourcePath) {
        try (InputStream in = ResourceUtils.getResourceStream(resourcePath)) {
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 从字节数组解码 Image，定位在子图局部坐标（local row/col）。
     */
    private ImageView buildImageView(int level, String key, byte[] data) {
        Image tileImage = new Image(new ByteArrayInputStream(data));
        if (tileImage.isError()) {
            log.warn("Tile Image 解码失败: {} ({})", key,
                    tileImage.getException() != null ? tileImage.getException().getMessage() : "unknown");
        }
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
}
