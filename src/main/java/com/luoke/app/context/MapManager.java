package com.luoke.app.context;

import javafx.scene.image.Image;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MapManager {
    private Image mapImage;
    private double scale = 1.0, offsetX = 0, offsetY = 0;
    private double viewWidth, viewHeight;
    private double playerX = 0, playerY = 0;
    // 地图原始图片的裁剪偏移
    private double trimOffsetX = 0;
    private double trimOffsetY = 0;

    private MapManager() {
    }

    public static MapManager getInstance() {
        return Holder.INSTANCE;
    }

    public void init(Image image, double w, double h, double tX, double tY) {
        this.mapImage = image;
        this.viewWidth = w;
        this.viewHeight = h;
        this.trimOffsetX = tX;
        this.trimOffsetY = tY;
    }

    public void zoom(double factor, double mx, double my) {
        if (CameraManager.getInstance().isFollowMode()) return;
        double minScale = Math.max(viewWidth / mapImage.getWidth(), viewHeight / mapImage.getHeight());
        double newScale = Math.max(minScale, Math.min(scale * factor, 15.0));
        double actualFactor = newScale / scale;
        offsetX = mx - (mx - offsetX) * actualFactor;
        offsetY = my - (my - offsetY) * actualFactor;
        scale = newScale;
        ensureBounds();
    }

    public void ensureBounds() {
        if (mapImage == null) return;
        double mw = mapImage.getWidth() * scale;
        double mh = mapImage.getHeight() * scale;
        if (mw >= viewWidth) offsetX = Math.min(0, Math.max(offsetX, viewWidth - mw));
        else offsetX = (viewWidth - mw) / 2.0;
        if (mh >= viewHeight) offsetY = Math.min(0, Math.max(offsetY, viewHeight - mh));
        else offsetY = (viewHeight - mh) / 2.0;
    }

    public double getPlayerCanvasX() {
        return offsetX + (playerX * scale);
    }

    public double getPlayerCanvasY() {
        return offsetY + (playerY * scale);
    }

    private static class Holder {
        private static final MapManager INSTANCE = new MapManager();
    }
}