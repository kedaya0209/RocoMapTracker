package com.luoke.app.component;

import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import javafx.animation.AnimationTimer;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.paint.Color;

public class InteractiveCanvas extends Canvas {
    private Point2D lastMousePos;

    public InteractiveCanvas() {
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                CameraManager.getInstance().updateViewport();
                render();
            }
        }.start();

        // 窗口尺寸同步
        widthProperty().addListener(e -> MapManager.getInstance().setViewWidth(getWidth()));
        heightProperty().addListener(e -> MapManager.getInstance().setViewHeight(getHeight()));

        // 拖拽
        this.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> lastMousePos = new Point2D(e.getX(), e.getY()));
        this.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (CameraManager.getInstance().isFollowMode()) return;
            MapManager mm = MapManager.getInstance();
            if (lastMousePos != null) {
                mm.setOffsetX(mm.getOffsetX() + (e.getX() - lastMousePos.getX()));
                mm.setOffsetY(mm.getOffsetY() + (e.getY() - lastMousePos.getY()));
                mm.ensureBounds();
                lastMousePos = new Point2D(e.getX(), e.getY());
            }
        });

        // 滚轮缩放：支持两种模式
        this.addEventHandler(ScrollEvent.SCROLL, e -> {
            double factor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
            CameraManager cam = CameraManager.getInstance();
            if (cam.isFollowMode()) {
                // 跟随模式下：调整视野广度 (Scale越小视野越广)
                double newFollowScale = cam.getFollowScale() * factor;
                cam.setFollowScale(Math.max(0.4, Math.min(newFollowScale, 5.0)));
            } else {
                // 手动模式下：正常地图缩放
                MapManager.getInstance().zoom(factor, e.getX(), e.getY());
            }
        });
    }

    private void render() {
        GraphicsContext gc = getGraphicsContext2D();
        MapManager mm = MapManager.getInstance();
        gc.clearRect(0, 0, getWidth(), getHeight());
        if (mm.getMapImage() == null) return;

        gc.save();
        gc.translate(mm.getOffsetX(), mm.getOffsetY());
        gc.scale(mm.getScale(), mm.getScale());
        gc.drawImage(mm.getMapImage(), 0, 0);
        gc.restore();

        // 绘制玩家红点
        gc.setFill(Color.RED);
        gc.setStroke(Color.WHITE);
        gc.setLineWidth(2);
        double px = mm.getPlayerCanvasX();
        double py = mm.getPlayerCanvasY();
        gc.fillOval(px - 6, py - 6, 12, 12);
        gc.strokeOval(px - 6, py - 6, 12, 12);
    }
}