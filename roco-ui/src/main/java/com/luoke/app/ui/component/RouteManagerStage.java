package com.luoke.app.ui.component;

import com.luoke.app.context.PathContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.map.model.RoutePath;
import com.luoke.app.ui.util.DialogUtils;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.List;

/**
 * 路线管理工具窗口 - 单例模式 (双重检查锁实现)
 */
@Slf4j
public class RouteManagerStage extends Stage {

    private static volatile RouteManagerStage instance;

    private final ListView<RoutePath> listView = new ListView<>();
    private final StackPane rootContainer; // 从外部传入的主容器
    private double xOffset = 0;
    private double yOffset = 0;

    private RouteManagerStage(StackPane rootContainer) {
        this.rootContainer = rootContainer;

        initStyle(StageStyle.TRANSPARENT);
        setAlwaysOnTop(false);
        initUI();

        // 窗口隐藏时清理状态
        this.setOnHiding(e -> {
            PathContext.getInstance().setCurrentMode(PathContext.Mode.VIEW);
            PathContext.getInstance().setActiveRoute(null);
        });
    }

    /**
     * 双重检查锁获取单例
     *
     * @param rootContainer 外部传入的 UI 根容器（如主界面的 StackPane）
     */
    public static RouteManagerStage getInstance(StackPane rootContainer) {
        if (instance == null) {
            synchronized (RouteManagerStage.class) {
                if (instance == null) {
                    instance = new RouteManagerStage(rootContainer);
                }
            }
        }
        return instance;
    }

    /**
     * 辅助获取方法：如果已经初始化过，可以直接获取
     */
    public static RouteManagerStage getInstance() {
        if (instance == null) {
            log.error("RouteManagerStage 未初始化，请先调用 getInstance(StackPane) 传入容器");
        }
        return instance;
    }

    private void initUI() {
        VBox mainLayout = new VBox(15);
        mainLayout.setPadding(new Insets(15));
        mainLayout.setStyle("-fx-background-color: #1e1e1e; -fx-background-radius: 12; -fx-border-color: #333333; -fx-border-radius: 12; -fx-border-width: 1.5;");

        // --- 标题栏 ---
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setCursor(javafx.scene.Cursor.MOVE);

        Label titleLabel = new Label("路线管理工具");
        titleLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button("×");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #888888; -fx-font-size: 20px; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> this.hide());

        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);

        titleBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            this.setX(event.getScreenX() - xOffset);
            this.setY(event.getScreenY() - yOffset);
        });

        // --- 路线列表 ---
        listView.setItems(PathContext.getInstance().getSavedRoutes());
        listView.setPrefHeight(200);
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.setStyle("-fx-background-color: #252525; -fx-control-inner-background: #252525; -fx-background-insets: 0;");

        // 右键菜单
        ContextMenu contextMenu = new ContextMenu();
        MenuItem deleteItem = new MenuItem("删除路线");
        deleteItem.setStyle("-fx-text-fill: #ff4444;");
        deleteItem.setOnAction(e -> handleDelete());
        contextMenu.getItems().add(deleteItem);
        listView.setContextMenu(contextMenu);

        // 单元格渲染
        listView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(RoutePath item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setText(item.getName());
                    setTextFill(Color.WHITE);
                    setStyle("-fx-background-color: transparent; -fx-padding: 5 10;");
                }
            }
        });

        listView.setOnMouseClicked(e -> {
            RoutePath selected = listView.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (e.getClickCount() == 2) {
                PathContext.getInstance().enterEditMode(selected);
            } else {
                PathContext.getInstance().viewMode(selected);
            }
        });

        // --- 按钮组 ---
        GridPane btnGrid = new GridPane();
        btnGrid.setHgap(10);
        btnGrid.setVgap(10);

        Button drawBtn = createActionBtn("绘制路线", "#4CAF50");
        drawBtn.setOnAction(e -> PathContext.getInstance().startNewRoute());

        Button saveBtn = createActionBtn("保存当前", "#2196F3");
        saveBtn.setOnAction(e -> handleSave()); // 传入外部容器

        Button importBtn = createActionBtn("导入路线", "#555555");
        importBtn.setOnAction(e -> handleImport());

        Button exportBtn = createActionBtn("导出路线", "#555555");
        exportBtn.setOnAction(e -> handleExport());

        btnGrid.add(drawBtn, 0, 0);
        btnGrid.add(saveBtn, 1, 0);
        btnGrid.add(importBtn, 0, 1);
        btnGrid.add(exportBtn, 1, 1);

        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(50);
        btnGrid.getColumnConstraints().addAll(cc, cc);

        mainLayout.getChildren().addAll(titleBar, new Label("路线列表 (单机选中，双击修改，右键删除):"), listView, btnGrid);

        // 管理窗口本身的布局（不需要再包一层 StackPane，因为 Dialog 现在要去 rootContainer 弹）
        Scene scene = new Scene(mainLayout, 340, 480);
        scene.setFill(Color.TRANSPARENT);
        setScene(scene);
    }

    private Button createActionBtn(String text, String color) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setStyle(String.format("-fx-background-color: %s; -fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 8; -fx-cursor: hand;", color));
        return btn;
    }

    public void refreshList() {
        Platform.runLater(listView::refresh);
    }

    public void handleSave() {
        RoutePath active = PathContext.getInstance().getActiveRoute();
        if (active == null || active.getNodes().isEmpty()) {
            log.warn("没有正在编辑的路线，无法保存");
            return;
        }

        VBox content = new VBox(15);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setStyle("-fx-padding: 20;");

        Label label = new Label("请输入路线名称：");
        label.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        TextField nameField = new TextField(active.getName() != null ? active.getName() : "新建路线");
        nameField.setPrefWidth(300);
        nameField.setStyle("-fx-background-color: #252525; -fx-text-fill: white; -fx-border-color: #333; -fx-border-radius: 4;");

        content.getChildren().addAll(label, nameField);

        DialogUtils.showConfirmDialog(
                rootContainer, // 此时在画布上弹
                "保存路线",
                content,
                () -> {
                    String newName = nameField.getText();
                    if (newName != null && !newName.isBlank()) {
                        active.setName(newName);
                        if (PathContext.getInstance().saveToLocal()) {
                            refreshList();
                            PathContext.getInstance().setCurrentMode(PathContext.Mode.VIEW);
                            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("保存成功", NotificationType.SUCCESS));
                        } else {
                            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("保存失败", NotificationType.ERROR));
                        }
                    }
                },
                () -> {
                }
        );
    }

    private void handleDelete() {
        RoutePath selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        DialogUtils.showConfirmDialog(
                rootContainer, // 在画布上弹
                "确认删除",
                "确定要永久删除路线 [" + selected.getName() + "] 吗？",
                () -> {
                    PathContext.getInstance().getSavedRoutes().remove(selected);
                    if (PathContext.getInstance().getActiveRoute() == selected) {
                        PathContext.getInstance().setActiveRoute(null);
                        PathContext.getInstance().setCurrentMode(PathContext.Mode.VIEW);
                    }
                    if (PathContext.getInstance().saveToLocal()) {
                        refreshList();
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("删除成功", NotificationType.SUCCESS));
                    }
                    refreshList();
                },
                null
        );
    }

    private void handleImport() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择导入的路线文件");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON文件", "*.json"));
        File selectedFile = fileChooser.showOpenDialog(this);
        if (selectedFile == null) return;

        List<RoutePath> importedPaths = PathContext.getInstance().resolve(selectedFile);
        if (importedPaths == null || importedPaths.isEmpty()) {
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("文件解析失败", NotificationType.ERROR));
            return;
        }

        VBox content = new VBox(10);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setStyle("-fx-padding: 10;");

        Label tipLabel = new Label(String.format("解析成功，发现 %d 条路线：", importedPaths.size()));
        tipLabel.setStyle("-fx-text-fill: #BBBBBB; -fx-font-size: 13px;");

        VBox listContainer = new VBox(5);
        for (RoutePath path : importedPaths) {
            Label nameLabel = new Label(" • " + (path.getName() != null ? path.getName() : "未命名路线"));
            nameLabel.setStyle("-fx-text-fill: white;");
            listContainer.getChildren().add(nameLabel);
        }

        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setPrefHeight(150);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: #333;");

        content.getChildren().addAll(tipLabel, scrollPane, new Label("确定要导入这些路线吗？"));

        DialogUtils.showConfirmDialog(
                rootContainer, // 在画布上弹
                "确认导入",
                content,
                () -> {
                    PathContext.getInstance().getSavedRoutes().addAll(importedPaths);
                    if (PathContext.getInstance().saveToLocal()) {
                        refreshList();
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("成功导入路线", NotificationType.SUCCESS));
                    }
                },
                () -> {
                }
        );
    }

    private void handleExport() {
        RoutePath selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("导出路线");
        fileChooser.setInitialFileName(selected.getName() + ".json");
        File saveFile = fileChooser.showSaveDialog(this);
        if (saveFile != null) {
            if (PathContext.getInstance().exportPaths(selected, saveFile)) {
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("导出成功", NotificationType.SUCCESS));
            } else {
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("导出失败", NotificationType.ERROR));
            }
        }
    }
}