package com.luoke.app.ui.render;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.*;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.map.model.RoutePath;
import com.luoke.app.ui.component.StatsOverlay;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.geometry.Rectangle2D;
import javafx.scene.Group;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图渲染器 — 瓦片金字塔 + ImageView 图标层（GPU 变换）+ 路线 + 玩家。
 * 瓦片层：
 * - 多分辨率金字塔 (100%/50%/25%/12.5%/6.25%)，256×256 瓦片
 * - 所有瓦片放入 worldGroup，统一 GPU translate/scale 变换
 * 图标层：
 * - ImageView 节点直接放入 worldGroup，与瓦片共用 GPU 变换
 * - 从纹理图集取源区域（setViewport），无任何 CPU 重绘
 * - 变灰操作直接切换 ImageView 的 image 引用（colorAtlas ↔ grayAtlas）
 * 路线层：
 * - 独立 Canvas 屏幕坐标绘制，缩放时全量重绘，平移时 GPU 节点 translate
 * 玩家：
 * - ImageView 放入 playerGroup，世界坐标定位，自动跟随地图变换
 */
@Slf4j
public class MapRenderer {

    private static final double ICON_SIZE = 32;
    private static final double GRAY_RADIUS = AppConfig.GRAY_DISTANCE;

    @Getter
    private final Pane parent;
    private final Group worldGroup;
    private final Group iconGroup;       // ImageView 图标节点（worldGroup 子级，共用 GPU 变换）
    private final Canvas routeCanvas;    // 路线 Canvas（独立层，屏幕坐标）
    private final Canvas hoverCanvas;    // hover 光环层（屏幕坐标）
    private final ImageView playerView;
    private final Timeline loop;

    // worldGroup 变换：Scale 锚点 (0,0)
    private final Scale worldScale;
    private final Translate worldTranslate;
    private final Scale playerScale;
    private final Translate playerTranslate;

    private final GraphicsContext routeGc;
    private final GraphicsContext hoverGc;

    private TileManager tileManager;

    // 图标 ImageView 查找表（资源点 → ImageView，供变灰切换）
    private final Map<ResourcePoint, ImageView> iconViews = new ConcurrentHashMap<>();

    // hover 状态
    private ResourcePoint hoveredPoint;
    @Getter
    private ResourcePoint lastHoveredPoint;

    // 视口追踪
    private double lastScale;
    private boolean hoverDirty;
    private boolean firstFrame = true;

    // 跟随模式
    private boolean followWasOn;

    // 路线状态
    private RoutePath lastActiveRoute;
    private PathContext.Mode lastMode;
    private double routeDrawOx, routeDrawOy;
    private boolean routeDirty = true;

    // 跟踪玩家上一帧位置用于变灰检测（防止每帧重复检测已变灰点位）
    private double lastGrayCheckX = Double.NaN;
    private double lastGrayCheckY = Double.NaN;
    private static final double GRAY_CHECK_THRESHOLD = 10; // 世界像素，超出才重新检测

    // ==================== 构造与初始化 ====================

