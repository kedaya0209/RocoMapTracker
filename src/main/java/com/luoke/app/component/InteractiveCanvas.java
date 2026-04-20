package com.luoke.app.component;

import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;

public class InteractiveCanvas extends Canvas {

    private Point2D lastMousePos;
    private boolean firstResize = true;

    public InteractiveCanvas() {
        setFocusTraversable(true);
        setPickOnBounds(true);
        setMouseTransparent(false);

        // 窗口大小监听 + 首次自动适配全图
        widthProperty().addListener(e -> {
            MapManager mm = MapManager.getInstance();
            mm.setViewWidth(getWidth());
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else {
                mm.ensureBounds();
            }
        });

        heightProperty().addListener(e -> {
            MapManager mm = MapManager.getInstance();
            mm.setViewHeight(getHeight());
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else {
                mm.ensureBounds();
            }
        });

        // 鼠标按下
        setOnMousePressed(e -> {
            lastMousePos = new Point2D(e.getX(), e.getY());
            e.consume();
        });

        // 鼠标拖动
        setOnMouseDragged(e -> {
            if (CameraManager.getInstance().isFollowMode()) return;
            if (lastMousePos == null) return;

            MapManager mm = MapManager.getInstance();
            double dx = e.getX() - lastMousePos.getX();
            double dy = e.getY() - lastMousePos.getY();

            mm.setOffsetX(mm.getOffsetX() + dx);
            mm.setOffsetY(mm.getOffsetY() + dy);
            mm.ensureBounds(); // 自动限制边界

            lastMousePos = new Point2D(e.getX(), e.getY());
            e.consume();
        });

        // 滚轮缩放
        setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
            CameraManager cam = CameraManager.getInstance();
            if (cam.isFollowMode()) {
                cam.setFollowScale(Math.clamp(cam.getFollowScale() * factor, 0.3, 5));
            } else {
                MapManager.getInstance().zoom(factor, e.getX(), e.getY());
            }
            e.consume();
        });
    }

    // 自动适配：显示整张地图
    private void autoFitMap() {
        MapManager mm = MapManager.getInstance();
        if (mm.getMapWidth() <= 0 || mm.getMapHeight() <= 0) return;

        double canvasW = getWidth();
        double canvasH = getHeight();
        double scaleW = canvasW / mm.getMapWidth();
        double scaleH = canvasH / mm.getMapHeight();
        double fitScale = Math.min(scaleW, scaleH);

        mm.setScale(fitScale);
        mm.ensureBounds(); // 自动居中+边界
    }
}