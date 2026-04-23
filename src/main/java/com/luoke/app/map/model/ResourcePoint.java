package com.luoke.app.map.model;

import javafx.geometry.Point2D;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import lombok.Data;

@Data
public class ResourcePoint {
    // 全局公用1个置灰效果（全局复用，不重复new）
    private static final ColorAdjust GRAY_EFFECT;

    static {
        GRAY_EFFECT = new ColorAdjust();
        GRAY_EFFECT.setSaturation(-1.0); // 饱和度拉到最低 = 完全灰
    }

    private final ResourceConfig config;
    private final Point2D screenPosition;
    private boolean grayed;

    public ResourcePoint(ResourceConfig config, Point2D screenPosition) {
        this.config = config;
        this.screenPosition = screenPosition;
    }

    // ====================== 全版本兼容 安全绘制 ======================
    public void render(GraphicsContext gc, Image icon) {
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
            gc.setEffect(GRAY_EFFECT); // 直接用实例
        }

        gc.drawImage(icon, drawX, drawY);
        gc.restore();
    }
}