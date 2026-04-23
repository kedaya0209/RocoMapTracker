package com.luoke.app.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraManager;
import com.luoke.app.context.MapManager;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.map.loader.ImageLoader;
import com.luoke.app.map.model.ResourcePoint;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;

public class InteractiveCanvas extends Canvas {

    private final MapManager mapManager = MapManager.getInstance();
    private boolean firstResize = true;
    private final CameraManager cameraManager = CameraManager.getInstance();
    private final ResourcePointContext pointContext = ResourcePointContext.getInstance();
    private final ImageLoader imageLoader = ImageLoader.getInstance();
    private double lastMouseX, lastMouseY;

    public InteractiveCanvas() {
        setFocusTraversable(true);
        setPickOnBounds(true);
        setMouseTransparent(false);

        widthProperty().addListener(e -> {
            mapManager.setViewWidth(getWidth());
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else {
                mapManager.ensureBounds();
            }
        });

        heightProperty().addListener(e -> {
            mapManager.setViewHeight(getHeight());
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else {
                mapManager.ensureBounds();
            }
        });

        setOnMousePressed(e -> {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            e.consume();
        });

        setOnMouseDragged(e -> {
            if (cameraManager.isFollowMode()) return;

            double dx = e.getX() - lastMouseX;
            double dy = e.getY() - lastMouseY;

            mapManager.setOffsetX(mapManager.getOffsetX() + dx);
            mapManager.setOffsetY(mapManager.getOffsetY() + dy);
            mapManager.ensureBounds();

            lastMouseX = e.getX();
            lastMouseY = e.getY();
            e.consume();
        });

        setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
            if (cameraManager.isFollowMode()) {
                cameraManager.setFollowScale(Math.clamp(cameraManager.getFollowScale() * factor, 0.3, 5));
            } else {
                mapManager.zoom(factor, e.getX(), e.getY());
            }
            e.consume();
        });
    }

    public void drawAllResourceIcons(GraphicsContext gc) {
        if (pointContext.getAllPoints().isEmpty()) return;

        gc.save();
        gc.translate(mapManager.getOffsetX(), mapManager.getOffsetY());
        gc.scale(mapManager.getScale(), mapManager.getScale());

        for (ResourcePoint point : pointContext.getAllPoints()) {
            String iconPath = point.getConfig().getIcon();
            if (iconPath == null || iconPath.isBlank()) continue;
            Image icon = imageLoader.loadScaledIcon(AppConfig.ICON_DIR + iconPath);
            point.render(gc, icon);
        }

        gc.restore();
    }

    private void autoFitMap() {
        if (mapManager.getMapWidth() <= 0 || mapManager.getMapHeight() <= 0) return;
        double scale = Math.min(getWidth() / mapManager.getMapWidth(), getHeight() / mapManager.getMapHeight());
        mapManager.setScale(scale);
        mapManager.ensureBounds();
    }
}