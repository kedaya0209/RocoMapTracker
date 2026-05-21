package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.UiConfig;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.PathContext;
import com.luoke.app.ui.util.CoordinateUtil;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.ui.render.MapRenderer;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.input.*;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 交互画布 — 事件分发编排器。
 * 将具体职责委托给子组件：
 * <ul>
 *   <li>{@link PathEditor} — 路径绘图/编辑逻辑</li>
 *   <li>{@link HoverManager} — 悬停检测 + Tooltip</li>
 *   <li>{@link ContextMenuManager} — 右键菜单</li>
 * </ul>
 */
@NotThreadSafe
@Slf4j
public class InteractiveCanvas extends Canvas {

    private final MapContext mapManager = MapContext.getInstance();
    private final CameraContext cameraManager = CameraContext.getInstance();
    private final PathContext pathContext = PathContext.getInstance();

    private final PathEditor pathEditor = new PathEditor();
    private final HoverManager hoverManager;
    private final ContextMenuManager contextMenuManager;

    @Setter
    private MapRenderer mapRenderer;
    @Setter
    private UiAnimator uiAnimator;

    private double lastMouseX, lastMouseY;
    private final KeyCombination saveCombo = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_ANY);
    private final KeyCombination undoCombo = new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_ANY);

    public InteractiveCanvas() {
        this.hoverManager = new HoverManager(this);
        this.contextMenuManager = new ContextMenuManager(this,
                () -> { if (mapRenderer != null) mapRenderer.resetViewport(); },
                () -> { if (mapRenderer != null) mapRenderer.markDirty(); });

        setFocusTraversable(true);
        bindViewport();
        initListeners();

        // 确保初始化后获得焦点以捕获快捷键
        Platform.runLater(this::requestFocus);
    }

    // ================================================================
    // 初始化
    // ================================================================

    private void bindViewport() {
        widthProperty().addListener(_ -> mapManager.setViewWidth(getWidth()));
        heightProperty().addListener(_ -> mapManager.setViewHeight(getHeight()));
    }

    private void initListeners() {
        setOnMouseEntered(_ -> requestFocus());
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyEvents);

        setOnMouseMoved(this::onMouseMoved);
        setOnMouseExited(this::onMouseExited);
        setOnMousePressed(this::onMousePressed);
        setOnMouseClicked(this::onMouseClicked);
        setOnMouseDragged(this::onMouseDragged);
        setOnMouseReleased(_ -> pathEditor.onMouseReleased());
        setOnScroll(this::onScroll);
    }

    // ================================================================
    // 鼠标事件
    // ================================================================

    private void onMouseMoved(MouseEvent e) {
        double[] logic = toLogic(e.getX(), e.getY());
        pathContext.setMouseLogicX(logic[0]);
        pathContext.setMouseLogicY(logic[1]);
        if (hoverManager.onMouseMoved(logic[0], logic[1], e.getScreenX(), e.getScreenY())) {
            // hoveredPoint 发生变化，同步到 MapRenderer
            if (mapRenderer != null) {
                mapRenderer.setHoveredPoint(hoverManager.getHoveredPoint());
            }
        }
    }

    private void onMouseExited(MouseEvent _e) {
        hoverManager.onMouseExited();
        if (mapRenderer != null) mapRenderer.setHoveredPoint(null);
    }

    private void onMousePressed(MouseEvent e) {
        requestFocus();
        if (uiAnimator != null) uiAnimator.closeSidebar();
        lastMouseX = e.getX();
        lastMouseY = e.getY();
        contextMenuManager.hideAll();

        if (e.getButton() == MouseButton.PRIMARY) {
            pathEditor.onMousePressed(e.getX(), e.getY());
        } else if (e.getButton() == MouseButton.SECONDARY) {
            double[] logic = toLogic(e.getX(), e.getY());
            contextMenuManager.setClickPoint(logic[0], logic[1]);
        }
    }

    private void onMouseClicked(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY) {
            boolean handled = pathEditor.onMouseClicked(e.getX(), e.getY(),
                    hoverManager.getHoveredPoint(),
                    () -> { if (mapRenderer != null) mapRenderer.markDirty(); });
            if (handled) return;
        }

        if (e.getButton() == MouseButton.SECONDARY) {
            boolean deleted = pathEditor.onSecondaryClicked(e.getX(), e.getY(),
                    () -> { if (mapRenderer != null) mapRenderer.markDirty(); });
            if (deleted) return;

            ResourcePoint hovered = hoverManager.getHoveredPoint();
            if (hovered != null) {
                contextMenuManager.showImageMenu(e.getScreenX(), e.getScreenY(), hovered);
            } else {
                contextMenuManager.showMapMenu(e.getScreenX(), e.getScreenY());
            }
        } else {
            contextMenuManager.hideAll();
        }
    }

    private void onMouseDragged(MouseEvent e) {
        if (pathEditor.isDragging()) {
            pathEditor.onMouseDragged(e.getX(), e.getY(),
                    hoverManager.getHoveredPoint(),
                    () -> { if (mapRenderer != null) mapRenderer.markDirty(); });
            return;
        }

        if (cameraManager.isFollowMode()) cameraManager.setFollowMode(false);
        // 拖拽地图时清除 hover，避免残留
        hoverManager.clearHover();

        double dx = e.getX() - lastMouseX;
        double dy = e.getY() - lastMouseY;
        mapManager.setOffsetX(mapManager.getOffsetX() + dx);
        mapManager.setOffsetY(mapManager.getOffsetY() + dy);
        mapManager.ensureBounds();
        lastMouseX = e.getX();
        lastMouseY = e.getY();
    }

    private void onScroll(ScrollEvent e) {
        double factor = e.getDeltaY() > 0
                ? UiConfig.INTERACTIVE_ZOOM_FACTOR
                : (2 - UiConfig.INTERACTIVE_ZOOM_FACTOR);

        if (cameraManager.isFollowMode() && cameraManager.hasValidPlayerPosition()) {
            double newScale = cameraManager.getFollowScale() * factor;
            cameraManager.setFollowScale(Math.clamp(newScale,
                    ViewConfig.INTERACTIVE_FOLLOW_MIN_SCALE,
                    ViewConfig.INTERACTIVE_FOLLOW_MAX_SCALE));
        } else {
            if (cameraManager.isFollowMode()) {
                cameraManager.setFollowScale(mapManager.getScale());
            }
            mapManager.zoom(factor, e.getX(), e.getY());
        }
    }

    // ================================================================
    // 键盘事件
    // ================================================================

    private void handleKeyEvents(KeyEvent event) {
        if (saveCombo.match(event)) {
            RouteManagerStage.getInstance().handleSave();
            event.consume();
        } else if (undoCombo.match(event)) {
            boolean undone = pathEditor.undoLastNode(
                    () -> { if (mapRenderer != null) mapRenderer.markDirty(); });
            if (undone) event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            contextMenuManager.hideAll();
            pathEditor.exitEditingMode(
                    () -> { if (mapRenderer != null) mapRenderer.markDirty(); });
            event.consume();
        }
    }

    // ================================================================
    // 坐标转换
    // ================================================================

    /**
     * 屏幕 Canvas 坐标 → 地图逻辑坐标（同时处理 X/Y，导航旋转时需要两个值）。
     */
    public double[] toLogic(double canvasX, double canvasY) {
        double ox = mapManager.getOffsetX();
        double oy = mapManager.getOffsetY();
        double scale = mapManager.getScale();
        if (cameraManager.isNavMode() && cameraManager.getNavAngle() != 0) {
            double pivotX = getWidth() / 2;
            double pivotY = getHeight() / 2;
            return CoordinateUtil.screenToWorld(canvasX, canvasY, ox, oy, scale,
                    cameraManager.getNavAngle(), pivotX, pivotY);
        }
        return new double[]{(canvasX - ox) / scale, (canvasY - oy) / scale};
    }

    /**
     * @deprecated 仅保留给外部非 nav 场景使用；有 nav 模式请用 {@link #toLogic(double, double)}。
     */
    @Deprecated
    public double toLogicX(double canvasX) {
        return (canvasX - mapManager.getOffsetX()) / mapManager.getScale();
    }

    /**
     * @deprecated 仅保留给外部非 nav 场景使用；有 nav 模式请用 {@link #toLogic(double, double)}。
     */
    @Deprecated
    public double toLogicY(double canvasY) {
        return (canvasY - mapManager.getOffsetY()) / mapManager.getScale();
    }
}
