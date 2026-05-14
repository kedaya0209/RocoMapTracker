package com.luoke.app.ui.render;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.*;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.map.model.RoutePath;
import com.luoke.app.ui.component.StatsOverlay;
import com.luoke.app.utils.ResourceUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.*;

/**
 * 地图渲染器 — 瓦片金字塔 + Canvas 图标层 + 玩家。
 * 瓦片层：
 * - 多分辨率金字塔 (100%/50%/25%/12.5%/6.25%)，256×256 瓦片
 * - 所有瓦片放入 worldGroup，统一 GPU translate/scale 变换
 * - 瓦片由 Python 预生成，运行时从磁盘按需加载
 * 图标层：
 * - displayCanvas 直接绘制图标，CLEAR 混合模式保持透明让瓦片层可见
 * - 视口变化时全量重绘，玩家移动时局部更新变灰图标
 * 玩家：
 * - ImageView 放入 worldGroup，世界坐标定位，自动跟随地图变换
 */
@Slf4j
public class MapRenderer {

    private static final int TILE_SIZE = 256;
    private static final double ICON_SIZE = 32;
    private static final double GRAY_RADIUS = AppConfig.GRAY_DISTANCE; // 世界像素

    @Getter
    private final Pane parent;
    private final Group worldGroup;
    private final Canvas routeCanvas;
    private final Canvas displayCanvas;
    private final ImageView playerView;
    private final Timeline loop;

    // worldGroup 变换：Scale 锚点 (0,0)，确保与 Canvas 图标公式一致
    // screenX = offsetX + worldX * scale
    private final Scale worldScale;
    private final Translate worldTranslate;
    private final Scale playerScale;
    private final Translate playerTranslate;

    private final GraphicsContext routeGc;
    private final GraphicsContext displayGc;

    // 瓦片缓存
    private final Map<String, ImageView> activeTiles = new HashMap<>();
    private int mapW, mapH;

    // 图标状态
    private final Set<ResourcePoint> grayedSet = new HashSet<>();

    // hover 状态
    private ResourcePoint hoveredPoint;
    @Getter
    private ResourcePoint lastHoveredPoint;

    // 视口追踪
    private double lastOx, lastOy, lastScale;
    private int lastLevel = -1;
    private boolean viewportDirty = true;
    private boolean firstFrame = true;

    // 缩放稳定延迟：缩放期间保持当前层级瓦片（GPU 缩放），稳定后再切换精确层级
    private int scaleStableFrames = 0;
    private static final int SCALE_STABLE_THRESHOLD = 5; // ~165ms

    // 跟随模式首次关闭标记
    private boolean followWasOn;

    // 路线状态追踪：检测 activeRoute/mode 变化以触发重绘
    private RoutePath lastActiveRoute;
    private PathContext.Mode lastMode;

