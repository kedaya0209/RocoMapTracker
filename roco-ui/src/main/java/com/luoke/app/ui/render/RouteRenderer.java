package com.luoke.app.ui.render;

import com.luoke.app.config.RenderConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.ui.util.CoordinateUtil;
import com.luoke.app.context.PathContext;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.RoutePath;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * 路线渲染器 — Canvas 屏幕坐标绘制 + 脏检测 + 平移补偿。
 * <p>
 * 缩放变化时全量重绘，纯平移时通过 GPU translate 补偿避免重绘。
 * 通过 PathContext / CameraContext 单例自动检测状态变化。
 */
public class RouteRenderer implements RenderLayer {

    private final Canvas routeCanvas;
    private final GraphicsContext routeGc;

    // 路线状态跟踪
    private RoutePath lastActiveRoute;
    private PathContext.Mode lastMode;
    private boolean followWasOn;
    private double routeDrawOx;
    private double routeDrawOy;
    private double lastScale;
    private double lastNavAngle;
    private boolean routeDirty = true;

    public RouteRenderer(Pane parent) {
        routeCanvas = new Canvas();
        routeCanvas.setMouseTransparent(true);
        routeCanvas.setPickOnBounds(false);
        routeCanvas.widthProperty().bind(parent.widthProperty());
        routeCanvas.heightProperty().bind(parent.heightProperty());
        routeGc = routeCanvas.getGraphicsContext2D();
    }

    @Override
    public Node getNode() {
        return routeCanvas;
    }

    public void markDirty() {
        routeDirty = true;
    }

    @Override
    public void onFrame() {
        MapContext mm = MapContext.getInstance();
        CameraContext cam = CameraContext.getInstance();
        double scale = mm.getScale();
        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();
        boolean scaleChanged = Math.abs(scale - lastScale) > 1e-9;

        // 导航模式旋转参数
        double navAngle = cam.isNavMode() ? cam.getNavAngle() : 0;
        double pivotX = routeCanvas.getWidth() / 2;
        double pivotY = routeCanvas.getHeight() / 2;

        PathContext pc = PathContext.getInstance();

        // 路线状态变化检测
        RoutePath activeRoute = pc.getActiveRoute();
        PathContext.Mode mode = pc.getCurrentMode();
        if (activeRoute != lastActiveRoute || mode != lastMode) {
            lastActiveRoute = activeRoute;
            lastMode = mode;
            routeDirty = true;
        }

        // 跟随模式切换 → 路线重绘
        if (cam.isFollowMode() != followWasOn) {
            followWasOn = cam.isFollowMode();
            routeDirty = true;
        }

        // 导航模式角度变化 → 路线重绘
        if (Math.abs(navAngle - lastNavAngle) > 1e-6) {
            lastNavAngle = navAngle;
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
            redrawRoutes(ox, oy, scale, navAngle, pivotX, pivotY);
            routeDrawOx = ox;
            routeDrawOy = oy;
            routeCanvas.setTranslateX(0);
            routeDirty = false;
        }

        lastScale = scale;
    }

    /**
     * 全量重绘路线层 — 世界坐标转屏幕坐标直接绘制（含导航模式旋转补偿）
     */
    private void redrawRoutes(double ox, double oy, double scale, double navAngle, double pivotX, double pivotY) {
        double w = routeCanvas.getWidth();
        double h = routeCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        routeGc.clearRect(0, 0, w, h);

        PathContext pc = PathContext.getInstance();
        RoutePath active = pc.getActiveRoute();
        if (active == null) return;

        // 1. 背景路线（置灰/半透明）
        routeGc.setLineWidth(RenderConfig.ROUTE_INACTIVE_WIDTH);
        routeGc.setStroke(Color.web("#888888", 0.6));
        for (RoutePath path : pc.getSavedRoutes()) {
            if (path == pc.getActiveRoute()) continue;
            renderPathScreen(routeGc, path.getNodes(), ox, oy, scale, navAngle, pivotX, pivotY);
        }

        // 2. 活跃路线（绿色）
        routeGc.setStroke(Color.CHARTREUSE);
        routeGc.setLineWidth(RenderConfig.ROUTE_ACTIVE_WIDTH);
        renderPathScreen(routeGc, active.getNodes(), ox, oy, scale, navAngle, pivotX, pivotY);

        // 3. UI 叠加（绘图/编辑模式）
        if (pc.getCurrentMode() != PathContext.Mode.VIEW) {
            // 预览虚线（橡皮筋）
            if (pc.getCurrentMode() == PathContext.Mode.DRAWING && !active.getNodes().isEmpty()) {
                Point lastNode = active.getNodes().getLast();
                double[] p1 = CoordinateUtil.worldToScreen(lastNode.getX(), lastNode.getY(), ox, oy, scale, navAngle, pivotX, pivotY);
                double[] p2 = CoordinateUtil.worldToScreen(pc.getMouseLogicX(), pc.getMouseLogicY(), ox, oy, scale, navAngle, pivotX, pivotY);
                routeGc.setStroke(Color.web("#FFFFFF", 0.7));
                routeGc.setLineDashes(RenderConfig.ROUTE_DASH_LENGTH);
                routeGc.strokeLine(p1[0], p1[1], p2[0], p2[1]);
                routeGc.setLineDashes(null);
            }

            // 节点锚点圆
            routeGc.setFill(Color.WHITE);
            routeGc.setStroke(Color.BLUE);
            double r = RenderConfig.ROUTE_NODE_RADIUS;
            for (Point node : active.getNodes()) {
                double[] p = CoordinateUtil.worldToScreen(node.getX(), node.getY(), ox, oy, scale, navAngle, pivotX, pivotY);
                routeGc.fillOval(p[0] - r, p[1] - r, r * 2, r * 2);
                routeGc.strokeOval(p[0] - r, p[1] - r, r * 2, r * 2);
            }
        }
    }

    /**
     * 以屏幕坐标绘制单条路径（含导航模式旋转补偿）
     */
    private static void renderPathScreen(GraphicsContext gc, List<Point> nodes,
                                          double ox, double oy, double scale,
                                          double navAngle, double pivotX, double pivotY) {
        if (nodes.size() < 2) return;
        double[] first = CoordinateUtil.worldToScreen(nodes.getFirst().getX(), nodes.getFirst().getY(), ox, oy, scale, navAngle, pivotX, pivotY);
        gc.beginPath();
        gc.moveTo(first[0], first[1]);
        for (int i = 1; i < nodes.size(); i++) {
            double[] p = CoordinateUtil.worldToScreen(nodes.get(i).getX(), nodes.get(i).getY(), ox, oy, scale, navAngle, pivotX, pivotY);
            gc.lineTo(p[0], p[1]);
        }
        gc.stroke();
    }
}