    public MapRenderer(Pane parent) {
        this.parent = parent;

        worldGroup = new Group();
        tileManager = new TileManager(worldGroup, 0, 0);
        worldGroup.setPickOnBounds(false);
        // 变换链： Translate(ox,oy) × Scale(scale,scale,0,0)
        worldScale = new Scale(1, 1, 0, 0);
        worldTranslate = new Translate(0, 0);
        worldGroup.getTransforms().addAll(worldTranslate, worldScale);

        // 图标组 — 放入 worldGroup，与瓦片共用 GPU 变换
        iconGroup = new Group();
        iconGroup.setPickOnBounds(false);
        iconGroup.setMouseTransparent(true);
        worldGroup.getChildren().add(iconGroup);

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

        // 路线 Canvas — 屏幕坐标，视口大小，平移用 GPU translate 补偿
        routeCanvas = new Canvas();
        routeCanvas.setMouseTransparent(true);
        routeCanvas.setPickOnBounds(false);
        routeCanvas.widthProperty().bind(parent.widthProperty());
        routeCanvas.heightProperty().bind(parent.heightProperty());
        routeGc = routeCanvas.getGraphicsContext2D();

        // hover Canvas — 屏幕坐标，视口大小
        hoverCanvas = new Canvas();
        hoverCanvas.setMouseTransparent(true);
        hoverCanvas.setPickOnBounds(false);
        hoverCanvas.widthProperty().bind(parent.widthProperty());
        hoverCanvas.heightProperty().bind(parent.heightProperty());
        hoverGc = hoverCanvas.getGraphicsContext2D();

        // 层级：瓦片 → 图标(都在worldGroup) → 路线 → hover → 玩家
        parent.getChildren().addAll(worldGroup, routeCanvas, hoverCanvas, playerGroup);

        loop = new Timeline(new KeyFrame(Duration.millis(33), e -> onFrame()));
        loop.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * 初始化地图尺寸并构建图标 ImageView 层。
     */
    public void init(int mapW, int mapH) {
        this.tileManager = new TileManager(worldGroup, mapW, mapH);
        buildIconLayer();
    }

    /** 为每个资源点创建 ImageView，放入 worldGroup 的 iconGroup */
    private void buildIconLayer() {
        IconCache cache = IconCache.getInstance();
        if (!cache.isAtlasReady()) {
            log.warn("图集未就绪，跳过图标层构建");
            return;
        }

        Image colorAtlas = cache.getColorAtlas();
        Image grayAtlas = cache.getGrayAtlas();
        int built = 0;

        for (ResourcePoint rp : ResourcePointContext.getInstance().getAllPoints()) {
            String iconFile = rp.getConfig().getIcon();
            if (iconFile == null || iconFile.isEmpty()) continue;

            String iconPath = AppConfig.ICON_DIR + iconFile;
            IconCache.AtlasSlot slot = cache.getSlot(iconPath);
            if (slot == null) continue;

            ImageView iv = new ImageView();
            iv.setImage(rp.isGrayed() ? grayAtlas : colorAtlas);
            iv.setViewport(new Rectangle2D(slot.sx, slot.sy, ICON_SIZE, ICON_SIZE));
            iv.setPreserveRatio(false);
            iv.setSmooth(false);
            iv.setMouseTransparent(true);
            iv.setPickOnBounds(false);

            Point pos = rp.getScreenPosition();
            iv.setLayoutX(pos.getX() - ICON_SIZE / 2.0);
            iv.setLayoutY(pos.getY() - ICON_SIZE / 2.0);
            iv.setFitWidth(ICON_SIZE);
            iv.setFitHeight(ICON_SIZE);

            iconViews.put(rp, iv);
            iconGroup.getChildren().add(iv);
            built++;
        }
        log.info("图标 ImageView 层已构建: {} 个点位", built);
    }

    public void setPlayerImage(Image image) {
        playerView.setImage(image);
    }

    public void start() {
        loop.play();
    }

    public void dispose() {
        loop.stop();
        tileManager.reset();
    }

    /** 通知路线/资源点变化，触发路线层重绘 */
    public void markDirty() {
        routeDirty = true;
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

        cam.updateViewport();

        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();
        double scale = mm.getScale();

        // 首帧自动适配视口
        if (firstFrame) {
            if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                firstFrame = false;
                autoFitViewport(mm);
                ox = mm.getOffsetX();
                oy = mm.getOffsetY();
                scale = mm.getScale();
                followWasOn = cam.isFollowMode();
                log.info("首帧 view={}x{} scale={} ox={} oy={}",
                        (int) parent.getWidth(), (int) parent.getHeight(),
                        String.format("%.4f", scale), (int) ox, (int) oy);
            }
        }

        boolean scaleChanged = Math.abs(scale - lastScale) > 1e-9;

        // 跟随模式切换 → 路线重绘
        if (cam.isFollowMode() != followWasOn) {
            followWasOn = cam.isFollowMode();
            routeDirty = true;
        }

        // ====== GPU 变换（每帧，纯 GPU 操作） ======

        // worldGroup 变换 → 瓦片 + 图标同时生效
        worldScale.setX(scale);
        worldScale.setY(scale);
        worldTranslate.setX(ox);
        worldTranslate.setY(oy);

        // playerGroup 变换
        playerScale.setX(scale);
        playerScale.setY(scale);
        playerTranslate.setX(ox);
        playerTranslate.setY(oy);

        // 玩家位置
        if (mm.isPlayerInitialized() && playerView.getImage() != null) {
            playerView.setVisible(true);
            double half = playerView.getFitWidth() / 2.0;
            playerView.setLayoutX(mm.getPlayerX() - half);
            playerView.setLayoutY(mm.getPlayerY() - half);
            playerView.setRotate(mm.getPlayerAngle());
        } else {
            playerView.setVisible(false);
        }

        // ====== 路线层（屏幕坐标 Canvas） ======

        // 路线状态变化检测
        PathContext pc = PathContext.getInstance();
        RoutePath activeRoute = pc.getActiveRoute();
        PathContext.Mode mode = pc.getCurrentMode();
        if (activeRoute != lastActiveRoute || mode != lastMode) {
            lastActiveRoute = activeRoute;
            lastMode = mode;
            routeDirty = true;
        }

        // 缩放 → 路线全量重绘；平移 → GPU translate 补偿
        if (scaleChanged) {
            routeCanvas.setTranslateX(0);
            routeCanvas.setTranslateY(0);
            routeDirty = true;
        } else {
            routeCanvas.setTranslateX(ox - routeDrawOx);
            routeCanvas.setTranslateY(oy - routeDrawOy);
        }

        if (routeDirty) {
            redrawRoutes(ox, oy, scale);
            routeDrawOx = ox;
            routeDrawOy = oy;
            routeCanvas.setTranslateX(0);
            routeCanvas.setTranslateY(0);
            routeDirty = false;
        }

        // ====== 瓦片加载（缩放稳定后执行） ======

        if (scaleChanged) {
            tileManager.onScaleChanged();
        } else {
            tileManager.onStableFrame();
        }
        boolean scaleStable = tileManager.isScaleStable() || firstFrame;

        int level = tileManager.selectLevel(scale);
        if (level != tileManager.getLastLevel() && scaleStable) {
            tileManager.setLastLevel(level);
        }
        if (scaleStable) {
            tileManager.updateTiles(ox, oy, scale, level, parent.getWidth(), parent.getHeight());
        }

        // ====== 变灰检测（ImageView 图集引用切换，零重绘开销） ======

        if (mm.isPlayerInitialized()) {
            double px = mm.getPlayerX();
            double py = mm.getPlayerY();
            // 减少检测频率：玩家移动超过阈值才检测
            if (distanceSq(px, py, lastGrayCheckX, lastGrayCheckY) > GRAY_CHECK_THRESHOLD * GRAY_CHECK_THRESHOLD) {
                updateGrayStates(px, py);
                lastGrayCheckX = px;
                lastGrayCheckY = py;
            }
        }

        // ====== hover 重绘 ======

        if (hoverDirty) {
            redrawHover(ox, oy, scale);
            hoverDirty = false;
        }

        lastScale = scale;
    }

