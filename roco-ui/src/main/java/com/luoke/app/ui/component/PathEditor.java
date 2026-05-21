package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.PathContext;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.map.model.RoutePath;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 路径编辑器 — 从 InteractiveCanvas 拆分，
 * 负责路径绘图、编辑模式下的节点增删拖拽和键盘快捷键。
 */
@NotThreadSafe
@Slf4j
public class PathEditor {

    private final PathContext pathContext = PathContext.getInstance();
    private final MapContext mapContext = MapContext.getInstance();

    private int draggedNodeIndex = -1;

    /**
     * 鼠标按下：编辑模式下检测节点拖拽
     */
    public void onMousePressed(double mx, double my) {
        if (pathContext.getCurrentMode() == PathContext.Mode.EDITING) {
            draggedNodeIndex = findNodeIndexAt(mx, my);
        }
    }

    /**
     * 鼠标单击：绘图/编辑模式下添加或插入节点
     *
     * @param snapTarget 吸附到的资源点（可能为 null）
     * @param markDirty  标记重绘回调
     * @return true 如果事件已处理（已消费）
     */
    public boolean onMouseClicked(double mx, double my, ResourcePoint snapTarget, Runnable markDirty) {
        if (pathContext.getCurrentMode() == PathContext.Mode.VIEW) {
            return false;
        }

        double lx = toLogicX(mx);
        double ly = toLogicY(my);

        if (pathContext.getCurrentMode() == PathContext.Mode.DRAWING) {
            double finalX = (snapTarget != null) ? snapTarget.getScreenPosition().getX() : lx;
            double finalY = (snapTarget != null) ? snapTarget.getScreenPosition().getY() : ly;
            pathContext.getActiveRoute().addNode(new Point(finalX, finalY));
            if (markDirty != null) markDirty.run();
            return true;
        }

        if (pathContext.getCurrentMode() == PathContext.Mode.EDITING) {
            if (draggedNodeIndex == -1) {
                int insertIndex = findInsertPosition(mx, my);
                if (insertIndex != -1) {
                    pathContext.getActiveRoute().addNode(insertIndex, new Point(lx, ly));
                } else {
                    pathContext.getActiveRoute().addNode(new Point(lx, ly));
                }
            }
            if (markDirty != null) markDirty.run();
            return true;
        }

        return false;
    }

    /**
     * 右键单击：编辑/绘图模式下删除节点
     *
     * @return true 如果删除了节点
     */
    public boolean onSecondaryClicked(double mx, double my, Runnable markDirty) {
        if (pathContext.getCurrentMode() != PathContext.Mode.VIEW) {
            int nodeIdx = findNodeIndexAt(mx, my);
            if (nodeIdx != -1) {
                pathContext.getActiveRoute().remove(nodeIdx);
                if (markDirty != null) markDirty.run();
                return true;
            }
        }
        return false;
    }

    /**
     * 鼠标拖拽：编辑模式下拖拽节点
     */
    public void onMouseDragged(double mx, double my, ResourcePoint snapTarget, Runnable markDirty) {
        if (draggedNodeIndex != -1 && pathContext.getCurrentMode() == PathContext.Mode.EDITING) {
            double rawLx = toLogicX(mx);
            double rawLy = toLogicY(my);

            if (snapTarget != null) {
                Point snapPos = snapTarget.getScreenPosition();
                pathContext.getActiveRoute().setNode(draggedNodeIndex, new Point(snapPos.getX(), snapPos.getY()));
            } else {
                pathContext.getActiveRoute().setNode(draggedNodeIndex, new Point(rawLx, rawLy));
            }
            if (markDirty != null) markDirty.run();
        }
    }

    /**
     * 鼠标释放：结束节点拖拽
     */
    public void onMouseReleased() {
        draggedNodeIndex = -1;
    }

    /**
     * Ctrl+Z 撤销：删除最后一个节点
     *
     * @return true 如果执行了撤销
     */
    public boolean undoLastNode(Runnable markDirty) {
        if (pathContext.getCurrentMode() != PathContext.Mode.VIEW) {
            RoutePath active = pathContext.getActiveRoute();
            if (active != null && !active.getNodes().isEmpty()) {
                active.remove(active.getNodes().size() - 1);
                if (markDirty != null) markDirty.run();
                return true;
            }
        }
        return false;
    }

    /**
     * ESC 退出编辑/绘图模式
     */
    public void exitEditingMode(Runnable markDirty) {
        pathContext.setActiveRoute(null);
        pathContext.setCurrentMode(PathContext.Mode.VIEW);
        if (markDirty != null) markDirty.run();
        log.info("ESC 已成功触发一键退出并清理数据");
    }

    public boolean isDragging() {
        return draggedNodeIndex != -1;
    }

    // ================================================================
    // 内部工具方法
    // ================================================================

    private int findNodeIndexAt(double mx, double my) {
        RoutePath active = pathContext.getActiveRoute();
        if (active == null) return -1;
        double lx = toLogicX(mx);
        double ly = toLogicY(my);
        double threshold = ViewConfig.NODE_CLICK_THRESHOLD / mapContext.getScale();
        List<Point> nodes = active.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).distance(lx, ly) < threshold) return i;
        }
        return -1;
    }

    private int findInsertPosition(double mx, double my) {
        RoutePath active = pathContext.getActiveRoute();
        if (active == null || active.getNodes().size() < 2) return -1;
        double lx = toLogicX(mx);
        double ly = toLogicY(my);
        double threshold = ViewConfig.NODE_INSERT_THRESHOLD / mapContext.getScale();
        List<Point> nodes = active.getNodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            if (distancePointToSegment(lx, ly,
                    nodes.get(i).getX(), nodes.get(i).getY(),
                    nodes.get(i + 1).getX(), nodes.get(i + 1).getY()) < threshold) {
                return i + 1;
            }
        }
        return -1;
    }

    private double distancePointToSegment(double px, double py,
                                          double x1, double y1,
                                          double x2, double y2) {
        double l2 = Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2);
        if (l2 == 0) return Math.sqrt(Math.pow(px - x1, 2) + Math.pow(py - y1, 2));
        double t = Math.clamp(((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2, 0, 1);
        return Math.sqrt(Math.pow(px - (x1 + t * (x2 - x1)), 2)
                + Math.pow(py - (y1 + t * (y2 - y1)), 2));
    }

    private double toLogicX(double canvasX) {
        return (canvasX - mapContext.getOffsetX()) / mapContext.getScale();
    }

    private double toLogicY(double canvasY) {
        return (canvasY - mapContext.getOffsetY()) / mapContext.getScale();
    }
}
