package com.luoke.app.ui.component;

import com.luoke.app.config.UiConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.PathContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import lombok.Getter;

import java.util.List;

/**
 * Hover 检测与 Tooltip 管理 — 从 InteractiveCanvas 拆分，
 * 负责资源点悬停检测、光标切换和提示显示。
 */
public class HoverManager {

    private final MapContext mapContext = MapContext.getInstance();
    private final PathContext pathContext = PathContext.getInstance();
    private final ResourcePointContext pointContext = ResourcePointContext.getInstance();
    private final Node owner;
    private final Tooltip tooltip = new Tooltip();

    @Getter
    private ResourcePoint hoveredPoint;

    public HoverManager(Node owner) {
        this.owner = owner;
        tooltip.setShowDelay(Duration.ZERO);
        tooltip.setHideDelay(Duration.ZERO);
        tooltip.setStyle("-fx-background-color: rgba(35,35,35,0.9); -fx-text-fill: white; -fx-padding: 6px; -fx-border-color: #00BFFF; -fx-border-radius: 4; -fx-background-radius: 4;");
    }

    /**
     * 鼠标移动时更新 hover 状态。
     *
     * @return true 如果 hoveredPoint 发生变化
     */
    public boolean onMouseMoved(double mx, double my, double sceneX, double sceneY) {
        ResourcePoint point = findPointAt(mx, my);
        if (point != hoveredPoint) {
            hoveredPoint = point;
            if (hoveredPoint != null) {
                owner.setCursor(Cursor.HAND);
                String prefix = (pathContext.getCurrentMode() == PathContext.Mode.DRAWING) ? "吸附: " : "";
                tooltip.setText(prefix + hoveredPoint.getConfig().getMarkTypeName());
                tooltip.show(owner, sceneX + 10, sceneY + 10);
            } else {
                owner.setCursor(pathContext.getCurrentMode() != PathContext.Mode.VIEW ? Cursor.CROSSHAIR : Cursor.DEFAULT);
                tooltip.hide();
            }
            return true;
        } else if (hoveredPoint != null) {
            tooltip.setAnchorX(sceneX + 10);
            tooltip.setAnchorY(sceneY + 10);
        }
        return false;
    }

    /**
     * 鼠标离开画布时清理 hover 状态
     */
    public void onMouseExited() {
        tooltip.hide();
        if (hoveredPoint != null) {
            hoveredPoint = null;
        }
        owner.setCursor(Cursor.DEFAULT);
    }

    /**
     * 清除 hoveredPoint（拖拽等场景下使用）
     */
    public void clearHover() {
        hoveredPoint = null;
    }

    /**
     * 在指定屏幕坐标附近查找资源点
     */
    private ResourcePoint findPointAt(double mx, double my) {
        double lx = toLogicX(mx);
        double ly = toLogicY(my);
        List<ResourcePoint> nearbyPoints = pointContext.getNearbyResources(lx, ly);
        if (nearbyPoints.isEmpty()) return null;
        for (int i = nearbyPoints.size() - 1; i >= 0; i--) {
            ResourcePoint p = nearbyPoints.get(i);
            Point pos = p.getScreenPosition();
            double r = UiConfig.HOVER_DETECT_RADIUS;
            if (lx >= pos.getX() - r && lx <= pos.getX() + r
                    && ly >= pos.getY() - r * 2 && ly <= pos.getY()) {
                return p;
            }
        }
        return null;
    }

    private double toLogicX(double canvasX) {
        return (canvasX - mapContext.getOffsetX()) / mapContext.getScale();
    }

    private double toLogicY(double canvasY) {
        return (canvasY - mapContext.getOffsetY()) / mapContext.getScale();
    }
}
