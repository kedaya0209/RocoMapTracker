package com.luoke.app.ui.render;

import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.ui.component.InteractiveCanvas;
import com.luoke.app.ui.component.StatsOverlay;
import com.luoke.app.utils.MultiResMapCache;
import javafx.animation.AnimationTimer;
import javafx.geometry.Rectangle2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.*;

import java.nio.IntBuffer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 4 层混合渲染器 — 最小化 GPU 纹理上传。
 * <p>
 * mapView (ImageView):     地图背景，PixelBuffer + GPU viewport 平移。
 * staticCanvas (Canvas):   图标 + 路线，GPU translateX/Y 平移，仅在偏移越界/缩放/数据变更时重绘。
 * playerView (ImageView):  玩家图标，setTranslateX/Y + setRotate，零纹理上传。
 * interactiveCanvas:       输入事件 + hover 高亮，不参与 GPU 平移。
 * <p>
 * 关键优化：
 * 1. 玩家移动 → 仅 playerView GPU transform，完全不触发任何 Canvas 重绘
 * 2. 地图平移 → mapView GPU viewport pan + staticCanvas GPU translate，零纹理上传
 * 3. Canvas 重绘仅在：缩放、偏移超出边距、数据变更、hover 变化时发生
 */
public class RenderLoop {

    private static final int PLAYER_ICON_SIZE = 72;
    /**
     * 纹理边距比例：纹理 = 视口 + 2×边距，提供 GPU 平移余量
     */
    private static final double PAN_MARGIN = 0.3;

    private final ImageView mapView;
    private final Canvas staticCanvas;
    private final ImageView playerView;
    private final InteractiveCanvas interactiveCanvas;

