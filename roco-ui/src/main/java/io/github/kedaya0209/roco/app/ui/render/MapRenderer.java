package io.github.kedaya0209.roco.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.service.NavigationController;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.context.CameraContext;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.IHook;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.ui.component.StatsOverlay;
import io.github.kedaya0209.roco.app.ui.service.resource.TileManager;
import javafx.animation.KeyFrame;
import javafx.application.Platform;
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
import java.util.Set;

/**
 * 地图渲染器编排器。
 * <p>
 * 渲染在子图局部坐标空间（8192x8192）中工作。从 MapContext 读取拼接坐标
 * 后转换为局部坐标再用于 worldGroup 变换和 TileManager。
 */
@NotThreadSafe
@Slf4j
public class MapRenderer implements IHook<Object> {

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
    private final RouteRenderer routeRenderer;
    private final HoverRenderer hoverRenderer;
    private final List<RenderLayer> renderLayers;

    private TileManager tileManager;
    // 视口追踪
    private double lastScale;
    private double lastOx = Double.NaN;
    private double lastOy = Double.NaN;
    private boolean firstFrame = true;

    // 上次活跃子图偏移，用于检测切换
    private double lastSubOffsetY = 0;

    public MapRenderer(Pane parent) {
        this.parent = parent;

        worldGroup = new Group();
        tileManager = new TileManager(worldGroup, 8192, 8192);
        worldGroup.setPickOnBounds(false);
        // 变换链：Rotate × Translate × Scale (从右到左作用)
        worldScale = new Scale(1, 1, 0, 0);
        worldTranslate = new Translate(0, 0);
        worldRotate = new Rotate(0, 0, 0);
        worldGroup.getTransforms().addAll(worldRotate, worldTranslate, worldScale);

        navigationController = new NavigationController();

        iconLayerManager = new IconLayerManager(worldGroup);
        playerRenderer = new PlayerRenderer();
        routeRenderer = new RouteRenderer(parent);
        hoverRenderer = new HoverRenderer(parent);
        renderLayers = List.of(playerRenderer, routeRenderer, iconLayerManager, hoverRenderer);

        // 层级：worldGroup(瓦片+图标) → 路线 → hover → 玩家
        parent.getChildren().addAll(worldGroup,
                routeRenderer.getNode(),
                hoverRenderer.getNode(),
                playerRenderer.getNode());

        loop = new Timeline(new KeyFrame(Duration.millis(RenderConfig.RENDER_FRAME_INTERVAL_MS), _ -> onFrame()));
        loop.setCycleCount(Timeline.INDEFINITE);

        HookRegistry.INSTANCE.register(this);
    }