    private static double distanceSq(double x1, double y1, double x2, double y2) {
        if (Double.isNaN(x2) || Double.isNaN(y2)) return Double.MAX_VALUE;
        double dx = x1 - x2;
        double dy = y1 - y2;
        return dx * dx + dy * dy;
    }

    // ==================== 视口适配 ====================

    private void autoFitViewport(MapContext mm) {
        double vw = parent.getWidth();
        double vh = parent.getHeight();
        if (vw <= 0 || vh <= 0) return;

        double mw = tileManager.getMapWidth(), mh = tileManager.getMapHeight();
        if (mw <= 0 || mh <= 0) return;
        double s = Math.max(vw / mw, vh / mh);
        double tx = (vw - mw * s) / 2;
        double ty = (vh - mh * s) / 2;

        mm.setScale(s);
        mm.setOffsetX(tx);
        mm.setOffsetY(ty);
    }

    // ==================== 路线渲染（屏幕坐标 Canvas） ====================

    /** 全量重绘路线层 — 世界坐标转屏幕坐标直接绘制 */
    private void redrawRoutes(double ox, double oy, double scale) {
        double w = routeCanvas.getWidth();
        double h = routeCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        routeGc.clearRect(0, 0, w, h);

        PathContext pc = PathContext.getInstance();
        RoutePath active = pc.getActiveRoute();
        if (active == null) return;

        // 1. 背景路线（置灰/半透明）
        routeGc.setLineWidth(2);
        routeGc.setStroke(Color.web("#888888", 0.6));
        for (RoutePath path : pc.getSavedRoutes()) {
            if (path == pc.getActiveRoute()) continue;
            renderPathScreen(routeGc, path.getNodes(), ox, oy, scale);
        }

        // 2. 活跃路线（绿色）
        routeGc.setStroke(Color.CHARTREUSE);
        routeGc.setLineWidth(3);
        renderPathScreen(routeGc, active.getNodes(), ox, oy, scale);

        // 3. UI 叠加（绘图/编辑模式）
        if (pc.getCurrentMode() != PathContext.Mode.VIEW) {
            // 预览虚线（橡皮筋）
            if (pc.getCurrentMode() == PathContext.Mode.DRAWING && !active.getNodes().isEmpty()) {
                Point lastNode = active.getNodes().get(active.getNodes().size() - 1);
                double x1 = lastNode.getX() * scale + ox;
                double y1 = lastNode.getY() * scale + oy;
                double x2 = pc.getMouseLogicX() * scale + ox;
                double y2 = pc.getMouseLogicY() * scale + oy;
                routeGc.setStroke(Color.web("#FFFFFF", 0.7));
                routeGc.setLineDashes(5);
                routeGc.strokeLine(x1, y1, x2, y2);
                routeGc.setLineDashes(null);
            }

            // 节点锚点圆
            routeGc.setFill(Color.WHITE);
            routeGc.setStroke(Color.BLUE);
            double r = 4.5;
            for (Point node : active.getNodes()) {
                double nx = node.getX() * scale + ox;
                double ny = node.getY() * scale + oy;
                routeGc.fillOval(nx - r, ny - r, r * 2, r * 2);
                routeGc.strokeOval(nx - r, ny - r, r * 2, r * 2);
            }
        }
    }

