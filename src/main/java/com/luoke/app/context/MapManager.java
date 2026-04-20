package com.luoke.app.context;

import javafx.scene.image.Image;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MapManager {
    private Image mapImage;
    private double mapWidth, mapHeight;
    private double scale = 1.0, offsetX = 0, offsetY = 0;
    private double viewWidth, viewHeight;

    private double playerX = -1, playerY = -1;
    private double playerAngle = 0;

    private double trimOffsetX = 0, trimOffsetY = 0;

    private MapManager() {}
    public static MapManager getInstance() { return Holder.INSTANCE; }

    public void init(Image image, double w, double h, double tX, double tY) {
        this.mapImage = image;
        this.mapWidth = image.getWidth();
        this.mapHeight = image.getHeight();
        this.viewWidth = w;
        this.viewHeight = h;
        this.trimOffsetX = tX;
        this.trimOffsetY = tY;
    }

    // ==============================================
    // ✅【修复】玩家坐标必须加上 trim 偏移！！！
    // ==============================================
    public void updatePlayerState(double x, double y, double visualAngle) {
        this.playerX = x + trimOffsetX;   // 这里！！！
        this.playerY = y + trimOffsetY;   // 这里！！！
        this.playerAngle = visualAngle;
    }

    public double getPlayerCanvasX() {
        return offsetX + playerX * scale;
    }

    public double getPlayerCanvasY() {
        return offsetY + playerY * scale;
    }

    public void zoom(double factor, double mx, double my) {
        double minScale = Math.max(viewWidth / mapWidth, viewHeight / mapHeight);
        double newScale = Math.max(minScale, Math.min(scale * factor, 15));
        double f = newScale / scale;
        offsetX = mx - (mx - offsetX) * f;
        offsetY = my - (my - offsetY) * f;
        scale = newScale;
        ensureBounds();
    }

    public void ensureBounds() {
        if (mapImage == null) return;
        double w = mapWidth * scale, h = mapHeight * scale;
        offsetX = (w >= viewWidth) ? Math.min(0, Math.max(offsetX, viewWidth - w)) : (viewWidth - w) / 2;
        offsetY = (h >= viewHeight) ? Math.min(0, Math.max(offsetY, viewHeight - h)) : (viewHeight - h) / 2;
    }

    private static class Holder {
        private static final MapManager INSTANCE = new MapManager();
    }
}