package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.PathContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.map.loader.ImageLoader;
import com.luoke.app.map.model.Point;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.map.model.RoutePath;
import com.luoke.app.ui.render.PathRenderer;
import com.luoke.app.ui.util.DialogUtils;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.input.*;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
public class InteractiveCanvas extends Canvas {

    private final MapContext mapManager = MapContext.getInstance();
    private final CameraContext cameraManager = CameraContext.getInstance();
    private final ResourcePointContext pointContext = ResourcePointContext.getInstance();
    private final PathContext pathContext = PathContext.getInstance();
    private final ImageLoader imageLoader = ImageLoader.getInstance();

    private final Tooltip hintTooltip = new Tooltip();
    private double lastMouseX, lastMouseY;
    private boolean firstResize = true;
    private ResourcePoint hoveredPoint = null;
    private ContextMenu mapContextMenu;
    private ContextMenu imageContextMenu;

    private double clickSceneX;
    private double clickSceneY;
    private final KeyCombination saveCombo = new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_ANY);
    private final KeyCombination undoCombo = new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_ANY);
    private int draggedNodeIndex = -1;

    public InteractiveCanvas() {
        setFocusTraversable(true);
        initMenus();
        initTooltip();
        initListeners();

        // 确保初始化后获得焦点以捕获快捷键
        Platform.runLater(this::requestFocus);
    }

    private void initListeners() {
        // 鼠标进入自动抓取焦点
        setOnMouseEntered(e -> requestFocus());

        widthProperty().addListener(e -> {
            mapManager.setViewWidth(getWidth());
            handleInitialFit();
        });
        heightProperty().addListener(e -> {
            mapManager.setViewHeight(getHeight());
            handleInitialFit();
        });

        // 拦截按键事件
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleKeyEvents);

        setOnMouseMoved(e -> {
            pathContext.setMouseLogicX(toLogicX(e.getX()));
            pathContext.setMouseLogicY(toLogicY(e.getY()));
            handlePointHover(e);
        });

        setOnMouseExited(e -> {
            hintTooltip.hide();
            if (hoveredPoint != null) {
                hoveredPoint.setHovered(false);
                hoveredPoint = null;
            }
            setCursor(Cursor.DEFAULT);
        });

        setOnMousePressed(e -> {
            requestFocus();
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            hideAllMenus();

            if (e.getButton() == MouseButton.PRIMARY) {
                if (pathContext.getCurrentMode() == PathContext.Mode.EDITING) {
                    draggedNodeIndex = findNodeIndexAt(e.getX(), e.getY());
                    if (draggedNodeIndex != -1) e.consume();
                }
            } else if (e.getButton() == MouseButton.SECONDARY) {
                clickSceneX = e.getX();
                clickSceneY = e.getY();
            }
        });

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                if (pathContext.getCurrentMode() == PathContext.Mode.VIEW) {
                    return;
                }
                double lx = toLogicX(e.getX());
                double ly = toLogicY(e.getY());

                if (pathContext.getCurrentMode() == PathContext.Mode.DRAWING) {
                    ResourcePoint targetPoint = findPointAt(e.getX(), e.getY());
                    double finalX = (targetPoint != null) ? targetPoint.getScreenPosition().getX() : lx;
                    double finalY = (targetPoint != null) ? targetPoint.getScreenPosition().getY() : ly;
                    pathContext.getActiveRoute().addNode(new Point(finalX, finalY));
                    return;
                }

                if (pathContext.getCurrentMode() == PathContext.Mode.EDITING) {
                    if (draggedNodeIndex == -1) {
                        int insertIndex = findInsertPosition(e.getX(), e.getY());
                        if (insertIndex != -1) {
                            pathContext.getActiveRoute().addNode(insertIndex, new Point(lx, ly));
                        } else {
                            pathContext.getActiveRoute().addNode(new Point(lx, ly));
                        }
                    }
                    return;
                }
            }

            if (e.getButton() == MouseButton.SECONDARY) {
                if (pathContext.getCurrentMode() != PathContext.Mode.VIEW) {
                    int nodeIdx = findNodeIndexAt(e.getX(), e.getY());
                    if (nodeIdx != -1) {
                        pathContext.getActiveRoute().remove(draggedNodeIndex);
                        return;
                    }
                }
                if (hoveredPoint != null) {
                    showImageMenu(e.getScreenX(), e.getScreenY(), hoveredPoint);
                } else {
                    mapContextMenu.show(this, e.getScreenX(), e.getScreenY());
                }
            } else {
                hideAllMenus();
            }
        });

        setOnMouseDragged(e -> {
            if (draggedNodeIndex != -1 && pathContext.getCurrentMode() == PathContext.Mode.EDITING) {
                double rawLx = toLogicX(e.getX());
                double rawLy = toLogicY(e.getY());
                ResourcePoint snapTarget = findPointAt(e.getX(), e.getY());

                if (snapTarget != null) {
                    Point2D snapPos = snapTarget.getScreenPosition();
                    pathContext.getActiveRoute().setNode(draggedNodeIndex, new Point(snapPos.getX(), snapPos.getY()));
                } else {
                    pathContext.getActiveRoute().setNode(draggedNodeIndex, new Point(rawLx, rawLy));
                }
                return;
            }

            if (cameraManager.isFollowMode()) cameraManager.setFollowMode(false);
            double dx = e.getX() - lastMouseX;
            double dy = e.getY() - lastMouseY;
            mapManager.setOffsetX(mapManager.getOffsetX() + dx);
            mapManager.setOffsetY(mapManager.getOffsetY() + dy);
            mapManager.ensureBounds();
            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

        setOnMouseReleased(e -> draggedNodeIndex = -1);

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

    private void handleKeyEvents(KeyEvent event) {
        if (saveCombo.match(event)) {
            RouteManagerStage.getInstance().handleSave();
            event.consume();
        } else if (undoCombo.match(event)) {
            if (pathContext.getCurrentMode() != PathContext.Mode.VIEW) {
                RoutePath active = pathContext.getActiveRoute();
                if (active != null && !active.getNodes().isEmpty()) {
                    active.remove(active.getNodes().size() - 1);
                }
            }
            event.consume();
        } else if (event.getCode() == KeyCode.ESCAPE) {
            hideAllMenus();
            hintTooltip.hide();
            pathContext.setActiveRoute(null);
            pathContext.setCurrentMode(PathContext.Mode.VIEW);
            event.consume();
            log.info("ESC 已成功触发一键退出并清理数据");
        }
    }


    public void drawAllResourceIcons(GraphicsContext gc) {
        // 1. 渲染资源图标
        List<ResourcePoint> points = pointContext.getAllPoints();
        if (!points.isEmpty()) {
            double scale = mapManager.getScale();
            double viewX = -mapManager.getOffsetX() / scale;
            double viewY = -mapManager.getOffsetY() / scale;
            double viewW = getWidth() / scale;
            double viewH = getHeight() / scale;
            double padding = 32.0;
            for (ResourcePoint point : points) {
                Point2D pos = point.getScreenPosition();
                if (pos.getX() < viewX - padding || pos.getX() > viewX + viewW + padding ||
                        pos.getY() < viewY - padding || pos.getY() > viewY + viewH + padding) continue;

                String iconPath = point.getConfig().getIcon();
                if (iconPath != null && !iconPath.isBlank()) {
                    Image icon = imageLoader.loadScaledIcon(AppConfig.ICON_DIR + iconPath);
                    point.render(gc, icon);
                }
            }
        }

        // 2. 渲染路径
        PathRenderer.draw(gc);
    }

    private double toLogicX(double canvasX) {
        return (canvasX - mapManager.getOffsetX()) / mapManager.getScale();
    }

    private double toLogicY(double canvasY) {
        return (canvasY - mapManager.getOffsetY()) / mapManager.getScale();
    }

    private int findNodeIndexAt(double mx, double my) {
        RoutePath active = pathContext.getActiveRoute();
        if (active == null) return -1;
        double lx = toLogicX(mx);
        double ly = toLogicY(my);
        double threshold = 15.0 / mapManager.getScale();
        List<Point2D> nodes = active.getNodes();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).distance(lx, ly) < threshold) return i;
        }
        return -1;
    }

    private int findInsertPosition(double mx, double my) {
        RoutePath active = pathContext.getActiveRoute();
        if (active == null || active.getNodes().size() < 2) return -1;
        double lx = toLogicX(mx);
        double ly = toLogicY(my);
        double threshold = 12.0 / mapManager.getScale();
        List<Point2D> nodes = active.getNodes();
        for (int i = 0; i < nodes.size() - 1; i++) {
            if (distancePointToSegment(lx, ly, nodes.get(i).getX(), nodes.get(i).getY(), nodes.get(i + 1).getX(), nodes.get(i + 1).getY()) < threshold)
                return i + 1;
        }
        return -1;
    }

    private double distancePointToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double l2 = Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2);
        if (l2 == 0) return Math.sqrt(Math.pow(px - x1, 2) + Math.pow(py - y1, 2));
        double t = Math.max(0, Math.min(1, ((px - x1) * (x2 - x1) + (py - y1) * (y2 - y1)) / l2));
        return Math.sqrt(Math.pow(px - (x1 + t * (x2 - x1)), 2) + Math.pow(py - (y1 + t * (y2 - y1)), 2));
    }

    private void handlePointHover(MouseEvent e) {
        ResourcePoint point = findPointAt(e.getX(), e.getY());
        if (point != hoveredPoint) {
            if (hoveredPoint != null) hoveredPoint.setHovered(false);
            hoveredPoint = point;
            if (hoveredPoint != null) {
                hoveredPoint.setHovered(true);
                setCursor(Cursor.HAND);
                String prefix = (pathContext.getCurrentMode() == PathContext.Mode.DRAWING) ? "吸附: " : "";
                hintTooltip.setText(prefix + hoveredPoint.getConfig().getMarkTypeName());
                hintTooltip.show(this, e.getScreenX() + 10, e.getScreenY() + 10);
            } else {
                setCursor(pathContext.getCurrentMode() != PathContext.Mode.VIEW ? Cursor.CROSSHAIR : Cursor.DEFAULT);
                hintTooltip.hide();
            }
        } else if (hoveredPoint != null) {
            hintTooltip.setAnchorX(e.getScreenX() + 10);
            hintTooltip.setAnchorY(e.getScreenY() + 10);
        }
    }

    private ResourcePoint findPointAt(double mouseX, double mouseY) {
        double lx = toLogicX(mouseX);
        double ly = toLogicY(mouseY);
        List<ResourcePoint> nearbyPoints = pointContext.getNearbyResources(lx, ly);
        if (nearbyPoints.isEmpty()) return null;
        for (int i = nearbyPoints.size() - 1; i >= 0; i--) {
            ResourcePoint p = nearbyPoints.get(i);
            Point2D pos = p.getScreenPosition();
            double r = 16.0;
            if (lx >= pos.getX() - r && lx <= pos.getX() + r && ly >= pos.getY() - r * 2 && ly <= pos.getY()) return p;
        }
        return null;
    }

    private void showImageMenu(double sx, double sy, ResourcePoint p) {
        imageContextMenu.getItems().clear();
        MenuItem info = new MenuItem(p.getConfig().getMarkTypeName());
        info.setDisable(true);
        imageContextMenu.getItems().addAll(info, new SeparatorMenuItem());
        if (ResourcePointContext.getInstance().isCollect(p.getConfig().getMarkTypeName())) {
            MenuItem toggle = new MenuItem(p.isGrayed() ? "恢复标记" : "标记为已采集");
            toggle.setOnAction(_ -> p.setGrayed(!p.isGrayed()));
            imageContextMenu.getItems().add(toggle);
        }
        MenuItem del = new MenuItem("删除点位");
        del.setStyle("-fx-text-fill: #ff4444;");
        del.setOnAction(e -> {
            if (this.getParent() != null && this.getParent().getParent() instanceof StackPane rootStack) {
                DialogUtils.showConfirmDialog(rootStack, "删除标记", "确定要永久删除吗？", () -> pointContext.deletePoint(p), null);
            }
        });
        imageContextMenu.getItems().add(del);
        imageContextMenu.show(this, sx, sy);
    }

    private void hideAllMenus() {
        if (mapContextMenu.isShowing()) mapContextMenu.hide();
        if (imageContextMenu.isShowing()) imageContextMenu.hide();
    }

    private void initMenus() {
        mapContextMenu = new ContextMenu();
        MenuItem addPoint = new MenuItem("在此处添加标记");
        addPoint.setOnAction(e -> openAddPointDialog(clickSceneX, clickSceneY));
        MenuItem resetCam = new MenuItem("重置视角");
        resetCam.setOnAction(e -> autoFitMap());
        mapContextMenu.getItems().addAll(addPoint, new SeparatorMenuItem(), resetCam);
        imageContextMenu = new ContextMenu();
    }

    private void openAddPointDialog(double canvasX, double canvasY) {
        Set<String> markTypeSet = new TreeSet<>();
        for (ResourcePoint point : pointContext.getAllPoints()) {
            markTypeSet.add(point.getConfig().getMarkTypeName());
        }
        ObservableList<String> allItems = FXCollections.observableArrayList(markTypeSet);
        ComboBox<String> typeCombo = new ComboBox<>(allItems);
        typeCombo.setEditable(true);
        typeCombo.setPrefWidth(280);
        VBox content = new VBox(12, new Label("选择或输入新的点位名称:"), typeCombo);
        content.setAlignment(Pos.CENTER);
        content.setStyle("-fx-padding: 20 10 10 10;");
        if (this.getParent() != null && this.getParent().getParent() instanceof StackPane rootStack) {
            DialogUtils.showConfirmDialog(rootStack, "新增标记点", content, () -> {
                String selected = typeCombo.getEditor().getText();
                if (selected != null && !selected.isBlank()) pointContext.savePoint(selected, canvasX, canvasY);
            }, null);
        }
    }

    private void initTooltip() {
        hintTooltip.setShowDelay(Duration.ZERO);
        hintTooltip.setHideDelay(Duration.ZERO);
        hintTooltip.setStyle("-fx-background-color: rgba(35,35,35,0.9); -fx-text-fill: white; -fx-padding: 6px; -fx-border-color: #00BFFF; -fx-border-radius: 4; -fx-background-radius: 4;");
    }

    private void handleInitialFit() {
        if (getWidth() > 0 && getHeight() > 0) {
            if (firstResize) {
                autoFitMap();
                firstResize = false;
            } else {
                // 如果窗口被拉伸导致图片太小产生留白，重新调用 autoFit
                double minScale = Math.max(getWidth() / mapManager.getMapWidth(), getHeight() / mapManager.getMapHeight());
                if (mapManager.getScale() < minScale) {
                    autoFitMap();
                } else {
                    mapManager.ensureBounds();
                }
            }
        }
    }

    /**
     * 自动缩放地图以撑满窗口（Cover 模式），消除留白。
     */
    public void autoFitMap() {
        if (mapManager.getMapWidth() <= 0 || mapManager.getMapHeight() <= 0) return;

        // 使用 Math.max：确保较长的一边也被填满，从而消除留白
        double scale = Math.max(getWidth() / mapManager.getMapWidth(), getHeight() / mapManager.getMapHeight());
        mapManager.setScale(scale);

        // 计算偏移量，使地图在多余的部分对称溢出（即居中显示）
        double offsetX = (getWidth() - mapManager.getMapWidth() * scale) / 2.0;
        double offsetY = (getHeight() - mapManager.getMapHeight() * scale) / 2.0;

        mapManager.setOffsetX(offsetX);
        mapManager.setOffsetY(offsetY);

        // 强行约束一次边界，防止计算误差产生空隙
        mapManager.ensureBounds();
    }
}