package io.github.kedaya0209.roco.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.service.NavigationController;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.context.CameraContext;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.ui.component.overlay.StatsOverlay;
import io.github.kedaya0209.roco.app.ui.service.resource.TileManager;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Group;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.util.Duration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 地图渲染器编排器。
 * <p>
 * 渲染在子图局部坐标空间（8192x8192）中工作。从 MapContext 读取拼接坐标
 * 后转换为局部坐标再用于 worldGroup 变换和 TileManager。
 */
@NotThreadSafe
@Slf4j
public class MapRenderer {

    /** 默认地图尺寸（init 中会被实际尺寸替换） */
    private static final int DEFAULT_MAP_SIZE = 8192;

    @Getter
    private final Pane parent;
    private final Group worldGroup;
    // worldGroup 变换：Rotate × Translate(localOx, localOy) × Scale
    private final Scale worldScale;
    private final Translate worldTranslate;
    private final Rotate worldRotate;
    private final Timeline loop;

    private final NavigationController navigationController;

    private final IconLayerManager iconLayerManager;
    private final PlayerRenderer playerRenderer;
    private final HoverRenderer hoverRenderer;
    private final List<RenderLayer> renderLayers;

    private TileManager tileManager;
    // 视口追踪
    private double lastScale;
    private double lastOx = Double.NaN;
    private double lastOy = Double.NaN;
    private boolean firstFrame = true;
    // 视口 offset 插值，消除跟随模式下匹配间隔导致的视口离散跳跃
    private double lerpOx = Double.NaN, lerpOy = Double.NaN;
    private static final double VIEWPORT_LERP = 0.5;

    public MapRenderer(Pane parent) {
        this.parent = parent;

        worldGroup = new Group();
        tileManager = new TileManager(worldGroup, DEFAULT_MAP_SIZE, DEFAULT_MAP_SIZE);
        worldGroup.setPickOnBounds(false);
        // 变换链：Rotate × Translate × Scale (从右到左作用)
        worldScale = new Scale(1, 1, 0, 0);
        worldTranslate = new Translate(0, 0);
        worldRotate = new Rotate(0, 0, 0);
        worldGroup.getTransforms().addAll(worldRotate, worldTranslate, worldScale);

        navigationController = new NavigationController();

        iconLayerManager = new IconLayerManager(worldGroup);
        playerRenderer = new PlayerRenderer();
        RouteRenderer routeRenderer = new RouteRenderer(parent);
        hoverRenderer = new HoverRenderer(parent);
        renderLayers = List.of(playerRenderer, routeRenderer, iconLayerManager, hoverRenderer);

        // 层级：worldGroup(瓦片+图标) → 路线 → hover → 玩家
        parent.getChildren().addAll(worldGroup,
                routeRenderer.getNode(),
                hoverRenderer.getNode(),
                playerRenderer.getNode());

        loop = new Timeline(new KeyFrame(Duration.millis(RenderConfig.RENDER_FRAME_INTERVAL_MS), _ -> onFrame()));
        loop.setCycleCount(Timeline.INDEFINITE);
    }

    /**
     * 初始化地图尺寸并构建图标。
     */
    public void init(int mapW, int mapH, CompositeMapMetadata metadata) {
        this.tileManager = new TileManager(worldGroup, mapW, mapH);
        if (metadata != null) {
            tileManager.initFromMetadata(metadata);
        }
        iconLayerManager.buildIconLayer();
    }

    public void setPlayerImage(Image image) {
        playerRenderer.setPlayerImage(image);
    }

    public void start() {
        loop.play();
    }

    public void dispose() {
        loop.stop();
        tileManager.reset();
    }

    private void onFrame() {
        try {
            onFrameInternal();
        } catch (Exception e) {
            log.error("onFrame 异常", e);
        }
    }

    // ==================== 帧循环 ====================

