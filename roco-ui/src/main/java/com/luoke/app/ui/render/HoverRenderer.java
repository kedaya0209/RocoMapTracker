package com.luoke.app.ui.render;

import com.luoke.app.config.PathConfig;
import com.luoke.app.config.RenderConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.ui.service.IconCache;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import lombok.Getter;

/**
 * Hover 渲染器 — Canvas 屏幕坐标绘制高亮光环 + 图标。
 * <p>
 * 通过 setHoveredPoint() 设置目标点，下一帧自动绘制。
 */
public class HoverRenderer implements RenderLayer {

    private final Canvas hoverCanvas;
    private final GraphicsContext hoverGc;
    private ResourcePoint hoveredPoint;
    @Getter
    private ResourcePoint lastHoveredPoint;
    private boolean hoverDirty;

    public HoverRenderer(Pane parent) {
        hoverCanvas = new Canvas();
        hoverCanvas.setMouseTransparent(true);
        hoverCanvas.setPickOnBounds(false);
        hoverCanvas.widthProperty().bind(parent.widthProperty());
        hoverCanvas.heightProperty().bind(parent.heightProperty());
        hoverGc = hoverCanvas.getGraphicsContext2D();
    }

    @Override
    public Node getNode() {
        return hoverCanvas;
    }

    /**
     * 设置 hover 目标点，标记本层脏，下一帧重绘。
     */
    public void setHoveredPoint(ResourcePoint p) {
        if (this.hoveredPoint == p) return;
        this.lastHoveredPoint = this.hoveredPoint;
        this.hoveredPoint = p;
        hoverDirty = true;
    }

    @Override
    public void onFrame() {
        if (hoverDirty) {
            MapContext mm = MapContext.getInstance();
            redrawHover(mm.getOffsetX(), mm.getOffsetY(), mm.getScale());
            hoverDirty = false;
        }
    }

    /**
     * hover 光环绘制 — 世界坐标转屏幕坐标
     */
    private void redrawHover(double ox, double oy, double scale) {
        double w = hoverCanvas.getWidth();
        double h = hoverCanvas.getHeight();
        if (w <= 0 || h <= 0) return;

        hoverGc.clearRect(0, 0, w, h);
        if (hoveredPoint == null) return;

        Point pos = hoveredPoint.getScreenPosition();
        double sx = pos.getX() * scale + ox;
        double sy = pos.getY() * scale + oy;

        // 光环
        double hoverSize = RenderConfig.HOVER_ICON_SIZE;
        hoverGc.setFill(Color.web(RenderConfig.HOVER_GLOW_COLOR, 0.2));
        hoverGc.fillOval(sx - hoverSize / 2 - 4, sy - hoverSize / 2 - 4, hoverSize + 8, hoverSize + 8);
        hoverGc.setStroke(Color.web(RenderConfig.HOVER_GLOW_COLOR, 0.8));
        hoverGc.setLineWidth(2);
        hoverGc.strokeOval(sx - hoverSize / 2 - 2, sy - hoverSize / 2 - 2, hoverSize + 4, hoverSize + 4);

        // 图标
        String iconFile = hoveredPoint.getConfig().getIcon();
        if (iconFile == null || iconFile.isEmpty()) return;
        IconCache cache = IconCache.getInstance();
        String iconPath = PathConfig.ICON_DIR + iconFile;
        if (cache.isAtlasReady()) {
            IconCache.AtlasSlot slot = cache.getSlot(iconPath);
            if (slot != null) {
                hoverGc.drawImage(cache.getColorAtlas(), slot.sx, slot.sy, RenderConfig.ICON_SIZE, RenderConfig.ICON_SIZE,
                        sx - hoverSize / 2, sy - hoverSize / 2, hoverSize, hoverSize);
                return;
            }
        }
        Image icon = cache.getIcon(iconPath);
        if (icon != null) {
            hoverGc.drawImage(icon, sx - hoverSize / 2, sy - hoverSize / 2, hoverSize, hoverSize);
        }
    }
}
