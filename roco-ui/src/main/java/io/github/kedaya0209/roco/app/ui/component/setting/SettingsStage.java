package io.github.kedaya0209.roco.app.ui.component.setting;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.util.DialogUtils;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.StageStyle;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * IntelliJ IDEA 风格设置面板 — 左侧分类列表 + 右侧配置面板 + 底部操作栏。
 * 单例模式，通过 {@link #getInstance()} 获取实例。
 *
 * <p>职责边界：仅处理 Stage 生命周期和 UI 编排。
 * 配置数据管理委托给 {@link SettingConfigManager}，控件创建委托给 {@link SettingFieldBuilder}。</p>
 */
@NotThreadSafe
@Slf4j
public class SettingsStage extends Stage {

    private static volatile SettingsStage instance;

    /**
     * CaptureService 引用（用于全帧模式切换），由 ModernCanvasApp 注入
     */
    @Setter
    private static FullFrameControl captureService;
    private final SettingConfigManager configManager = new SettingConfigManager();
    private final StackPane rootStackPane = new StackPane();
    private final StackPane rightPanel;
    private final ListView<SettingCategory> categoryList;
    private StackPane ownerRoot;
    private Button applyBtn;
    private Label titleLabel;
    private PlayerPreview playerPreview;
    private RoiPreview currentRoiPreview;
    private double dragX, dragY;
    /**
     * 首次打开标记：构造器不刷新分类，延迟到 showDialog 中执行
     */
    private volatile boolean needsInitialRefresh = true;

    private SettingsStage() {
        initStyle(StageStyle.TRANSPARENT);
        setAlwaysOnTop(false);

        // 修改状态回调：更新标题和按钮
        configManager.setOnModifiedChanged(() -> {
            if (configManager.isModified()) {
                titleLabel.setText("设置 (已修改)");
                applyBtn.setDisable(false);
            } else {
                titleLabel.setText("设置");
                applyBtn.setDisable(true);
            }
        });

        configManager.setPostApplyHook(() -> {
        });

        // --- 根布局 ---
        VBox root = new VBox();
        root.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 12; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 12; -fx-border-width: 1.5;");
        root.setPrefSize(720, 540);
        root.setMinSize(500, 400);

        // --- 标题栏 ---
        HBox titleBar = buildTitleBar();
        // 拖拽移动窗口（需存储偏移量，不可用 sceneX 实时计算）
        titleBar.setOnMousePressed(e -> {
            dragX = e.getScreenX() - getX();
            dragY = e.getScreenY() - getY();
        });
        titleBar.setOnMouseDragged(e -> {
            setX(e.getScreenX() - dragX);
            setY(e.getScreenY() - dragY);
        });

        // --- 主体 ---
        HBox body = new HBox();
        body.setPadding(new Insets(5, 15, 10, 15));
        VBox.setVgrow(body, Priority.ALWAYS);

        categoryList = buildCategoryList();
        rightPanel = new StackPane();
        rightPanel.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 8; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 8; -fx-border-width: 1;");
        HBox.setHgrow(rightPanel, Priority.ALWAYS);
        rightPanel.setPadding(new Insets(15));

        Label placeholder = new Label("请选择一个配置分类");
        placeholder.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");
        placeholder.setAlignment(Pos.CENTER);
        StackPane.setAlignment(placeholder, Pos.CENTER);
        rightPanel.getChildren().add(placeholder);

        body.getChildren().addAll(categoryList, rightPanel);
        HBox.setMargin(rightPanel, new Insets(0, 0, 0, 10));

        // --- 底部按钮栏 ---
        HBox buttonBar = new HBox(8);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);
        buttonBar.setPadding(new Insets(10, 15, 15, 15));

        Button cancelBtn = new Button("取消");
        cancelBtn.setStyle("-fx-cursor: hand;");
        FxRippleUtil.install(cancelBtn);
        cancelBtn.setOnAction(_ -> handleClose());

        applyBtn = new Button("应用");
        applyBtn.setDisable(true);
        applyBtn.setStyle("-fx-cursor: hand;");
        applyBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
        FxRippleUtil.install(applyBtn);
        applyBtn.setOnAction(_ -> doApply());

        Button okBtn = new Button("确定");
        okBtn.setStyle("-fx-cursor: hand;");
        okBtn.getStyleClass().addAll(Styles.ACCENT);
        FxRippleUtil.install(okBtn);
        okBtn.setOnAction(_ -> {
            doApply();
            cleanupPreview();
            hide();
        });

        buttonBar.getChildren().addAll(cancelBtn, applyBtn, okBtn);

        // --- 组装 ---
        root.getChildren().addAll(titleBar, body, buttonBar);
        rootStackPane.getChildren().add(root);

        Scene scene = new Scene(rootStackPane, 720, 540);
        scene.setFill(Color.TRANSPARENT);
        setScene(scene);

        // 圆角裁剪，防止子节点（列表高亮、滚动内容等）溢出圆角边界
        Rectangle clip = new Rectangle(0, 0, 720, 540);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        rootStackPane.setClip(clip);

        // 分类切换监听（初始分类在 showDialog 中延迟刷新，避免首次打开卡顿）
        categoryList.getSelectionModel().selectedItemProperty().addListener((_, _, cat) -> {
            if (cat != null) refreshCategory(cat);
        });
    }

    // ================================================================
    // 单例
    // ================================================================

    public static SettingsStage getInstance() {
        if (instance == null) {
            synchronized (SettingsStage.class) {
                if (instance == null) {
                    instance = new SettingsStage();
                }
            }
        }
        return instance;
    }

    // ================================================================
    // 公开方法
    // ================================================================

    /**
     * 打开设置面板，首次打开时延迟刷新分类内容避免卡顿
     */
    public void showDialog(StackPane ownerRoot) {
        showDialog(ownerRoot, null);
    }

    /**
     * 打开设置面板并选中指定分类
     */
    public void showDialog(StackPane ownerRoot, String selectCategoryName) {
        this.ownerRoot = ownerRoot;

        show();
        toFront();

        // 将窗口定位到主窗口所在屏幕的中心（支持多显示器）
        Window owner = ownerRoot.getScene() != null ? ownerRoot.getScene().getWindow() : null;
        if (owner != null) {
            Screen screen = Screen.getScreensForRectangle(owner.getX(), owner.getY(), 1, 1)
                    .stream().findFirst().orElse(null);
            if (screen != null) {
                setX(screen.getVisualBounds().getMinX()
                        + (screen.getVisualBounds().getWidth() - getWidth()) / 2);
                setY(screen.getVisualBounds().getMinY()
                        + (screen.getVisualBounds().getHeight() - getHeight()) / 2);
            }
        }

        if (needsInitialRefresh) {
            needsInitialRefresh = false;
            Platform.runLater(() -> {
                int idx = findCategoryIndex(selectCategoryName);
                categoryList.getSelectionModel().select(idx);
            });
        } else if (selectCategoryName != null) {
            int idx = findCategoryIndex(selectCategoryName);
            if (idx >= 0) {
                categoryList.getSelectionModel().select(idx);
            }
        } else {
            // 重新打开面板时强制刷新当前分类，重建 RoiPreview 等动态组件
            SettingCategory current = categoryList.getSelectionModel().getSelectedItem();
            if (current != null) {
                categoryList.getSelectionModel().clearSelection();
                Platform.runLater(() -> categoryList.getSelectionModel().select(current));
            }
        }
    }

    private int findCategoryIndex(String name) {
        if (name == null) return 0;
        ObservableList<SettingCategory> items = categoryList.getItems();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).name().equals(name)) return i;
        }
        return 0;
    }

    // ================================================================
    // 标题栏
    // ================================================================

    private HBox buildTitleBar() {
        HBox bar = new HBox();
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setCursor(Cursor.MOVE);
        bar.setPadding(new Insets(15, 15, 10, 20));

        titleLabel = new Label("设置");
        titleLabel.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 15px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button closeBtn = new Button();
        closeBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.FLAT);
        SVGPath closeIcon = new SVGPath();
        closeIcon.setContent("M1 1 L9 9 M9 1 L1 9");
        closeIcon.setStyle("-fx-stroke: -color-fg-default; -fx-stroke-width: 2;");
        closeBtn.setGraphic(closeIcon);
        closeBtn.setStyle("-fx-cursor: hand; -fx-padding: 8;");
        closeBtn.setOnAction(_ -> handleClose());

        bar.getChildren().addAll(titleLabel, spacer, closeBtn);
        return bar;
    }

    // ================================================================
    // 分类列表
    // ================================================================

    private ListView<SettingCategory> buildCategoryList() {
        ListView<SettingCategory> lv = new ListView<>();
        lv.setPrefWidth(180);
        lv.setMaxWidth(180);
        lv.setMinWidth(140);
        lv.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; " +
                "-fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent; -fx-hbar-policy: never;");
        lv.setFocusTraversable(false);
        lv.setItems(FXCollections.observableArrayList(
                SettingDefinitions.CATEGORIES.stream()
                        .filter(c -> VersionManager.getInstance().getCurrentMode() == VersionMode.ADVANCED
                                || !"物资面板".equals(c.name()))
                        .toList()));
        lv.setCellFactory(_ -> new SettingCategoryCell());
        return lv;
    }

    // ================================================================
    // 右侧面板刷新
    // ================================================================

    private void refreshCategory(SettingCategory category) {
        configManager.clearControls();

        // 停止上一个玩家预览（如有）
        if (playerPreview != null) {
            playerPreview.stop();
            playerPreview = null;
        }

        // 停止上一个 ROI 预览并关闭全帧模式
        if (currentRoiPreview != null) {
            currentRoiPreview.stop();
            currentRoiPreview = null;
        }
        if (captureService != null) {
            captureService.setFullFrameMode(false);
        }

        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        SettingFieldBuilder fieldBuilder = new SettingFieldBuilder(configManager, rootStackPane);
        VBox content = new VBox(6);
        content.setPadding(new Insets(5, 10, 10, 10));

        // 两栏布局：匹配分类配置项较多，使用 GridPane 两列排列
        boolean twoColumns = "匹配".equals(category.name());

        // 过滤掉 ROI 坐标字段（已显示在右侧面板中）
        String roiPrefix = "匹配".equals(category.name()) ? "ROI_MAP_"
                : "OCR".equals(category.name()) ? "ROI_OCR_" : null;
        List<SettingDef> fields = roiPrefix != null
                ? category.fields().stream().filter(d -> !d.key().startsWith(roiPrefix)).toList()
                : category.fields();

        if (twoColumns) {
            GridPane grid = new GridPane();
            grid.setHgap(20);
            grid.setVgap(6);
            grid.setPadding(new Insets(5, 10, 10, 10));
            ColumnConstraints cc = new ColumnConstraints();
            cc.setPercentWidth(50);
            grid.getColumnConstraints().addAll(cc, cc);

            for (int i = 0; i < fields.size(); i++) {
                SettingDef def = fields.get(i);
                int col = i % 2;
                int row = i / 2;

                Label label = new Label(def.label());
                label.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");
                if (def.restartRequired()) {
                    label.setText(label.getText() + " *");
                    label.setStyle(label.getStyle() + " -fx-text-fill: -color-warning-fg;");
                }

                Node control = fieldBuilder.buildControl(def);
                if (control instanceof Control c) {
                    configManager.registerControl(def.key(), c);
                }

                HBox container = new HBox();
                container.setAlignment(Pos.CENTER_LEFT);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                container.getChildren().addAll(label, spacer, control);
                grid.add(container, col, row);
            }
            scroll.setContent(grid);
        } else {

            for (SettingDef def : fields) {
                // 标签
                Label label = new Label(def.label());
                label.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");
                if (def.restartRequired()) {
                    label.setText(label.getText() + " *");
                    label.setStyle(label.getStyle() + " -fx-text-fill: -color-warning-fg;");
                }

                Node control = fieldBuilder.buildControl(def);
                if (control instanceof Control c) {
                    configManager.registerControl(def.key(), c);
                }

                // 标签左对齐，控件右对齐
                HBox container = new HBox();
                container.setAlignment(Pos.CENTER_LEFT);
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                container.getChildren().addAll(label, spacer, control);
                content.getChildren().add(container);

                // 分隔线
                if (def != fields.getLast()) {
                    Region sep = new Region();
                    sep.setStyle("-fx-border-color: -color-border-muted; -fx-border-width: 0 0 0.5 0;");
                    sep.setPrefHeight(1);
                    content.getChildren().add(sep);
                }
            }

            scroll.setContent(content);

        } // end else (单列布局)

        // 「玩家」分类：预览固定在顶部，不随列表滚动
        if ("玩家".equals(category.name())) {
            playerPreview = new PlayerPreview(configManager);
            playerPreview.start();

            VBox container = new VBox();
            container.getChildren().addAll(playerPreview.getNode(), scroll);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            rightPanel.getChildren().setAll(container);
        } else if ("匹配".equals(category.name()) || "OCR".equals(category.name())) {
            // ROI 分类：左侧截帧预览 + 右侧 ROI 坐标参数（万分比）
            boolean isMatch = "匹配".equals(category.name());
            int roiIdx = isMatch ? 0 : 1;
            String prefix = isMatch ? "ROI_MAP_" : "ROI_OCR_";
            Color accent = isMatch ? Color.rgb(0, 160, 255, 0.8) : Color.rgb(0, 200, 80, 0.8);
            String title = "预览";

            RoiPreview roiPreview = new RoiPreview(roiIdx, title, accent);
            roiPreview.setOwnerStage(this);
            roiPreview.start();
            // 拖拽调整 ROI 后同步刷新右侧 Spinner 控件
            roiPreview.setOnRoiChanged(() -> configManager.syncRoiControls(prefix));

            // 全帧模式：显示完整窗口画面 + ROI 矩形叠加
            roiPreview.setFullFrameMode(true, prefix);
            currentRoiPreview = roiPreview;
            if (captureService != null) {
                captureService.setFullFrameMode(true);
            }

            // 右侧 ROI 坐标参数面板
            VBox roiParamPanel = new VBox(8);
            roiParamPanel.setPadding(new Insets(8, 10, 8, 10));
            roiParamPanel.setPrefWidth(260);
            roiParamPanel.setMinWidth(200);
            roiParamPanel.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 6; " +
                    "-fx-border-color: -color-border-muted; -fx-border-radius: 6; -fx-border-width: 0.5;");

            Label roiParamTitle = new Label("ROI 坐标 (万分比)");
            roiParamTitle.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 12px;");
            roiParamPanel.getChildren().add(roiParamTitle);

            for (SettingDef def : category.fields()) {
                if (!def.key().startsWith(prefix)) continue;
                Label l = new Label(def.label());
                l.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 12px;");
                Node ctrl = fieldBuilder.buildControl(def);
                if (ctrl instanceof Control c) {
                    configManager.registerControl(def.key(), c);
                    if (c instanceof Spinner<?> sp) {
                        sp.getValueFactory().valueProperty().addListener((_, _, newVal) -> {
                            if (newVal != null) configManager.writeField(def.key(), newVal);
                        });
                        sp.getEditor().textProperty().addListener((_, _, _) -> {
                            try {
                                configManager.writeField(def.key(),
                                        Integer.parseInt(sp.getEditor().getText()));
                            } catch (NumberFormatException ignored) {
                            }
                        });
                    }
                }
                HBox row = new HBox(8);
                row.setAlignment(Pos.CENTER_LEFT);
                row.getChildren().addAll(l, ctrl);
                roiParamPanel.getChildren().add(row);
            }

            HBox topRow = new HBox(12);
            HBox.setHgrow(roiPreview.getNode(), Priority.ALWAYS);
            topRow.getChildren().addAll(roiPreview.getNode(), roiParamPanel);

            VBox container = new VBox();
            container.getChildren().addAll(topRow, scroll);
            VBox.setVgrow(scroll, Priority.ALWAYS);
            rightPanel.getChildren().setAll(container);
        } else {
            rightPanel.getChildren().setAll(scroll);
        }
    }

    // ================================================================
    // 操作
    // ================================================================

    private void doApply() {
        List<String> restartFields = configManager.applyChanges();
        if (!restartFields.isEmpty()) {
            String msg = "以下配置项需要重启程序才能生效：\n" + String.join("\n", restartFields);
            if (ownerRoot != null) {
                DialogUtils.showSimpleDialog(rootStackPane, "需要重启", msg, "确定", false, () -> {
                });
            }
        }
    }

    private void cleanupPreview() {
        if (currentRoiPreview != null) {
            currentRoiPreview.stop();
            currentRoiPreview = null;
        }
        if (captureService != null) {
            captureService.setFullFrameMode(false);
        }
        System.gc();
    }

    private void handleClose() {
        cleanupPreview();

        if (configManager.isModified()) {
            if (ownerRoot != null) {
                DialogUtils.showConfirmDialog(rootStackPane, "未保存的更改",
                        "有未保存的更改，是否放弃？",
                        "放弃",
                        this::hide, () -> {
                        });
            } else {
                hide();
            }
        } else {
            hide();
        }
    }
}