    private final AtomicBoolean dirtyBg = new AtomicBoolean(false);
    private final AtomicBoolean dirtyStatic = new AtomicBoolean(false);
    private final AtomicBoolean dirtyHover = new AtomicBoolean(false);
    private final AtomicBoolean dirtyOverlay = new AtomicBoolean(false);
    private Image playerIcon;
    // 带 margin 的 PixelBuffer
    private int[] bgPixels;
    // 上次 fillTexture 时的锚点
    private double texAnchorOx, texAnchorOy, texAnchorScale;
    private PixelBuffer<IntBuffer> bgPixelBuffer;
    private WritableImage bgImage;
    private int texAnchorVw, texAnchorVh;
    private int texW, texH, marginX, marginY;
    // staticCanvas GPU 平移：上次重绘时的 offset
    private double lastStaticOx, lastStaticOy;
    private boolean firstRender = true;
    private final AnimationTimer renderTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            boolean bg = dirtyBg.getAndSet(false);
            boolean st = dirtyStatic.getAndSet(false);
            boolean hv = dirtyHover.getAndSet(false);
            boolean ov = dirtyOverlay.getAndSet(false);
            if (!bg && !st && !hv && !ov) return;
            renderDirtyLayers(bg, st, hv, ov);
        }
    };

    public RenderLoop(ImageView mapView, Canvas staticCanvas, ImageView playerView,
                      InteractiveCanvas interactiveCanvas) {
        this.mapView = mapView;
        this.staticCanvas = staticCanvas;
        this.playerView = playerView;
        this.interactiveCanvas = interactiveCanvas;
        HookRegistry.INSTANCE.register(new SnapshotInvalidationHook());
        renderTimer.start();
    }

    // ==================== Dirty Flag API ====================

    /**
     * 全量重绘（缩放、窗口变化、重置视角等）
     */
    public void markDirty() {
        dirtyBg.set(true);
        dirtyStatic.set(true);
        dirtyHover.set(true);
        dirtyOverlay.set(true);
    }

    /** 仅地图背景平移（拖拽、跟随模式偏移变化） */
    public void markDirtyBg() {
        dirtyBg.set(true);
    }

    /**
     * 仅静态层重绘（资源点/路线数据变更）
     */
    public void markDirtyStatic() {
        dirtyStatic.set(true);
    }

    /**
     * 仅 hover 层重绘（鼠标移动改变 hover 目标）
     */
    public void markDirtyHover() {
        dirtyHover.set(true);
    }

    /** 仅覆盖层更新（玩家移动） */
    public void markDirtyOverlay() {
        dirtyOverlay.set(true);
    }

    public void setPlayerImage(Image image) {
        this.playerIcon = image;
        playerView.setImage(image);
    }

    public void dispose() {
        renderTimer.stop();
    }

    // ==================== 渲染主循环 ====================

    private void renderDirtyLayers(boolean bg, boolean st, boolean hv, boolean ov) {
        CameraContext camera = CameraContext.getInstance();
        camera.updateViewport();

        MapContext mm = MapContext.getInstance();
        if (!MultiResMapCache.getInstance().isInitialized()) return;

        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();
        double scale = mm.getScale();

        // 1. 地图背景 — GPU viewport 平移
        if (bg) {
            updateMapView(mm, ox, oy, scale);
        }

        // 2. 静态层 (图标 + 路线) — 数据变更/缩放时重绘，否则 GPU translate 平移
        if (st) {
            renderStaticLayer(mm, ox, oy, scale);
        } else if (bg) {
            // 背景平移了但静态层没重绘 → GPU translate 跟随
            panStaticCanvas(ox, oy);
        }

        // 3. Hover 高亮 — 仅在 hover 目标变化时重绘
        if (hv || st) {
            renderHoverLayer(mm, ox, oy, scale);
        }

        // 4. 玩家图标 — GPU transform
        if (ov) {
            updatePlayerView(mm, ox, oy, scale);
        }

        StatsOverlay.getInstance().update();
    }

    // ==================== 静态层 (图标 + 路线) ====================

    private void renderStaticLayer(MapContext mm, double ox, double oy, double scale) {
        double w = staticCanvas.getWidth();
        double h = staticCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = staticCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        gc.save();
        gc.translate(ox, oy);
        gc.scale(scale, scale);
        interactiveCanvas.drawAllResourceIcons(gc);
        PathRenderer.draw(gc);
        gc.restore();

        // 重置 GPU translate，记录当前 offset
        staticCanvas.setTranslateX(0);
        staticCanvas.setTranslateY(0);
        lastStaticOx = ox;
        lastStaticOy = oy;
    }

    /**
     * 不重绘 Canvas，仅通过 GPU translate 平移已有像素内容。
     * 当地图偏移 (ox, oy) 变化但缩放不变时，平移 staticCanvas 使图标与地图背景保持同步。
     */
    private void panStaticCanvas(double ox, double oy) {
        double dx = ox - lastStaticOx;
        double dy = oy - lastStaticOy;
        staticCanvas.setTranslateX(dx);
        staticCanvas.setTranslateY(dy);

        // 超出边距 → 强制重绘（避免 Canvas 内容被 clip 露出空白）
        double mw = staticCanvas.getWidth() * PAN_MARGIN;
        double mh = staticCanvas.getHeight() * PAN_MARGIN;
        if (Math.abs(dx) > mw || Math.abs(dy) > mh) {
            dirtyStatic.set(true);
        }
    }

    // ==================== Hover 层 ====================

    private void renderHoverLayer(MapContext mm, double ox, double oy, double scale) {
        double w = interactiveCanvas.getWidth();
        double h = interactiveCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext gc = interactiveCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        gc.save();
        gc.translate(ox, oy);
        gc.scale(scale, scale);
        interactiveCanvas.drawHoverOverlay(gc);
        gc.restore();
    }

    // ==================== 玩家图标 ====================

    private void updatePlayerView(MapContext mm, double ox, double oy, double scale) {
        if (!mm.isPlayerInitialized() || playerIcon == null) {
            playerView.setVisible(false);
            return;
        }
        playerView.setVisible(true);

        double screenX = ox + mm.getPlayerX() * scale;
        double screenY = oy + mm.getPlayerY() * scale;

        // unmanaged ImageView: layoutX/Y = 0, translate 直接定位
        double half = PLAYER_ICON_SIZE / 2.0;
        playerView.setTranslateX(screenX - half);
        playerView.setTranslateY(screenY - half);
        playerView.setRotate(mm.getPlayerAngle());
    }

    // ==================== 地图背景 (ImageView + GPU 平移) ====================

    private void updateMapView(MapContext mm, double ox, double oy, double scale) {
        double w = interactiveCanvas.getWidth();
        double h = interactiveCanvas.getHeight();
        int vw = (int) w, vh = (int) h;
        if (vw <= 0 || vh <= 0) return;

        boolean sizeChanged = vw != texAnchorVw || vh != texAnchorVh;
        boolean scaleChanged = Math.abs(scale - texAnchorScale) > 1e-9;

        if (firstRender || sizeChanged || scaleChanged) {
            fillTexture(mm, ox, oy, scale, vw, vh);
            return;
        }

        // GPU 平移：当前视口是否在已有纹理内
        double vpX = texAnchorOx - ox;
        double vpY = texAnchorOy - oy;

        if (vpX >= 0 && vpY >= 0 && vpX + vw <= texW && vpY + vh <= texH) {
            mapView.setViewport(new Rectangle2D(vpX, vpY, vw, vh));
            return;
        }

        // 越界 → 重新填充
        fillTexture(mm, ox, oy, scale, vw, vh);
    }

    private void fillTexture(MapContext mm, double ox, double oy, double scale, int vw, int vh) {
        marginX = (int) (vw * PAN_MARGIN);
        marginY = (int) (vh * PAN_MARGIN);
        texW = vw + 2 * marginX;
        texH = vh + 2 * marginY;
        double fillOx = ox + marginX;
        double fillOy = oy + marginY;

        if (bgPixels == null || bgPixels.length != texW * texH) {
            bgPixels = new int[texW * texH];
            IntBuffer intBuf = IntBuffer.wrap(bgPixels);
            bgPixelBuffer = new PixelBuffer<>(texW, texH, intBuf, PixelFormat.getIntArgbPreInstance());
            bgImage = new WritableImage(bgPixelBuffer);
        }

        MultiResMapCache cache = MultiResMapCache.getInstance();
        cache.fillPixels(bgPixels, texW, 0, 0, texW, texH, fillOx, fillOy, scale,
                (int) mm.getMapWidth(), (int) mm.getMapHeight());
        bgPixelBuffer.updateBuffer(b -> null);

        mapView.setImage(bgImage);
        mapView.setViewport(new Rectangle2D(marginX, marginY, vw, vh));

        texAnchorOx = fillOx;
        texAnchorOy = fillOy;
        texAnchorScale = scale;
        texAnchorVw = vw;
        texAnchorVh = vh;
        firstRender = false;
    }

    // ==================== Hook ====================

    private class SnapshotInvalidationHook extends AbstractGenericHook<Object> {
        @Override
        public Set<HookEventType> supportedEvents() {
            return Set.of(HookEventType.RESOURCE_POINT_CHANGED);
        }

        @Override
        public void onEvent(HookEventType eventType, Object data) {
            markDirtyStatic();
        }
    }
}
