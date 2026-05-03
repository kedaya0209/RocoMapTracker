package com.luoke.app.ui.render;

import com.luoke.app.context.MapContext;
import com.luoke.app.context.PathContext;
import com.luoke.app.map.model.RoutePath;
import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;

import java.util.List;

public class PathRenderer {

    public static void draw(GraphicsContext gc) {
        PathContext pc = PathContext.getInstance();
        RoutePath active = pc.getActiveRoute();
        if (active == null) return;

        double scale = MapContext.getInstance().getScale();

        // 1. 绘制所有背景路线（置灰/半透明）
        gc.setLineWidth(2.0 / scale);
        gc.setStroke(Color.web("#888888", 0.6));
        for (RoutePath path : pc.getSavedRoutes()) {
            if (path == pc.getActiveRoute()) continue;
            renderSinglePath(gc, path.getNodes(), false);
        }

        // 2. 绘制活跃路线数据
        // 无论什么模式，只要 active 不为空，就画出这条绿线
        gc.setStroke(Color.CHARTREUSE);
        gc.setLineWidth(3.0 / scale);
        renderSinglePath(gc, active.getNodes(), true);

        // --- 以下 UI 只在非 VIEW 模式（绘图或编辑）下显示 ---
        if (pc.getCurrentMode() != PathContext.Mode.VIEW) {
            // 画预览虚线（橡皮筋）
            if (pc.getCurrentMode() == PathContext.Mode.DRAWING && !active.getNodes().isEmpty()) {
                Point2D lastNode = active.getNodes().get(active.getNodes().size() - 1);
                gc.setStroke(Color.web("#FFFFFF", 0.7));
                gc.setLineDashes(5.0 / scale);
                gc.strokeLine(lastNode.getX(), lastNode.getY(), pc.getMouseLogicX(), pc.getMouseLogicY());
                gc.setLineDashes(null);
            }
        }
        // 画节点锚点（小圆点）
        gc.setFill(Color.WHITE);
        gc.setStroke(Color.BLUE);
        double r = 4.5 / scale;
        for (Point2D node : active.getNodes()) {
            gc.fillOval(node.getX() - r, node.getY() - r, r * 2, r * 2);
            gc.strokeOval(node.getX() - r, node.getY() - r, r * 2, r * 2);
        }
    }

    private static void renderSinglePath(GraphicsContext gc, List<Point2D> nodes, boolean isActive) {
        if (nodes.size() < 2) return;
        gc.beginPath();
        gc.moveTo(nodes.get(0).getX(), nodes.get(0).getY());
        for (int i = 1; i < nodes.size(); i++) {
            gc.lineTo(nodes.get(i).getX(), nodes.get(i).getY());
        }
        gc.stroke();
    }
}