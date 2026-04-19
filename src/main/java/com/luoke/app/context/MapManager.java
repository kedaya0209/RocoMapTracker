package com.luoke.app.context;

import javafx.scene.image.Image;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;

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

    /**
     * 更新玩家状态并修复朝向
     * @param x 当前地图 X 坐标
     * @param y 当前地图 Y 坐标
     * @param visualAngle 视觉算法识别出的角度
     */

    public void updatePlayerState(double x, double y, double visualAngle) {
        // 1. 全局坐标正常更新（用于大地图渲染位置）
        this.playerX = x;
        this.playerY = y;
    }

    // 关键：这里计算的是玩家在 Canvas 上的绝对像素位置
    public double getPlayerCanvasX() { return offsetX + (playerX * scale); }
    public double getPlayerCanvasY() { return offsetY + (playerY * scale); }

    public void zoom(double factor, double mx, double my) {
        double minScale = Math.max(viewWidth / mapWidth, viewHeight / mapHeight);
        double newScale = Math.max(minScale, Math.min(scale * factor, 15.0));
        double actualFactor = newScale / scale;
        offsetX = mx - (mx - offsetX) * actualFactor;
        offsetY = my - (my - offsetY) * actualFactor;
        scale = newScale;
        ensureBounds();
    }

    public void ensureBounds() {
        if (mapImage == null) return;
        double mw = mapWidth * scale, mh = mapHeight * scale;
        if (mw >= viewWidth) offsetX = Math.min(0, Math.max(offsetX, viewWidth - mw));
        else offsetX = (viewWidth - mw) / 2.0;
        if (mh >= viewHeight) offsetY = Math.min(0, Math.max(offsetY, viewHeight - mh));
        else offsetY = (viewHeight - mh) / 2.0;
    }

    private static class Holder { private static final MapManager INSTANCE = new MapManager(); }
}