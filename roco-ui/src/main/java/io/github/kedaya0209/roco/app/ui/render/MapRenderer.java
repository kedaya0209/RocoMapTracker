package io.github.kedaya0209.roco.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.service.NavigationController;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.context.CameraContext;
import io.github.kedaya0209.roco.app.context.MapContext;
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
 * 地图渲染器编排器 — 维护帧循环 + 世界 GPU 变换 + 瓦片调度，
 * 将具体渲染职责委托给子渲染器：
 * <ul>
 *   <li>{@link IconLayerManager} — 图标 ImageView 构建 + 变灰切换</li>
 *   <li>{@link PlayerRenderer} — 玩家图标 + 波纹 + 光环</li>
 *   <li>{@link RouteRenderer} — 路线 Canvas 绘制</li>
 *   <li>{@link HoverRenderer} — hover 高亮 Canvas 绘制</li>
 * </ul>
 * <p>
 * 瓦片层：
 * - 多分辨率金字塔 (100%/50%/25%/12.5%/6.25%)，256×256 瓦片
 * - 所有瓦片放入 worldGroup，统一 GPU translate/scale 变换
 * 图标层：
 * - ImageView 节点直接放入 worldGroup，与瓦片共用 GPU 变换
 * - 从纹理图集取源区域（setViewport），无任何 CPU 重绘
 * 路线层：
 * - 独立 Canvas 屏幕坐标绘制，缩放时全量重绘，平移时 GPU 节点 translate
 * 玩家：
 * - ImageView 放入 playerGroup，世界坐标定位，自动跟随地图变换
 */
@NotThreadSafe
@Slf4j
public class MapRenderer {

    @Getter
    private final Pane parent;
    private final Group worldGroup;
    // worldGroup 变换：Rotate(-navAngle, pivotX, pivotY) × Translate(ox,oy) × Scale(scale,scale,0,0)
    private final Scale worldScale;
    private final Translate worldTranslate;
    private final Rotate worldRotate;
    private final Timeline loop;

    // 导航模式旋转控制器
    private final NavigationController navigationController;

    // 子渲染器
    private final IconLayerManager iconLayerManager;
    private final PlayerRenderer playerRenderer;
    private final RouteRenderer routeRenderer;
    private final HoverRenderer hoverRenderer;
    private final List<RenderLayer> renderLayers;

    private TileManager tileManager;
    // 视口追踪（避免不必要的 JavaFX 属性触发）
    private double lastScale;
    private double lastOx = Double.NaN;
    private double lastOy = Double.NaN;
    private boolean firstFrame = true;

    // ==================== 构造与初始化 ====================

    public MapRenderer(Pane parent) {
        this.parent = parent;

        worldGroup = new Group();
        tileManager = new TileManager(worldGroup, 0, 0);
        worldGroup.setPickOnBounds(false);
        // 变换链： Rotate(-navAngle, pivotX, pivotY) × Translate(ox,oy) × Scale(scale,scale,0,0)
        // JavaFX 变换按列表顺序从右到左合成，因此最后一个元素最先作用于子节点。
        // Scale 最先应用到子节点坐标 → 然后 Translate → 最后 Rotate（绕视口中心）
        worldScale = new Scale(1, 1, 0, 0);
        worldTranslate = new Translate(0, 0);
        worldRotate = new Rotate(0, 0, 0);
        worldGroup.getTransforms().addAll(worldRotate, worldTranslate, worldScale);

        // 导航模式控制器
        navigationController = new NavigationController();

        // 子渲染器
        iconLayerManager = new IconLayerManager(worldGroup);
        playerRenderer = new PlayerRenderer();
        routeRenderer = new RouteRenderer(parent);
        hoverRenderer = new HoverRenderer(parent);
        renderLayers = List.of(playerRenderer, routeRenderer, iconLayerManager, hoverRenderer);

        // 层级：瓦片 → 图标(都在worldGroup) → 路线 → hover → 玩家
        parent.getChildren().addAll(worldGroup,
                routeRenderer.getNode(),
                hoverRenderer.getNode(),
                playerRenderer.getNode());

        loop = new Timeline(new KeyFrame(Duration.millis(RenderConfig.RENDER_FRAME_INTERVAL_MS), _ -> onFrame()));
        loop.setCycleCount(Timeline.INDEFINITE);

    }

    /**
     * 初始化地图尺寸并构建图标 ImageView 层。
     */
    public void init(int mapW, int mapH) {
        this.tileManager = new TileManager(worldGroup, mapW, mapH);
        iconLayerManager.buildIconLayer();
    }

    public void setPlayerImage(Image image) {
        playerRenderer.setPlayerImage(image);
    }

    // ==================== 帧循环 ====================

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

    // ==================== 视口适配 ====================

    private void onFrameInternal() {
        StatsOverlay.getInstance().update();

        CameraContext cam = CameraContext.getInstance();
        cam.updateViewport();

        // offset/scale 从 MapContext 读取（引擎源，每帧随拖拽/缩放变化）
        MapContext mm = MapContext.getInstance();
        // nav/follow/player 状态从 ViewportState 读取
        ViewportState vp = ViewportState.getInstance();

        // 导航模式：每帧更新旋转控制
        if (vp.isPlayerInitialized()) {
            navigationController.update(vp.getPlayerAngle(), vp.isNavMode());
        }

        // 帧同步：navAngle 从 CameraContext → ViewportState（不经过 EventBus，避免每帧发布）
        vp.setNavAngle(cam.getNavAngle());

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
                vp.syncFromMapContext();
                log.info("首帧 view={}x{} scale={} ox={} oy={}",
                        (int) parent.getWidth(), (int) parent.getHeight(),
                        String.format("%.4f", scale), (int) ox, (int) oy);
            }
        }

        boolean scaleChanged = Math.abs(scale - lastScale) > 1e-9;
        boolean viewportMoved = firstFrame || Math.abs(ox - lastOx) > 1e-9 || Math.abs(oy - lastOy) > 1e-9;

        // ====== GPU 变换 ======
        if (scaleChanged) {
            worldScale.setX(scale);
            worldScale.setY(scale);
        }
        if (viewportMoved) {
            worldTranslate.setX(ox);
            worldTranslate.setY(oy);
        }

        // ====== 导航模式旋转 ======
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

        // ====== 帧同步 ViewportState（仅在值变化时 set，避免无效 listener 触发） ======
        if (scaleChanged) {
            vp.setScale(scale);
        }
        if (viewportMoved) {
            vp.setOffsetX(ox);
            vp.setOffsetY(oy);
        }

        // ====== 子渲染器 ======
        playerRenderer.snapshotScale = scale;
        playerRenderer.snapshotOx = ox;
        playerRenderer.snapshotOy = oy;
        playerRenderer.snapshotPivotX = parent.getWidth() / 2;
        playerRenderer.snapshotPivotY = parent.getHeight() / 2;
        for (RenderLayer layer : renderLayers) {
            layer.onFrame();
        }

        // ====== 瓦片加载 ======
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

        lastScale = scale;
        lastOx = ox;
        lastOy = oy;
    }

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

    // ==================== 公开 API ====================

    public void setHoveredPoint(ResourcePoint p) {
        hoverRenderer.setHoveredPoint(p);
    }

    public ResourcePoint getLastHoveredPoint() {
        return hoverRenderer.getLastHoveredPoint();
    }

    /**
     * 重置视角到首帧适配状态
     */
    public void resetViewport() {
        firstFrame = true;
    }
}
