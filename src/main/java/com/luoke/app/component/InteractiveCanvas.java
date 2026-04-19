package com.luoke.app.component;

import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import javafx.animation.AnimationTimer;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

public class InteractiveCanvas extends Canvas {
    private Point2D lastMousePos;

    public InteractiveCanvas() {
        // 60FPS 动画循环
        new AnimationTimer() {
            @Override
            public void handle(long now) {
                CameraManager.getInstance().updateViewport();
                render();
            }
        }.start();

        // 尺寸监听
        widthProperty().addListener(e -> MapManager.getInstance().setViewWidth(getWidth()));
        heightProperty().addListener(e -> MapManager.getInstance().setViewHeight(getHeight()));

        // 拖拽逻辑
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

        // 缩放逻辑
        this.addEventHandler(ScrollEvent.SCROLL, e -> {
            double factor = (e.getDeltaY() > 0) ? 1.1 : 0.9;
            CameraManager cam = CameraManager.getInstance();
            if (cam.isFollowMode()) {
                double newFollowScale = cam.getFollowScale() * factor;
                cam.setFollowScale(Math.max(0.3, Math.min(newFollowScale, 5.0)));
            } else {
                MapManager.getInstance().zoom(factor, e.getX(), e.getY());
            }
        });
    }

    private void render() {
        GraphicsContext gc = getGraphicsContext2D();
        MapManager mm = MapManager.getInstance();
        gc.clearRect(0, 0, getWidth(), getHeight());
        if (mm.getMapImage() == null) return;

        // 1. 绘制地图
        gc.save();
        gc.translate(mm.getOffsetX(), mm.getOffsetY());
        gc.scale(mm.getScale(), mm.getScale());
        gc.drawImage(mm.getMapImage(), 0, 0);
        gc.restore();

        // 2. 绘制玩家 (通过渲染器，自动处理旋转和图标，不再是红点)
        PlayerRenderer.getInstance().draw(gc);
    }
}