    private void onFrameInternal() {
        StatsOverlay.getInstance().update();

        CameraContext cam = CameraContext.getInstance();
        cam.updateViewport();

        MapContext mm = MapContext.getInstance();
        ViewportState vp = ViewportState.getInstance();

        clampToLocalBounds(mm);
        updateNavMode(vp, cam);

        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();
        double scale = mm.getScale();

        double[] interp = interpolateFollowOffset(ox, oy, cam.isFollowMode());
        ox = interp[0];
        oy = interp[1];

        // 首帧适配
        if (firstFrame && tryAutoFit(mm)) {
            firstFrame = false;
            ox = mm.getOffsetX();
            oy = mm.getOffsetY();
            scale = mm.getScale();
            vp.syncFromMapContext();
            log.info("首帧 view={}x{} scale={} ox={} oy={}",
                    (int) parent.getWidth(), (int) parent.getHeight(),
                    String.format("%.4f", scale), ox, oy);
        }

        boolean scaleChanged = Math.abs(scale - lastScale) > 1e-9;
        boolean viewportMoved = firstFrame
                || Math.abs(ox - lastOx) > 1e-9
                || Math.abs(oy - lastOy) > 1e-9;

        syncCaveOverlay(mm);
        applyTransforms(scale, ox, oy, scaleChanged, viewportMoved, vp);
        syncViewportState(vp, scaleChanged, viewportMoved, scale, ox, oy);
        updateRenderers(vp, scale, ox, oy);
        updateTiles(ox, oy, scale, scaleChanged);

        iconLayerManager.getNode().toFront();

        lastScale = scale;
        lastOx = ox;
        lastOy = oy;
    }

    // ==================== 导航更新 ====================

    private void updateNavMode(ViewportState vp, CameraContext cam) {
        if (vp.isPlayerInitialized()) {
            navigationController.update(vp.getPlayerAngle(), vp.isNavMode());
        }
        vp.setNavAngle(cam.getNavAngle());
    }

    // ==================== 跟随插值 ====================

    /**
     * 跟随模式下对视口 offset 插值，消除匹配间隔导致的视口离散跳跃。
     * 返回 [ox, oy]。
     */
    private double[] interpolateFollowOffset(double ox, double oy, boolean followMode) {
        if (!followMode) {
            lerpOx = Double.NaN;
            return new double[]{ox, oy};
        }
        double[] result = lerpPoint(lerpOx, lerpOy, ox, oy, VIEWPORT_LERP, 2500);
        lerpOx = result[0];
        lerpOy = result[1];
        return result;
    }

    // ==================== 工具方法 ====================

    /**
     * 二维指数平滑。current 为 NaN 或距离超过 thresholdSq 时直接吸附到 target。
     */
    static double[] lerpPoint(double currentX, double currentY,
                               double targetX, double targetY,
                               double factor, double thresholdSq) {
        if (Double.isNaN(currentX)) {
            return new double[]{targetX, targetY};
        }
        double dx = targetX - currentX;
        double dy = targetY - currentY;
        if (dx * dx + dy * dy > thresholdSq) {
            return new double[]{targetX, targetY};
        }
        return new double[]{currentX + dx * factor, currentY + dy * factor};
    }

    // ==================== 首帧适配 ====================