    public MapRenderer(Pane parent) {
        this.parent = parent;

        worldGroup = new Group();
        worldGroup.setPickOnBounds(false);
        // 变换链： Translate(ox,oy) × Scale(scale,scale,0,0)
        // 必须先 Translate 后 Scale，确保 offset 不被缩放
        worldScale = new Scale(1, 1, 0, 0);
        worldTranslate = new Translate(0, 0);
        worldGroup.getTransforms().addAll(worldTranslate, worldScale);

        playerView = new ImageView();
        playerView.setFitWidth(72);
        playerView.setFitHeight(72);
        playerView.setMouseTransparent(true);
        playerView.setVisible(false);

        // 玩家单独一层，应用相同的世界变换，置于 Canvas 之上确保不被图标遮挡
        Group playerGroup = new Group();
        playerGroup.setPickOnBounds(false);
        playerGroup.setMouseTransparent(true);
        playerScale = new Scale(1, 1, 0, 0);
        playerTranslate = new Translate(0, 0);
        playerGroup.getTransforms().addAll(playerTranslate, playerScale);
        playerGroup.getChildren().add(playerView);

        // 路线层 — Canvas，与 displayCanvas 相同坐标变换模式（gc.translate + gc.scale）
        routeCanvas = new Canvas();
        routeCanvas.setMouseTransparent(true);
        routeCanvas.setPickOnBounds(false);
        routeCanvas.setStyle("-fx-background-color: transparent;");
        routeCanvas.setOpacity(1.0);

        displayCanvas = new Canvas();
        displayCanvas.setMouseTransparent(true);
        displayCanvas.setPickOnBounds(false);
        displayCanvas.setStyle("-fx-background-color: transparent;");
        displayCanvas.setOpacity(1.0);

        // 层级：瓦片 → 路线 → 图标 → 玩家
        parent.getChildren().addAll(worldGroup, routeCanvas, displayCanvas, playerGroup);

        routeCanvas.widthProperty().bind(parent.widthProperty());
        routeCanvas.heightProperty().bind(parent.heightProperty());
        routeGc = routeCanvas.getGraphicsContext2D();

        displayCanvas.widthProperty().bind(parent.widthProperty());
        displayCanvas.heightProperty().bind(parent.heightProperty());
        displayGc = displayCanvas.getGraphicsContext2D();

        loop = new Timeline(new KeyFrame(Duration.millis(33), e -> onFrame()));
        loop.setCycleCount(Timeline.INDEFINITE);
    }

    // ==================== 初始化 ====================

    /**
     * 设置瓦片目录和地图尺寸。瓦片验证/生成已在 ModernCanvasApp 初始化阶段完成。
     */
    public void init(int mapW, int mapH) {
        this.mapW = mapW;
        this.mapH = mapH;
    }

    public void setPlayerImage(Image image) {
        playerView.setImage(image);
    }

    public void start() {
        loop.play();
    }

    public void dispose() {
        loop.stop();
        activeTiles.clear();
    }

    /** 外部触发全量重绘（窗口大小变化等） */
    public void markDirty() {
        viewportDirty = true;
    }

    // ==================== 帧循环 ====================

    private void onFrame() {
        try {
            onFrameInternal();
        } catch (Exception e) {
            log.error("onFrame 异常", e);
        }
    }