    /** 以屏幕坐标绘制单条路径 */
    private static void renderPathScreen(GraphicsContext gc, List<Point> nodes, double ox, double oy, double scale) {
        if (nodes.size() < 2) return;
        gc.beginPath();
        gc.moveTo(nodes.get(0).getX() * scale + ox, nodes.get(0).getY() * scale + oy);
        for (int i = 1; i < nodes.size(); i++) {
            gc.lineTo(nodes.get(i).getX() * scale + ox, nodes.get(i).getY() * scale + oy);
        }
        gc.stroke();
    }

    // ==================== 变灰检测（ImageView 直接切换） ====================

    /**
     * 检测玩家附近新变灰的资源点，直接切换 ImageView 的图集引用。
     * 无需任何 Canvas 重绘，零 CPU 开销。
     */
    private void updateGrayStates(double playerX, double playerY) {
        double r2 = GRAY_RADIUS * GRAY_RADIUS;
        IconCache cache = IconCache.getInstance();
        Image grayAtlas = cache.getGrayAtlas();
        if (grayAtlas == null) return;

        for (ResourcePoint rp : ResourcePointContext.getInstance().getNearbyResources(playerX, playerY)) {
            if (rp.isGrayed()) continue;
            Point pos = rp.getScreenPosition();
            double dx = pos.getX() - playerX;
            double dy = pos.getY() - playerY;
            String name = rp.getConfig().getMarkTypeName();
            if (dx * dx + dy * dy < r2 && ResourcePointContext.getInstance().isCollect(name)) {
                rp.setGrayed(true);
                // 直接切换 ImageView 图集引用，零重绘
                ImageView iv = iconViews.get(rp);
                if (iv != null) {
                    iv.setImage(grayAtlas);
                }
            }
        }
    }

    // ==================== hover 高亮（屏幕坐标 Canvas） ====================

    /** hover 光环绘制 — 世界坐标转屏幕坐标 */
    private void redrawHover(double ox, double oy, double scale) {
        double w = hoverCanvas.getWidth();
        double h = hoverCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        hoverGc.clearRect(0, 0, w, h);
        if (hoveredPoint == null) return;

        Point pos = hoveredPoint.getScreenPosition();
        double sx = pos.getX() * scale + ox;
        double sy = pos.getY() * scale + oy;

        // 光环
        double hoverSize = 38;
        hoverGc.setFill(Color.web("#00BFFF", 0.2));
        hoverGc.fillOval(sx - hoverSize / 2 - 4, sy - hoverSize / 2 - 4, hoverSize + 8, hoverSize + 8);
        hoverGc.setStroke(Color.web("#00BFFF", 0.8));
        hoverGc.setLineWidth(2);
        hoverGc.strokeOval(sx - hoverSize / 2 - 2, sy - hoverSize / 2 - 2, hoverSize + 4, hoverSize + 4);

        // 图标
        String iconFile = hoveredPoint.getConfig().getIcon();
        if (iconFile == null || iconFile.isEmpty()) return;
        IconCache cache = IconCache.getInstance();
        String iconPath = AppConfig.ICON_DIR + iconFile;
        if (cache.isAtlasReady()) {
            IconCache.AtlasSlot slot = cache.getSlot(iconPath);
            if (slot != null) {
                hoverGc.drawImage(cache.getColorAtlas(), slot.sx, slot.sy, ICON_SIZE, ICON_SIZE,
                        sx - hoverSize / 2, sy - hoverSize / 2, hoverSize, hoverSize);
                return;
            }
        }
        Image icon = cache.getIcon(iconPath);
        if (icon != null) {
            hoverGc.drawImage(icon, sx - hoverSize / 2, sy - hoverSize / 2, hoverSize, hoverSize);
        }
    }

    // ==================== 公开 API ====================

    public void setHoveredPoint(ResourcePoint p) {
        if (this.hoveredPoint == p) return;
        this.lastHoveredPoint = this.hoveredPoint;
        this.hoveredPoint = p;
        hoverDirty = true;
    }

    /** 重置视角到首帧适配状态 */
    public void resetViewport() {
        firstFrame = true;
        routeDirty = true;
    }
}