    private boolean tryAutoFit(MapContext mm) {
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            autoFitViewport(mm);
            return true;
        }
        return false;
    }

    // ==================== 洞穴叠加 ====================

    private void syncCaveOverlay(MapContext mm) {
        int activeLayer = mm.getActiveLayer();
        if (activeLayer >= 0) {
            java.util.List<String> caveDirs = mm.getCaveDirsToRender();
            tileManager.setLayerOverlay(activeLayer, caveDirs);
        } else if (mm.isCaveMode() && mm.getMultiMapMetadata() != null) {
            int idx = mm.getCaveIndex();
            String caveDir = "";
            if (idx >= 0) {
                List<CompositeMapMetadata.SubImageInfo> subs = mm.getMultiMapMetadata().subImages();
                if (idx < subs.size()) {
                    caveDir = subs.get(idx).tileDir();
                }
            }
            tileManager.setLayerOverlay(-1, caveDir.isEmpty()
                    ? java.util.List.of() : java.util.List.of(caveDir));
        } else {
            tileManager.setLayerOverlay(-1, java.util.List.of());
        }
    }

    // ==================== GPU 变换 ====================

    private void applyTransforms(double scale, double ox, double oy,
                                  boolean scaleChanged, boolean viewportMoved,
                                  ViewportState vp) {
        if (scaleChanged) {
            worldScale.setX(scale);
            worldScale.setY(scale);
        }
        if (viewportMoved) {
            worldTranslate.setX(ox);
            worldTranslate.setY(oy);
        }

        if (vp.isNavMode()) {
            double pivotX = parent.getWidth() / 2;
            double pivotY = parent.getHeight() / 2;
            double navAngle = vp.getNavAngle();
            worldRotate.setPivotX(pivotX);
            worldRotate.setPivotY(pivotY);
            worldRotate.setAngle(-navAngle);
        } else if (worldRotate.getAngle() != 0) {
            worldRotate.setAngle(0);
        }
    }

    // ==================== 帧同步 ====================

    private void syncViewportState(ViewportState vp, boolean scaleChanged,
                                    boolean viewportMoved, double scale,
                                    double ox, double oy) {
        if (scaleChanged) {
            vp.setScale(scale);
        }
        if (viewportMoved) {
            vp.setOffsetX(ox);
            vp.setOffsetY(oy);
        }
    }

    // ==================== 渲染器更新 ====================

    private void updateRenderers(ViewportState vp, double scale, double ox, double oy) {
        playerRenderer.snapshotScale = scale;
        playerRenderer.snapshotOx = ox;
        playerRenderer.snapshotOy = oy;
        playerRenderer.snapshotPivotX = parent.getWidth() / 2;
        playerRenderer.snapshotPivotY = parent.getHeight() / 2;
        playerRenderer.snapshotPlayerX = vp.getSmoothedPlayerX();
        playerRenderer.snapshotPlayerY = vp.getSmoothedPlayerY();
        for (RenderLayer layer : renderLayers) {
            layer.onFrame();
        }
    }

    // ==================== 瓦片更新 ====================

    private void updateTiles(double ox, double oy, double scale, boolean scaleChanged) {
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
            tileManager.updateTiles(ox, oy, scale, level,
                    parent.getWidth(), parent.getHeight());
        }
    }

    // ==================== 视口适配 ====================

    private void autoFitViewport(MapContext mm) {
        double vw = parent.getWidth();
        double vh = parent.getHeight();
        if (vw <= 0 || vh <= 0) return;

        double mw = tileManager.getMapWidth(), mh = tileManager.getMapHeight();
        if (mw <= 0 || mh <= 0) return;

        // 按宽度适配（地图是 8192 宽的正方形或子图区域）
        double s = vw / mw;
        mm.setScale(s);
        mm.setOffsetX(0);
        double fittedH = mh * s;
        if (fittedH < vh) {
            mm.setOffsetY((vh - fittedH) / 2);
        } else {
            mm.setOffsetY(0);
        }
    }

    /**
     * 将视口约束到大陆局部边界内（8192×8192）。
     * 大陆始终是底图坐标空间，offsetY=0。
     */
    private void clampToLocalBounds(MapContext mm) {
        if (mm.getMultiMapMetadata() == null) return;
        double vw = parent.getWidth();
        double vh = parent.getHeight();
        if (vw <= 0 || vh <= 0) return;
        double scale = mm.getScale();
        double subH = tileManager.getMapHeight() * scale;

        double oy = mm.getOffsetY();

        if (subH >= vh) {
            oy = Math.clamp(oy, vh - subH, 0);
        } else {
            oy = (vh - subH) / 2;
        }

        mm.setOffsetY(oy);
    }

    // ==================== 公开 API ====================

    public void setHoveredPoint(ResourcePoint p) {
        hoverRenderer.setHoveredPoint(p);
    }

    public ResourcePoint getLastHoveredPoint() {
        return hoverRenderer.getLastHoveredPoint();
    }

    public void resetViewport() {
        firstFrame = true;
    }
}
