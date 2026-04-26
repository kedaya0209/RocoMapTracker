package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.map.loader.ImageLoader;
import com.luoke.app.map.model.ResourcePoint;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

/**
 * 交互式地图画布组件
 */
@Slf4j
public class InteractiveCanvas extends Canvas {

    private final MapContext mapManager = MapContext.getInstance();
    private final CameraContext cameraManager = CameraContext.getInstance();
    private final ResourcePointContext pointContext = ResourcePointContext.getInstance();
    private final ImageLoader imageLoader = ImageLoader.getInstance();

    private final Tooltip hintTooltip = new Tooltip();
    private double lastMouseX, lastMouseY;
    private boolean firstResize = true;
    // 交互状态
    private ResourcePoint hoveredPoint = null;
    private ContextMenu mapContextMenu;
    private ContextMenu imageContextMenu;

    public InteractiveCanvas() {
        setFocusTraversable(true);
        initMenus();
        initTooltip();

        // 监听尺寸变化逻辑
        widthProperty().addListener(e -> {
            mapManager.setViewWidth(getWidth());
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else mapManager.ensureBounds();
        });
        heightProperty().addListener(e -> {
            mapManager.setViewHeight(getHeight());
            if (firstResize && getWidth() > 0 && getHeight() > 0) {
                autoFitMap();
                firstResize = false;
            } else mapManager.ensureBounds();
        });

        // --- 鼠标悬停检测 (Hover & Hint) ---
        setOnMouseMoved(e -> {
            ResourcePoint point = findPointAt(e.getX(), e.getY());
            if (point != hoveredPoint) {
                if (hoveredPoint != null) hoveredPoint.setHovered(false);
                hoveredPoint = point;
                if (hoveredPoint != null) {
                    hoveredPoint.setHovered(true);
                    setCursor(Cursor.HAND);
                    hintTooltip.setText(hoveredPoint.getConfig().getMarkTypeName());
                    hintTooltip.show(this, e.getScreenX() + 10, e.getScreenY() + 10);
                } else {
                    setCursor(Cursor.DEFAULT);
                    hintTooltip.hide();
                }
            } else if (hoveredPoint != null) {
                hintTooltip.setAnchorX(e.getScreenX() + 10);
                hintTooltip.setAnchorY(e.getScreenY() + 10);
            }
        });

        // --- 鼠标右键菜单分流 ---
        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                if (hoveredPoint != null) {
                    showImageMenu(e.getScreenX(), e.getScreenY(), hoveredPoint);
                } else {
                    mapContextMenu.show(this, e.getScreenX(), e.getScreenY());
                }
            } else {
                hideAllMenus();
            }
        });

        // 拖拽与平移
        setOnMousePressed(e -> {
            hideAllMenus();
            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });
        setOnMouseDragged(e -> {
            if (cameraManager.isFollowMode()) cameraManager.setFollowMode(false);
            double dx = e.getX() - lastMouseX;
            double dy = e.getY() - lastMouseY;
            mapManager.setOffsetX(mapManager.getOffsetX() + dx);
            mapManager.setOffsetY(mapManager.getOffsetY() + dy);
            mapManager.ensureBounds();
            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        // 缩放
        setOnScroll(e -> {
            double factor = e.getDeltaY() > 0 ? 1.1 : 0.9;
            if (cameraManager.isFollowMode()) {
                double newScale = cameraManager.getFollowScale() * factor;
                cameraManager.setFollowScale(Math.max(0.3, Math.min(5, newScale)));
            } else {
                mapManager.zoom(factor, e.getX(), e.getY());
            }
        });
    }

    private void initMenus() {
        mapContextMenu = new ContextMenu();
        MenuItem addPoint = new MenuItem("在此处添加标记");
        MenuItem resetCam = new MenuItem("重置视角");
        resetCam.setOnAction(e -> autoFitMap());
        mapContextMenu.getItems().addAll(addPoint, new SeparatorMenuItem(), resetCam);

        imageContextMenu = new ContextMenu();
    }

    private void initTooltip() {
        hintTooltip.setShowDelay(Duration.ZERO);
        hintTooltip.setHideDelay(Duration.ZERO);
        hintTooltip.setStyle("-fx-background-color: rgba(30,30,30,0.9); -fx-text-fill: white; -fx-padding: 5px; -fx-border-color: #00BFFF;");
    }

    private ResourcePoint findPointAt(double mouseX, double mouseY) {
        double scale = mapManager.getScale();
        double logicX = (mouseX - mapManager.getOffsetX()) / scale;
        double logicY = (mouseY - mapManager.getOffsetY()) / scale;

        for (int i = pointContext.getAllPoints().size() - 1; i >= 0; i--) {
            ResourcePoint p = pointContext.getAllPoints().get(i);
            // 判定范围：ResourcePoint坐标是底部中心，向上偏移检测
            double r = 16.0;
            if (logicX >= p.getScreenPosition().getX() - r && logicX <= p.getScreenPosition().getX() + r &&
                    logicY >= p.getScreenPosition().getY() - r * 2 && logicY <= p.getScreenPosition().getY()) {
                return p;
            }
        }
        return null;
    }

    private void showImageMenu(double sx, double sy, ResourcePoint p) {
        imageContextMenu.getItems().clear();
        MenuItem info = new MenuItem(p.getConfig().getMarkTypeName());
        info.setDisable(true);
        MenuItem toggle = new MenuItem(p.isGrayed() ? "恢复标记" : "标记已采集");
        toggle.setOnAction(e -> p.setGrayed(!p.isGrayed()));
        MenuItem del = new MenuItem("删除此点位");
        imageContextMenu.getItems().addAll(info, new SeparatorMenuItem(), toggle, del);
        imageContextMenu.show(this, sx, sy);
    }

    private void hideAllMenus() {
        if (mapContextMenu.isShowing()) mapContextMenu.hide();
        if (imageContextMenu.isShowing()) imageContextMenu.hide();
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
        mapManager.setOffsetX((getWidth() - mapManager.getMapWidth() * scale) / 2);
        mapManager.setOffsetY((getHeight() - mapManager.getMapHeight() * scale) / 2);
        mapManager.ensureBounds();
    }
}