    private void onFrameInternal() {
        StatsOverlay.getInstance().update();

        MapContext mm = MapContext.getInstance();
        CameraContext cam = CameraContext.getInstance();

        // 跟随模式：根据玩家坐标自动调整视口
        cam.updateViewport();

        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();
        double scale = mm.getScale();

        // 首次：自动适配地图到视口（displayCanvas 未布局时推迟）
        if (firstFrame) {
            if (displayCanvas.getWidth() > 0 && displayCanvas.getHeight() > 0) {
                firstFrame = false;
                autoFitViewport(mm);
                ox = mm.getOffsetX();
                oy = mm.getOffsetY();
                scale = mm.getScale();
                followWasOn = cam.isFollowMode();
                log.info("首帧 view={}x{} scale={} ox={} oy={}",
                        (int) displayCanvas.getWidth(), (int) displayCanvas.getHeight(),
                        String.format("%.4f", scale), (int) ox, (int) oy);
            }
        }

        // 检测视口变化
        boolean offsetChanged = Math.abs(ox - lastOx) > 0.01 || Math.abs(oy - lastOy) > 0.01;
        boolean scaleChanged = Math.abs(scale - lastScale) > 1e-9;
        if (offsetChanged || scaleChanged) {
            viewportDirty = true;
        }

        // 检测跟随模式切换
        boolean followNow = cam.isFollowMode();
        if (followNow != followWasOn) {
            followWasOn = followNow;
            viewportDirty = true;
        }

        // 更新 worldGroup 变换（每帧，纯 GPU 操作）
        worldScale.setX(scale);
        worldScale.setY(scale);
        worldTranslate.setX(ox);
        worldTranslate.setY(oy);

        // 同步 playerGroup 变换（与 worldGroup 一致，置于图标层之上）
        playerScale.setX(scale);
        playerScale.setY(scale);
        playerTranslate.setX(ox);
        playerTranslate.setY(oy);

        // 更新玩家位置（playerGroup 已应用 Scale，half 和 playerX 在同一坐标系中，无需除以 scale）
        if (mm.isPlayerInitialized() && playerView.getImage() != null) {
            playerView.setVisible(true);
            double half = playerView.getFitWidth() / 2.0;
            playerView.setLayoutX(mm.getPlayerX() - half);
            playerView.setLayoutY(mm.getPlayerY() - half);
            playerView.setRotate(mm.getPlayerAngle());
        } else {
            playerView.setVisible(false);
        }

        // 缩放稳定检测：缩放中保持当前层级（GPU 缩放），稳定后再切换精确层级
        if (scaleChanged) {
            scaleStableFrames = 0;
        } else {
            scaleStableFrames++;
        }
        boolean scaleStable = scaleStableFrames >= SCALE_STABLE_THRESHOLD || firstFrame;

        // 选择瓦片层级（缩放稳定后才允许切换）
        int level = selectLevel(scale);
        if (level != lastLevel && scaleStable) {
            viewportDirty = true;
            lastLevel = level;
        }

        // 检测路线状态变化（窗口关闭/选中/绘制时会变化，需要触发重绘）
        PathContext pc = PathContext.getInstance();
        RoutePath activeRoute = pc.getActiveRoute();
        PathContext.Mode mode = pc.getCurrentMode();
        if (activeRoute != lastActiveRoute || mode != lastMode) {
            viewportDirty = true;
            lastActiveRoute = activeRoute;
            lastMode = mode;
        }

        // 路线 + 图标重绘（视口变化时执行，与资源层行为一致：钉在地图上）
        if (viewportDirty) {
            drawRoutes(ox, oy, scale);
            fullIconRedraw(ox, oy, scale);
        }

        // 瓦片加载（缩放稳定后才执行，重量级操作：磁盘 IO + PNG 解码）
        if (viewportDirty && scaleStable) {
            updateTiles(ox, oy, scale, level);
            viewportDirty = false;
        }

        // 检测新变灰的图标 → 局部更新
        if (mm.isPlayerInitialized()) {
            List<ResourcePoint> newlyGrayed = checkNewlyGrayed(mm.getPlayerX(), mm.getPlayerY());
            if (!newlyGrayed.isEmpty()) {
                partialIconUpdate(ox, oy, scale, newlyGrayed);
            }
        }

        lastOx = ox;
        lastOy = oy;
        lastScale = scale;
    }

    // ==================== 视口适配 ====================

    private void autoFitViewport(MapContext mm) {
        double vw = displayCanvas.getWidth();
        double vh = displayCanvas.getHeight();
        if (vw <= 0 || vh <= 0 || mapW <= 0 || mapH <= 0) return;

        double s = Math.max(vw / mapW, vh / mapH);
        double tx = (vw - mapW * s) / 2;
        double ty = (vh - mapH * s) / 2;

        mm.setScale(s);
        mm.setOffsetX(tx);
        mm.setOffsetY(ty);
    }

    // ==================== 瓦片管理 ====================

    private int selectLevel(double scale) {
        // 选择最接近 1:1 屏幕像素比的层级
        // 2^L * scale ≈ 1 → L ≈ -log2(scale)
        int candidate;
        if (scale >= 0.7) candidate = 0;
        else if (scale >= 0.35) candidate = 1;
        else if (scale >= 0.175) candidate = 2;
        else if (scale >= 0.0875) candidate = 3;
        else candidate = 4;

        // 磁滞防止层级振荡
        if (lastLevel >= 0 && candidate != lastLevel) {
            // 进入某层级的阈值比退出略高，防止在边界反复切换
            double[] enterThresholds = {0.75, 0.38, 0.19, 0.095}; // 进入该层需要大于此值
            double[] exitThresholds  = {0.65, 0.32, 0.16, 0.08};   // 退出该层需要小于此值

            if (candidate < lastLevel) {
                // 放大（层级号变小）：scale 需高于进入阈值
                if (scale < enterThresholds[candidate]) {
                    return lastLevel;
                }
            } else {
                // 缩小（层级号变大）：scale 需低于退出阈值
                if (scale > exitThresholds[lastLevel]) {
                    return lastLevel;
                }
            }
        }
        return candidate;
    }

