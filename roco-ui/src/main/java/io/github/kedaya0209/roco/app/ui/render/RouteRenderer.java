package io.github.kedaya0209.roco.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import io.github.kedaya0209.roco.app.ui.util.CoordinateUtil;
import io.github.kedaya0209.roco.app.context.PathContext;
import io.github.kedaya0209.roco.app.map.model.Point;
import io.github.kedaya0209.roco.app.map.model.RoutePath;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import java.util.List;

/**
 * 路线渲染器 — Canvas 屏幕坐标每帧直接绘制。
 * <p>
 * 通过 PathContext / CameraContext 单例自动读取状态。
 */
@NotThreadSafe
public class RouteRenderer implements RenderLayer {

    private final Canvas routeCanvas;
    private final GraphicsContext routeGc;

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

    @Override
    public void onFrame() {
        ViewportState vp = ViewportState.getInstance();
        double ox = vp.getOffsetX();
        double oy = vp.getOffsetY();
        double scale = vp.getScale();
        double navAngle = vp.isNavMode() ? vp.getNavAngle() : 0;
        double pivotX = routeCanvas.getWidth() / 2;
        double pivotY = routeCanvas.getHeight() / 2;

        redrawRoutes(ox, oy, scale, navAngle, pivotX, pivotY);
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

        // 3. 节点锚点圆（所有模式都展示）
        {
            double[] buf = new double[2];
            routeGc.setFill(Color.WHITE);
            routeGc.setStroke(Color.BLUE);
            double r = RenderConfig.ROUTE_NODE_RADIUS;
            for (Point node : active.getNodes()) {
                CoordinateUtil.worldToScreenInto(buf, node.getX(), node.getY(), ox, oy, scale, navAngle, pivotX, pivotY);
                routeGc.fillOval(buf[0] - r, buf[1] - r, r * 2, r * 2);
                routeGc.strokeOval(buf[0] - r, buf[1] - r, r * 2, r * 2);
            }
        }

        // 4. 橡皮筋（仅绘制模式）
        if (pc.getCurrentMode() == PathContext.Mode.DRAWING && !active.getNodes().isEmpty()) {
            double[] buf = new double[2];
            Point lastNode = active.getNodes().getLast();
            CoordinateUtil.worldToScreenInto(buf, lastNode.getX(), lastNode.getY(), ox, oy, scale, navAngle, pivotX, pivotY);
            double p1x = buf[0], p1y = buf[1];
            CoordinateUtil.worldToScreenInto(buf, pc.getMouseLogicX(), pc.getMouseLogicY(), ox, oy, scale, navAngle, pivotX, pivotY);
            routeGc.setStroke(Color.web("#FFFFFF", 0.7));
            routeGc.setLineDashes(RenderConfig.ROUTE_DASH_LENGTH);
            routeGc.strokeLine(p1x, p1y, buf[0], buf[1]);
            routeGc.setLineDashes(null);
        }
    }

    /**
     * 以屏幕坐标绘制单条路径（含导航模式旋转补偿）
     */
    private static void renderPathScreen(GraphicsContext gc, List<Point> nodes,
                                          double ox, double oy, double scale,
                                          double navAngle, double pivotX, double pivotY) {
        if (nodes.size() < 2) return;
        double[] buf = new double[2];
        CoordinateUtil.worldToScreenInto(buf, nodes.getFirst().getX(), nodes.getFirst().getY(), ox, oy, scale, navAngle, pivotX, pivotY);
        gc.beginPath();
        gc.moveTo(buf[0], buf[1]);
        for (int i = 1; i < nodes.size(); i++) {
            CoordinateUtil.worldToScreenInto(buf, nodes.get(i).getX(), nodes.get(i).getY(), ox, oy, scale, navAngle, pivotX, pivotY);
            gc.lineTo(buf[0], buf[1]);
        }
        gc.stroke();
    }
}
