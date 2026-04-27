package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.map.loader.ImageLoader;
import com.luoke.app.map.model.ResourcePoint;
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
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
    private ResourcePoint hoveredPoint = null;
    private ContextMenu mapContextMenu;
    private ContextMenu imageContextMenu;

    private double clickSceneX;
    private double clickSceneY;

    public InteractiveCanvas() {
        setFocusTraversable(true);
        initMenus();
        initTooltip();

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

        setOnMouseExited(e -> {
            hintTooltip.hide();
            if (hoveredPoint != null) {
                hoveredPoint.setHovered(false);
                hoveredPoint = null;
            }
            setCursor(Cursor.DEFAULT);
        });

        setOnMousePressed(e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                clickSceneX = e.getX();
                clickSceneY = e.getY();
            }
            hideAllMenus();
            lastMouseX = e.getX();
            lastMouseY = e.getY();
        });

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

        addPoint.setOnAction(e -> openAddPointDialog(clickSceneX, clickSceneY));
    }

    private void openAddPointDialog(double canvasX, double canvasY) {
        // ... 坐标计算保持不变 ...
        double scale = mapManager.getScale();
        double logicX = (canvasX - mapManager.getOffsetX()) / scale;
        double logicY = (canvasY - mapManager.getOffsetY()) / scale;

        // 1. 获取去重后的类型列表
        ObservableList<String> allItems = FXCollections.observableArrayList();
        Set<String> markTypeSet = new TreeSet<>(); // 使用 TreeSet 自动排序，体验更好
        for (ResourcePoint point : pointContext.getAllPoints()) {
            String typeName = point.getConfig().getMarkTypeName();
            if (typeName != null && !typeName.isBlank()) markTypeSet.add(typeName);
        }
        allItems.addAll(markTypeSet);

        // 2. 初始化 ComboBox
        ComboBox<String> typeCombo = new ComboBox<>(allItems);
        typeCombo.setEditable(true);
        typeCombo.setPromptText("搜索/选择标记类型");
        typeCombo.setPrefWidth(280);

        // ==========================================
        // 🔥 核心逻辑：实现 AutoComplete 筛选效果
        // ==========================================
        typeCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) -> {
            // 如果是手动选择导致的文字改变，不触发搜索逻辑（防止死循环）
            if (typeCombo.getSelectionModel().getSelectedItem() != null &&
                    typeCombo.getSelectionModel().getSelectedItem().equals(newValue)) {
                return;
            }
            Platform.runLater(() -> {
                if (newValue == null || newValue.isEmpty()) {
                    typeCombo.setItems(allItems);
                } else {
                    // 筛选包含关键字的项目（不区分大小写）
                    ObservableList<String> filteredList = allItems.filtered(item ->
                            item.toLowerCase().contains(newValue.toLowerCase())
                    );
                    typeCombo.setItems(filteredList);
                }

                // 只要有内容就展示下拉列表，模拟 AutoComplete
                if (!typeCombo.getItems().isEmpty()) {
                    typeCombo.show();
                } else {
                    typeCombo.hide();
                }
            });
        });

        // 布局部分
        VBox content = new VBox(12, typeCombo);
        content.setAlignment(Pos.CENTER);
        content.setFillWidth(false);
        content.setStyle("-fx-padding: 20 0 20 0;");

        if (this.getParent() != null && this.getParent().getParent() instanceof StackPane rootStack) {
            DialogUtils.showConfirmDialog(rootStack, "新增标记", content, () -> {
                // 获取方式改为 getEditor().getText()，这样即使没在列表里选，输入的内容也生效
                String selected = typeCombo.getEditor().getText();
                if (selected == null || selected.isBlank()) return;
                pointContext.savePoint(selected, logicX, logicY);
                log.info("添加标记成功：{} → ({},{})", selected, logicX, logicY);
            }, () -> {
            });
        }
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

        List<ResourcePoint> list = pointContext.getAllPoints();
        for (int i = list.size() - 1; i >= 0; i--) {
            ResourcePoint p = list.get(i);
            double r = 16.0;
            Point2D pos = p.getScreenPosition();
            if (logicX >= pos.getX() - r && logicX <= pos.getX() + r &&
                    logicY >= pos.getY() - r * 2 && logicY <= pos.getY()) {
                return p;
            }
        }
        return null;
    }

    private void showImageMenu(double sx, double sy, ResourcePoint p) {
        imageContextMenu.getItems().clear();
        MenuItem info = new MenuItem(p.getConfig().getMarkTypeName());
        info.setDisable(true);
        imageContextMenu.getItems().addAll(info, new SeparatorMenuItem());
        if (p.isCollectible()) {
            MenuItem toggle = new MenuItem(p.isGrayed() ? "恢复标记" : "标记已采集");
            toggle.setOnAction(e -> p.setGrayed(!p.isGrayed()));
            imageContextMenu.getItems().add(toggle);
        }
        // 在 showImageMenu 方法中修改
        MenuItem del = new MenuItem("删除此点位");
        del.setOnAction(e -> {
            if (this.getParent() != null && this.getParent().getParent() instanceof StackPane rootStack) {
                DialogUtils.showConfirmDialog(rootStack,
                        String.format("确认移除标记[%s]", p.getConfig().getMarkTypeName()),
                        "该操作将从本地点位数据库中永久删除此标记",
                        () -> pointContext.deletePoint(p),
                        () -> {
                        });
            }
        });
        imageContextMenu.getItems().add(del);
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