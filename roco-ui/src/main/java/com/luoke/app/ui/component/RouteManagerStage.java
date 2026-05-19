package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.context.PathContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.map.model.RoutePath;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.FxRippleUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
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

    // 列表单元格样式 — 现代化卡片风格
    private static final String NORMAL_STYLE =
            "-fx-text-fill: -color-fg-default; " +
                    "-fx-background-color: transparent; " +
                    "-fx-padding: 8 10 8 12; " +
                    "-fx-background-radius: 6; " +
                    "-fx-background-insets: 2 8 2 8;";
    private static final String HOVER_STYLE =
            "-fx-text-fill: -color-fg-default; " +
                    "-fx-background-color: -color-neutral-subtle; " +
                    "-fx-padding: 8 10 8 12; " +
                    "-fx-background-radius: 6; " +
                    "-fx-background-insets: 2 8 2 8; " +
                    "-fx-border-color: -color-neutral-emphasis; " +
                    "-fx-border-width: 0 0 0 3; " +
                    "-fx-border-radius: 6;";
    private static final String SELECTED_STYLE =
            "-fx-text-fill: -color-fg-emphasis; " +
                    "-fx-background-color: -color-accent-subtle; " +
                    "-fx-padding: 8 10 8 12; " +
                    "-fx-background-radius: 6; " +
                    "-fx-background-insets: 2 8 2 8; " +
                    "-fx-border-color: -color-accent-emphasis; " +
                    "-fx-border-width: 0 0 0 3; " +
                    "-fx-border-radius: 6;";
    private static final String DEL_BTN_STYLE =
            "-fx-stroke: -color-fg-muted; -fx-stroke-width: 2;";
    private static final String DEL_BTN_HOVER_STYLE =
            "-fx-stroke: -color-danger-fg; -fx-stroke-width: 2;";
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
        this.setOnHiding(_ -> {
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

    private static SVGPath getSvgPath() {
        SVGPath closeIcon = new SVGPath();
        closeIcon.setContent("M1 1 L9 9 M9 1 L1 9");
        closeIcon.setStyle("-fx-stroke: -color-fg-default; -fx-stroke-width: 2;"); // 细线粗细
        return closeIcon;
    }

    private static FileChooser createJsonFileChooser(String title) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON文件", "*.json"));
        return fc;
    }

    private void initUI() {
        VBox mainLayout = new VBox(15);
        mainLayout.setPadding(new Insets(15));
        mainLayout.setStyle(
                "-fx-background-color: -color-bg-default; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 12; " +
                        "-fx-border-width: 1.5;"
        );

        // --- 标题栏 ---
        HBox titleBar = new HBox();
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setCursor(Cursor.MOVE);

        Label titleLabel = new Label("路线管理工具");
        titleLabel.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button();
        closeBtn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);
        SVGPath closeIcon = getSvgPath();
        closeBtn.setGraphic(closeIcon);
        closeBtn.setStyle("-fx-cursor: hand; -fx-padding: 8;");
        closeBtn.setOnAction(_ -> this.hide());

        titleBar.getChildren().addAll(titleLabel, spacer, closeBtn);

        titleBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            this.setX(event.getScreenX() - xOffset);
            this.setY(event.getScreenY() - yOffset);
        });

        // --- 说明文字 ---
        Label hintLabel = new Label("路线列表 (单击选中，双击修改):");
        hintLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        // --- 路线列表 ---
        PathContext pc = PathContext.getInstance();
        ObservableList<RoutePath> routeItems = FXCollections.observableArrayList(pc.getSavedRoutes());
        pc.onChange(routes -> Platform.runLater(() -> routeItems.setAll(routes)));
        listView.setItems(routeItems);
        listView.setPrefHeight(200);
        VBox.setVgrow(listView, Priority.ALWAYS);
        listView.setStyle(
                "-fx-background-color: transparent; " +
                        "-fx-control-inner-background: -color-bg-inset; " +
                        "-fx-background-insets: 0; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 8; " +
                        "-fx-padding: 4;"
        );
        listView.setFocusTraversable(false);

        // 单元格渲染（行尾删除按钮）
        listView.setCellFactory(_ -> new ListCell<>() {
            private final HBox row = new HBox();
            private final Label nameLabel = new Label();
            private final StackPane delBtn = new StackPane();
            private final SVGPath delIcon = new SVGPath();

            {
                delIcon.setContent("M1 1 L9 9 M9 1 L1 9");
                delIcon.setStyle(DEL_BTN_STYLE);
                delIcon.setMouseTransparent(true);
                delBtn.getChildren().add(delIcon);
                delBtn.setCursor(Cursor.HAND);
                delBtn.setPadding(new Insets(4, 10, 4, 6));
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                row.getChildren().addAll(nameLabel, spacer, delBtn);
                row.setAlignment(Pos.CENTER_LEFT);
                // 选中状态变化时更新样式
                selectedProperty().addListener((_, _, isSel) -> {
                    if (isEmpty()) return;
                    if (isSel) {
                        setStyle(SELECTED_STYLE);
                    } else {
                        setStyle(NORMAL_STYLE);
                    }
                });
            }

            @Override
            protected void updateItem(RoutePath item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    nameLabel.setText(item.getName());
                    nameLabel.setStyle("-fx-text-fill: -color-fg-default;");
                    delBtn.setOnMouseEntered(_ -> delIcon.setStyle(DEL_BTN_HOVER_STYLE));
                    delBtn.setOnMouseExited(_ -> delIcon.setStyle(DEL_BTN_STYLE));
                    delBtn.setOnMouseClicked(e -> {
                        e.consume();
                        handleDelete(item);
                    });
                    setGraphic(row);
                    if (isSelected()) {
                        setStyle(SELECTED_STYLE);
                    } else {
                        setStyle(NORMAL_STYLE);
                    }
                    setOnMouseEntered(_ -> {
                        if (!isSelected()) setStyle(HOVER_STYLE);
                    });
                    setOnMouseExited(_ -> {
                        if (!isSelected()) setStyle(NORMAL_STYLE);
                    });
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

        Button drawBtn = createActionBtn("绘制路线", Styles.SUCCESS);
        drawBtn.setOnAction(_ -> PathContext.getInstance().startNewRoute());

        Button saveBtn = createActionBtn("保存当前", Styles.ACCENT);
        saveBtn.setOnAction(_ -> handleSave());

        Button importBtn = createActionBtn("导入路线", null);
        importBtn.setOnAction(_ -> handleImport());

        Button exportBtn = createActionBtn("导出路线", null);
        exportBtn.setOnAction(_ -> handleExport());

        btnGrid.add(drawBtn, 0, 0);
        btnGrid.add(saveBtn, 1, 0);
        btnGrid.add(importBtn, 0, 1);
        btnGrid.add(exportBtn, 1, 1);

        ColumnConstraints cc = new ColumnConstraints();
        cc.setPercentWidth(50);
        btnGrid.getColumnConstraints().addAll(cc, cc);

        mainLayout.getChildren().addAll(titleBar, hintLabel, listView, btnGrid);

        Scene scene = new Scene(mainLayout, 340, 480);
        scene.setFill(Color.TRANSPARENT);
        setScene(scene);
    }

    private Button createActionBtn(String text, String styleClass) {
        Button btn = new Button(text);
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.getStyleClass().add(Styles.BUTTON_OUTLINED);
        if (styleClass != null) {
            btn.getStyleClass().add(styleClass);
        }
        FxRippleUtil.install(btn);
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
        label.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 14px;");

        TextField nameField = new TextField(active.getName() != null ? active.getName() : "新建路线");
        nameField.setPrefWidth(300);
        nameField.setStyle("-fx-background-color: -color-bg-inset; -fx-text-fill: -color-fg-default; -fx-border-color: -color-border-muted; -fx-border-radius: 4;");

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

    private void handleDelete(RoutePath target) {
        if (target == null) return;

        DialogUtils.showConfirmDialog(
                rootContainer,
                "确认删除",
                "确定要永久删除路线 [" + target.getName() + "] 吗？",
                () -> {
                    PathContext.getInstance().removeRoute(target);
                    if (PathContext.getInstance().getActiveRoute() == target) {
                        PathContext.getInstance().setActiveRoute(null);
                        PathContext.getInstance().setCurrentMode(PathContext.Mode.VIEW);
                    }
                    if (PathContext.getInstance().saveToLocal()) {
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("删除成功", NotificationType.SUCCESS));
                    }
                },
                () -> {
                }
        );
    }

    private void handleImport() {
        FileChooser fileChooser = createJsonFileChooser("选择导入的路线文件");
        File selectedFile = fileChooser.showOpenDialog(this);
        if (selectedFile == null) return;

        List<RoutePath> importedPaths = PathContext.getInstance().resolve(selectedFile);
        if (importedPaths == null || importedPaths.isEmpty()) {
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION, new StatusEvent("文件解析失败", NotificationType.ERROR));
            return;
        }

        VBox content = new VBox(12);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setStyle("-fx-padding: 15;");

        Label tipLabel = new Label(String.format("解析成功，发现 %d 条路线：", importedPaths.size()));
        tipLabel.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 14px; -fx-font-weight: bold;");

        VBox listContainer = new VBox(0); // 无间距，边框线分隔
        listContainer.setStyle(
                "-fx-background-color: -color-bg-inset; " +
                        "-fx-background-radius: 6; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 6;"
        );
        int count = importedPaths.size();
        for (int i = 0; i < count; i++) {
            RoutePath path = importedPaths.get(i);
            Label nameLabel = new Label("  " + (i + 1) + ".  " + (path.getName() != null ? path.getName() : "未命名路线"));
            nameLabel.setStyle(
                    "-fx-text-fill: -color-fg-default; " +
                            "-fx-padding: 8 12; " +
                            "-fx-background-color: " + (i % 2 == 0 ? "transparent" : "-color-neutral-subtle") + "; " +
                            "-fx-background-radius: " + (i == 0 ? "6 6 0 0" : i == count - 1 ? "0 0 6 6" : "0") + ";"
            );
            nameLabel.setMaxWidth(Double.MAX_VALUE);
            listContainer.getChildren().add(nameLabel);
        }

        // 少量路线直接展示，超过 10 条才启用滚动
        if (count <= 10) {
            content.getChildren().addAll(tipLabel, listContainer);
        } else {
            ScrollPane scrollPane = new ScrollPane(listContainer);
            scrollPane.setMaxHeight(250);
            scrollPane.setFitToWidth(true);
            scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
            content.getChildren().addAll(tipLabel, scrollPane);
        }

        Label confirmLabel = new Label("确定要导入这些路线吗？");
        confirmLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        content.getChildren().add(confirmLabel);

        DialogUtils.showConfirmDialog(
                rootContainer, // 在画布上弹
                "确认导入",
                content,
                () -> {
                    PathContext.getInstance().addRoutes(importedPaths);
                    if (PathContext.getInstance().saveToLocal()) {
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
        FileChooser fileChooser = createJsonFileChooser("导出路线");
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