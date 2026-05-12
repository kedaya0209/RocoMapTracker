package com.luoke.app.ui.render;

import com.luoke.app.map.model.Point;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

/**
 * 资源点位渲染工具 — 将图标绘制到 JavaFX GraphicsContext 上。
 */
public class ResourcePointRenderer {

    private static final double ICON_SIZE = 32;

    private ResourcePointRenderer() {
    }

    /** 绘制普通图标（居中于屏幕坐标位置） */
    public static void renderForCache(GraphicsContext gc, Image icon, Point screenPos) {
        if (icon == null) return;
        double half = ICON_SIZE / 2.0;
        gc.drawImage(icon, screenPos.getX() - half, screenPos.getY() - half,
                ICON_SIZE, ICON_SIZE);
    }

    /** 绘制 hover 高亮效果（放大 + 外发光边框） */
    public static void renderHoverOverlay(GraphicsContext gc, Image icon, Point screenPos) {
        if (icon == null) return;
        double hoverSize = 38;
        double half = hoverSize / 2.0;
        double x = screenPos.getX() - half;
        double y = screenPos.getY() - half;

        gc.setFill(javafx.scene.paint.Color.web("#00BFFF", 0.2));
        gc.fillOval(x - 4, y - 4, hoverSize + 8, hoverSize + 8);

        gc.setStroke(javafx.scene.paint.Color.web("#00BFFF", 0.8));
        gc.setLineWidth(2);
        gc.strokeOval(x - 2, y - 2, hoverSize + 4, hoverSize + 4);

        gc.drawImage(icon, x, y, hoverSize, hoverSize);
    }
}