    private void updateTiles(double ox, double oy, double scale, int level) {
        double vw = displayCanvas.getWidth();
        double vh = displayCanvas.getHeight();
        if (vw <= 0 || vh <= 0) return;

        // 可见世界范围（加 2 个瓦片的缓冲，防止瓦片提前移除导致图标"浮空"）
        double worldTileSize = TILE_SIZE * (1 << level);
        double buffer = 2 * worldTileSize;
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

        // 计算需要的瓦片集合
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
                worldGroup.getChildren().remove(entry.getValue());
                it.remove();
            }
        }

        // 加载新瓦片
        int loaded = 0, missed = 0;
        for (String k : needed) {
            if (!activeTiles.containsKey(k)) {
                ImageView tileView = loadTileView(level, k);
                if (tileView != null) {
                    activeTiles.put(k, tileView);
                    // 插入到最底层（playerView 在上层）
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

    private ImageView loadTileView(int level, String key) {
        Image tileImage = loadTile(level, key);
        if (tileImage == null) return null;

        // 从 key 解析 row, col
        String[] parts = key.split("_", 3); // "level_row_col"
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
        // 关键：ImageView 在世界空间中的尺寸 = 瓦片覆盖的世界范围
        iv.setFitWidth(worldTileSize);
        iv.setFitHeight(worldTileSize);

        return iv;
    }

    private Image loadTile(int level, String key) {
        String relativePath = level + "/" + key.substring(key.indexOf('_') + 1) + ".png";
        String resourcePath = ResourceConfigContext.getTilesDir() + "/" + relativePath;
        try (InputStream in = ResourceUtils.getResourceStream(resourcePath)) {
            return new Image(in);
        } catch (Exception e) {
            log.warn("瓦片缺失: {}", resourcePath);
            return null;
        }
    }

    private static String key(int level, int row, int col) {
        return level + "_" + row + "_" + col;
    }

    // ==================== 路线渲染 ====================

    private void drawRoutes(double ox, double oy, double scale) {
        double w = routeCanvas.getWidth();
        double h = routeCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        routeGc.clearRect(0, 0, w, h);
        routeGc.save();
        routeGc.translate(ox, oy);
        routeGc.scale(scale, scale);
        PathRenderer.draw(routeGc);
        routeGc.restore();
    }

    // ==================== 图标渲染（Canvas translate/scale，与 worldGroup 同一套变换逻辑） ====================

    /**
     * 全量重绘：清空 displayCanvas → 用 Canvas transform 与世界坐标对齐 → 绘所有图标。
     * Canvas: translate(ox, oy) × scale(scale, scale) — 与 worldGroup 完全一致
     */
    private void fullIconRedraw(double ox, double oy, double scale) {
        double w = displayCanvas.getWidth();
        double h = displayCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        displayGc.clearRect(0, 0, w, h);
        displayGc.save();
        displayGc.translate(ox, oy);
        displayGc.scale(scale, scale);

        List<ResourcePoint> allPoints = ResourcePointContext.getInstance().getAllPoints();
        for (ResourcePoint rp : allPoints) {
            if (!grayedSet.contains(rp)) {
                drawIconAtWorld(displayGc, rp, false);
            }
        }
        for (ResourcePoint rp : grayedSet) {
            drawIconAtWorld(displayGc, rp, true);
        }

        // hover 光环（紧跟图标重绘，只画一次，不累积）
        if (hoveredPoint != null) {
            drawHoverOverlay(displayGc, hoveredPoint, scale);
        }

        displayGc.restore();
    }

    /** 局部更新：在 displayCanvas 上绘制新变灰的图标（灰度版覆盖彩色版） */
    private void partialIconUpdate(double ox, double oy, double scale, List<ResourcePoint> newlyGrayed) {
        displayGc.save();
        displayGc.translate(ox, oy);
        displayGc.scale(scale, scale);
        for (ResourcePoint rp : newlyGrayed) {
            drawIconAtWorld(displayGc, rp, true);
        }
        displayGc.restore();
    }

    /** 在世界坐标绘制图标（Canvas 已应用 translate+scale，直接传世界坐标） */
    private void drawIconAtWorld(GraphicsContext gc, ResourcePoint rp, boolean gray) {
        String iconFile = rp.getConfig().getIcon();
        if (iconFile == null || iconFile.isEmpty()) return;

        String iconPath = AppConfig.ICON_DIR + iconFile;
        Image icon = gray ? IconCache.getInstance().getGrayIcon(iconPath)
                          : IconCache.getInstance().getIcon(iconPath);
        if (icon == null) return;

        Point pos = rp.getScreenPosition();
        gc.drawImage(icon, pos.getX() - ICON_SIZE / 2.0, pos.getY() - ICON_SIZE / 2.0, ICON_SIZE, ICON_SIZE);
    }

    // ==================== 变灰检测 ====================

    /**
     * 检测玩家周围新增的变灰图标。
     * 仅检查尚未变灰的图标，标记后移出未采集集合。
     */
    private List<ResourcePoint> checkNewlyGrayed(double playerX, double playerY) {
        List<ResourcePoint> result = new java.util.ArrayList<>();
        double r2 = GRAY_RADIUS * GRAY_RADIUS;

        // 通过空间网格索引只查玩家附近点位，不遍历全量
        for (ResourcePoint rp : ResourcePointContext.getInstance().getNearbyResources(playerX, playerY)) {
            if (grayedSet.contains(rp)) continue;
            Point pos = rp.getScreenPosition();
            double dx = pos.getX() - playerX;
            double dy = pos.getY() - playerY;
            String name = rp.getConfig().getMarkTypeName();
            //在半径，且能被收集的误判才可以置灰
            if (dx * dx + dy * dy < r2 && ResourcePointContext.getInstance().isCollect(name)) {
                rp.setGrayed(true);
                grayedSet.add(rp);
                result.add(rp);
            }
        }
        return result;
    }

    // ==================== hover 高亮 ====================

    /** 调用前 GC 已设置 translate+scale，直接在世界坐标绘制 */
    private void drawHoverOverlay(GraphicsContext gc, ResourcePoint rp, double scale) {
        String iconFile = rp.getConfig().getIcon();
        if (iconFile == null || iconFile.isEmpty()) return;

        Image icon = IconCache.getInstance().getIcon(AppConfig.ICON_DIR + iconFile);
        if (icon == null) return;

        Point pos = rp.getScreenPosition();

        double hoverSize = 38;

        gc.setFill(javafx.scene.paint.Color.web("#00BFFF", 0.2));
        gc.fillOval(pos.getX() - hoverSize / 2 - 4 / scale, pos.getY() - hoverSize / 2 - 4 / scale,
                     hoverSize + 8 / scale, hoverSize + 8 / scale);

        gc.setStroke(javafx.scene.paint.Color.web("#00BFFF", 0.8));
        gc.setLineWidth(2.0 / scale);
        gc.strokeOval(pos.getX() - hoverSize / 2 - 2 / scale, pos.getY() - hoverSize / 2 - 2 / scale,
                      hoverSize + 4 / scale, hoverSize + 4 / scale);

        gc.drawImage(icon, pos.getX() - hoverSize / 2, pos.getY() - hoverSize / 2, hoverSize, hoverSize);
    }

    // ==================== 公开 API ====================

    public void setHoveredPoint(ResourcePoint p) {
        if (this.hoveredPoint == p) return; // 没变化
        this.lastHoveredPoint = this.hoveredPoint;
        this.hoveredPoint = p;
        // 鼠标移入或移出：触发一次全量重绘，清除上一次的光环
        markDirty();
    }

    /** 触发重新自动适配视口（重置视角按钮回调） */
    public void resetViewport() {
        firstFrame = true;
        viewportDirty = true;
    }

}
