package com.luoke.app.map.model;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import lombok.Data;

/**
 * 地图资源点位 - 包含 Hover 高亮与渲染逻辑
 */
@Data
public class ResourcePoint {

    private final ResourceConfig config;
    private final Point2D screenPosition;
    private boolean grayed;

    // 【新增】Hover 状态
    private boolean hovered;

    public ResourcePoint(ResourceConfig config, Point2D screenPosition) {
        this.config = config;
        this.screenPosition = screenPosition;
    }

    /**
     * 渲染方法：负责处理 正常/置灰/Hover 状态的显示
     */
    public void render(GraphicsContext gc, Image icon) {
        if (icon == null || icon.isError()) return;

        double x = screenPosition.getX();
        double y = screenPosition.getY();
        double w = icon.getWidth();
        double h = icon.getHeight();

        double drawX = x - w / 2;
        double drawY = y - h;

        gc.save();

        if (hovered) {
            gc.setStroke(Color.web("#00BFFF"));
            gc.setLineWidth(3.0);
            gc.strokeOval(drawX - 2, drawY - 2, w + 4, h + 4);
            gc.setGlobalAlpha(0.3);
            gc.setFill(Color.web("#00BFFF"));
            gc.fillOval(drawX - 4, drawY - 4, w + 8, h + 8);
            gc.setGlobalAlpha(1.0);
        }

        if (grayed) {
            gc.setGlobalAlpha(0.4);
        }

        gc.drawImage(icon, drawX, drawY);

        gc.restore();
    }

    /**
     * 用于预渲染缓存的绘制（不含 hover 效果）
     */
    public void renderForCache(GraphicsContext gc, Image icon) {
        if (icon == null || icon.isError()) return;

        double x = screenPosition.getX();
        double y = screenPosition.getY();
        double w = icon.getWidth();
        double h = icon.getHeight();
        double drawX = x - w / 2;
        double drawY = y - h;

        gc.save();
        if (grayed) {
            gc.setGlobalAlpha(0.4);
        }
        gc.drawImage(icon, drawX, drawY);
        gc.restore();
    }

    /**
     * 在缓存层之上绘制 hover 效果：蓝色描边圆环，置灰时额外覆盖全色图标
     */
    public void renderHoverOverlay(GraphicsContext gc, Image icon) {
        if (icon == null || icon.isError()) return;

        double x = screenPosition.getX();
        double y = screenPosition.getY();
        double w = icon.getWidth();
        double h = icon.getHeight();
        double drawX = x - w / 2;
        double drawY = y - h;

        gc.save();
        gc.setStroke(Color.web("#00BFFF"));
        gc.setLineWidth(2.0);
        gc.strokeOval(drawX - 1, drawY - 1, w + 2, h + 2);
        if (grayed) {
            gc.drawImage(icon, drawX, drawY);
        }
        gc.restore();
    }
}