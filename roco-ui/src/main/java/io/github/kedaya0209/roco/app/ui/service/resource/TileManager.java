package io.github.kedaya0209.roco.app.ui.service.resource;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.RenderConfig;
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
 * 大陆图始终作为底图渲染。匹配到洞穴时，洞穴瓦片叠加在大陆之上。
 * 所有子图共享同一坐标空间（8192x8192，offsetY=0）。
 */
@Slf4j
@NotThreadSafe
public class TileManager {

    private static final int TILE_SIZE = RenderConfig.TILE_SIZE;
    private static final int SUB_IMAGE_SIZE = 8192;

    private final Group worldGroup;
    private final int mapW, mapH;
    // 大陆瓦片集（始终存在）
    private final Map<String, ImageView> mainlandTiles = new HashMap<>();
    // 洞穴叠加瓦片集（进入洞穴模式时叠加）
    private final Map<String, ImageView> caveOverlayTiles = new HashMap<>();
    private final Set<String> needed = new HashSet<>();
    private final List<String> missingKeys = new ArrayList<>();
    private final Map<String, byte[]> loadedBytes = new ConcurrentHashMap<>();
    private final ExecutorService tileExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Setter
    @Getter
    private int lastLevel = -1;

    // 缩放稳定延迟
    private int scaleStableFrames = 0;

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
     * 使用元数据初始化：寻找大陆子图（isCave=false）作为底图。
     */
    public void initFromMetadata(CompositeMapMetadata metadata) {
        if (metadata == null || metadata.subImages().isEmpty()) return;
        this.hasMultiMap = true;
        // 寻找大陆子图（第一个非洞穴子图），否则用子图0
        var mainland = metadata.subImages().stream()
                .filter(s -> !s.isCave())
                .findFirst()
                .orElse(metadata.subImages().get(0));
        this.mainlandTileDir = normalizeDir(mainland.tileDir());
    }

    /**
     * 根据洞穴索引从元数据获取瓦片目录。
     */
    public void setCaveOverlay(int caveIdx, String caveDir) {
        if (caveIdx < 0 || caveDir == null || caveDir.isEmpty()) {
            // 退出洞穴叠加
            if (this.caveMode) {
                this.caveMode = false;
                this.activeCaveIndex = -1;
                this.caveTileDir = null;
                clearTiles(caveOverlayTiles);
            }
            return;
        }
        String dir = normalizeDir(caveDir);
        if (this.caveMode && this.activeCaveIndex == caveIdx && Objects.equals(this.caveTileDir, dir)) {
            return; // 无变化
        }
        this.caveMode = true;
        this.activeCaveIndex = caveIdx;
        this.caveTileDir = dir;
        clearTiles(caveOverlayTiles);
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
        clearTiles(mainlandTiles);
        clearTiles(caveOverlayTiles);
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
     * <p>
     * 始终渲染大陆瓦片作为底图。洞穴模式下额外叠加洞穴瓦片。
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

        // 第一趟：大陆底图（始终加载）
        updateTileLayer(needed, mainlandTiles, mainlandTileDir, level, 1.0, false);

        // 第二趟：洞穴叠加（仅在洞穴模式下）
        if (caveMode && caveTileDir != null) {
            updateTileLayer(needed, caveOverlayTiles, caveTileDir, level, 1.0, true);
        } else if (!caveOverlayTiles.isEmpty()) {
            // 已退出洞穴模式，清理残留叠加瓦片
            clearTiles(caveOverlayTiles);
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

        // 非 256×256 瓦片（边缘）按实际尺寸等比缩放
        double imgW = tileImage.getWidth();
        double imgH = tileImage.getHeight();
        iv.setFitWidth(worldTileSize * (imgW / TILE_SIZE));
        iv.setFitHeight(worldTileSize * (imgH / TILE_SIZE));

        return iv;
    }
}