    /**
     * 初始化地图尺寸并构建图标。
     */
    public void init(int mapW, int mapH, CompositeMapMetadata metadata) {
        this.tileManager = new TileManager(worldGroup, 8192, 8192);
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

    public void markDirty() {
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

        MapContext mm = MapContext.getInstance();
        CameraContext cam = CameraContext.getInstance();

        cam.updateViewport();

        // 局部视口边界约束（确保不滑出活跃子图区域）
        clampToLocalBounds(mm);

        if (mm.isPlayerInitialized()) {
            navigationController.update(mm.getPlayerAngle(), cam.isNavMode());
        }

        // 拼接坐标（来自 MapContext）
        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();
        double scale = mm.getScale();

        double subOffsetY = mm.getActiveSubImageOffsetY();

        // 子图切换 → 通知 TileManager
        if (Math.abs(subOffsetY - lastSubOffsetY) > 1e-9) {
            lastSubOffsetY = subOffsetY;
            if (mm.getMultiMapMetadata() != null
                    && mm.getActiveSubImageIndex() < mm.getMultiMapMetadata().subImages().size()) {
                var sub = mm.getMultiMapMetadata().subImages().get(mm.getActiveSubImageIndex());
                tileManager.setActiveSubImage(sub.index(), sub.tileDir());
            }
        }

        // 转换为子图局部坐标
        double localOy = oy + subOffsetY * scale;
        double localPlayerY = mm.getRenderPlayerY();

        // 首帧适配
        if (firstFrame) {
            if (parent.getWidth() > 0 && parent.getHeight() > 0) {
                firstFrame = false;
                autoFitViewport(mm);
                ox = mm.getOffsetX();
                oy = mm.getOffsetY();
                scale = mm.getScale();
                localOy = oy + subOffsetY * scale;
                log.info("首帧 view={}x{} scale={} localOy={} subOffsetY={}",
                        (int) parent.getWidth(), (int) parent.getHeight(),
                        String.format("%.4f", scale), (int) localOy, (int) subOffsetY);
            }
        }

        boolean scaleChanged = Math.abs(scale - lastScale) > 1e-9;
        boolean viewportMoved = firstFrame
                || Math.abs(ox - lastOx) > 1e-9
                || Math.abs(localOy - lastOy) > 1e-9;

        // ====== 洞穴模式同步 ======
        if (mm.getMultiMapMetadata() != null && mm.isCaveMode()) {
            int idx = mm.getCaveIndex();
            var subs = mm.getMultiMapMetadata().subImages();
            String caveDir = (idx >= 0 && idx < subs.size()) ? subs.get(idx).tileDir() : "";
            tileManager.setCaveMode(true, idx, caveDir);
        } else {
            tileManager.setCaveMode(false, -1, null);
        }

        // ====== GPU 变换（局部坐标系） ======
        if (scaleChanged) {
            worldScale.setX(scale);
            worldScale.setY(scale);
        }
        if (viewportMoved) {
            worldTranslate.setX(ox);
            worldTranslate.setY(localOy);
        }

        // ====== 导航旋转 ======
        if (cam.isNavMode()) {
            double pivotX = parent.getWidth() / 2;
            double pivotY = parent.getHeight() / 2;
            double navAngle = cam.getNavAngle();
            worldRotate.setPivotX(pivotX);
            worldRotate.setPivotY(pivotY);
            worldRotate.setAngle(-navAngle);
        } else if (worldRotate.getAngle() != 0) {
            worldRotate.setAngle(0);
        }

        // ====== 子渲染器快照（玩家用局部坐标） ======
        playerRenderer.snapshotScale = scale;
        playerRenderer.snapshotOx = ox;
        playerRenderer.snapshotOy = localOy;
        playerRenderer.snapshotPlayerX = mm.getPlayerX();
        playerRenderer.snapshotPlayerY = localPlayerY;
        playerRenderer.snapshotPivotX = parent.getWidth() / 2;
        playerRenderer.snapshotPivotY = parent.getHeight() / 2;
        for (RenderLayer layer : renderLayers) {
            layer.onFrame();
        }

        // ====== 瓦片加载（局部坐标） ======
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
            tileManager.updateTiles(ox, localOy, scale, level,
                    parent.getWidth(), parent.getHeight());
        }

        lastScale = scale;
        lastOx = ox;
        lastOy = localOy;
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
     * 将视口约束到活跃子图的局部边界内（8192×8192）。
     * 避免 CameraContext 使用拼接坐标计算视口时滑出子图区域。
     */
    private void clampToLocalBounds(MapContext mm) {
        if (mm.getMultiMapMetadata() == null) return;
        double vw = parent.getWidth();
        double vh = parent.getHeight();
        if (vw <= 0 || vh <= 0) return;
        double scale = mm.getScale();
        double subH = tileManager.getMapHeight() * scale;

        double oy = mm.getOffsetY();
        double subOy = mm.getActiveSubImageOffsetY();
        double localOy = oy + subOy * scale;

        if (subH >= vh) {
            localOy = Math.clamp(localOy, vh - subH, 0);
        } else {
            localOy = (vh - subH) / 2;
        }

        mm.setOffsetY(localOy - subOy * scale);
    }

    // ==================== IHook ====================

    @Override
    public Set<HookEventType> supportedEvents() {
        return Set.of(HookEventType.RESOURCE_POINT_CHANGED);
    }

    @Override
    public void onEvent(HookEventType eventType, Object data) {
        Platform.runLater(this::markDirty);
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
