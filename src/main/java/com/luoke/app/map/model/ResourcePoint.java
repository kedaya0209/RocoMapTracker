package com.luoke.app.map.model;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import lombok.Data;

/**
 * 地图资源点位 - 包含 Hover 高亮与渲染逻辑
 */
@Data
public class ResourcePoint {
    private static final ColorAdjust GRAY_EFFECT;
    static {
        GRAY_EFFECT = new ColorAdjust();
        GRAY_EFFECT.setSaturation(-1.0);
    }

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

        // 底部中心对齐计算
        double drawX = x - w / 2;
        double drawY = y - h;

        gc.save();

        // 1. 如果处于 Hover 状态，先画一个高亮背光或边框
        if (hovered) {
            // 设置高亮颜色（统一蓝色）
            gc.setStroke(Color.web("#00BFFF"));
            gc.setLineWidth(3.0);
            // 画一个比图标稍微大一点的圆圈或矩形
            gc.strokeOval(drawX - 2, drawY - 2, w + 4, h + 4);

            // 可选：增加一点外发光效果
            gc.setGlobalAlpha(0.3);
            gc.setFill(Color.web("#00BFFF"));
            gc.fillOval(drawX - 4, drawY - 4, w + 8, h + 8);
            gc.setGlobalAlpha(1.0);
        }

        // 2. 处理置灰逻辑
        if (grayed) {
            gc.setGlobalAlpha(0.4);
            gc.setEffect(GRAY_EFFECT);
        }

        // 3. 绘制图标主体
        gc.drawImage(icon, drawX, drawY);

        gc.restore();
    }
}