package io.github.kedaya0209.roco.app.ui.component.canvas;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.context.PathContext;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.map.model.Point;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
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
@NotThreadSafe
public class HoverManager {

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
     * @param logicX  预转换的地图逻辑 X 坐标（由 InteractiveCanvas 转换，含导航旋转补偿）
     * @param logicY  预转换的地图逻辑 Y 坐标
     * @param screenX 屏幕绝对 X（用于 Tooltip 定位，必须为屏幕坐标）
     * @param screenY 屏幕绝对 Y
     * @return true 如果 hoveredPoint 发生变化
     */
    public boolean onMouseMoved(double logicX, double logicY, double screenX, double screenY) {
        ResourcePoint point = findPointAt(logicX, logicY);
        if (point != hoveredPoint) {
            hoveredPoint = point;
            if (hoveredPoint != null) {
                owner.setCursor(Cursor.HAND);
                String prefix = (pathContext.getCurrentMode() == PathContext.Mode.DRAWING) ? "吸附: " : "";
                tooltip.setText(prefix + hoveredPoint.getConfig().getMarkTypeName());
                tooltip.show(owner, screenX + 10, screenY + 10);
            } else {
                owner.setCursor(pathContext.getCurrentMode() != PathContext.Mode.VIEW ? Cursor.CROSSHAIR : Cursor.DEFAULT);
                tooltip.hide();
            }
            return true;
        } else if (hoveredPoint != null) {
            tooltip.setAnchorX(screenX + 10);
            tooltip.setAnchorY(screenY + 10);
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
     * 在指定逻辑坐标附近查找资源点。
     *
     * @param logicX 地图逻辑 X（已由 InteractiveCanvas 转换）
     * @param logicY 地图逻辑 Y
     */
    private ResourcePoint findPointAt(double logicX, double logicY) {
        List<ResourcePoint> nearbyPoints = pointContext.getNearbyResources(logicX, logicY);
        if (nearbyPoints.isEmpty()) return null;
        for (int i = nearbyPoints.size() - 1; i >= 0; i--) {
            ResourcePoint p = nearbyPoints.get(i);
            Point pos = p.getScreenPosition();
            double r = UiConfig.HOVER_DETECT_RADIUS;
            if (logicX >= pos.getX() - r && logicX <= pos.getX() + r
                    && logicY >= pos.getY() - r * 2 && logicY <= pos.getY()) {
                return p;
            }
        }
        return null;
    }
}
