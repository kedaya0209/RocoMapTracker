package io.github.kedaya0209.roco.app.ui.component.setting;

import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.capture.FullFrameControl;
import io.github.kedaya0209.roco.app.ui.component.setting.strategy.*;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.ui.ThemeManager;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
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
import javafx.stage.StageStyle;
import javafx.stage.Window;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final List<SettingCategory> categoryItems = new ArrayList<>();
    private SettingCategory selectedCategory;
    private StackPane ownerRoot;
    private Button applyBtn;
    private Label titleLabel;
    private final Map<String, CategoryRenderer> rendererMap = new HashMap<>();
    private CategoryRenderer currentRenderer;
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

        // 监听外部匹配状态变更，同步设置面板的 CheckBox
        AppState.getInstance().matchingEnabledProperty().addListener((_, _, now) -> {
            Control ctrl = configManager.getControl("SIFT_MATCHING_ENABLED");
            if (ctrl instanceof CheckBox cb) {
                cb.setSelected(now);
            }
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

        // 搜索框
        TextField searchField = new TextField();
        searchField.setPromptText("搜索设置...");
        searchField.getStyleClass().add("settings-search-field");
        searchField.setPrefWidth(180);
        VBox.setMargin(searchField, new Insets(0, 0, 5, 0));

        // 分类列表（带 FilteredList 支持搜索过滤）
        FilteredList<SettingCategory> filteredCategories = buildCategoryList();
        categoryList = new ListView<>(filteredCategories);
        categoryList.setFocusTraversable(false);
        categoryList.setPrefWidth(180);
        categoryList.setMaxWidth(180);
        categoryList.setMinWidth(140);
        categoryList.setCellFactory(_ -> new SettingCategoryCell());
        categoryList.setStyle("-fx-background-color: transparent; -fx-border-color: transparent;");

        categoryList.getSelectionModel().selectedItemProperty().addListener((_, _, selected) -> {
            if (selected != null) {
                selectedCategory = selected;
                refreshCategory(selected);
            }
        });

        // 注册分类渲染策略
        rendererMap.put("玩家", new PlayerCategoryRenderer());
        rendererMap.put("匹配", new RoiCategoryRenderer());
        rendererMap.put("OCR", new RoiCategoryRenderer());
        rendererMap.put("插件管理", new PluginCategoryRenderer());

        // 搜索过滤
        searchField.textProperty().addListener((_, _, text) -> {
            String t = text.toLowerCase().trim();
            filteredCategories.setPredicate(cat -> {
                if (t.isEmpty()) return true;
                if (cat.name().toLowerCase().contains(t)) return true;
                return cat.fields().stream().anyMatch(f ->
                        f.label().toLowerCase().contains(t) ||
                        f.key().toLowerCase().contains(t));
            });
            if (!filteredCategories.isEmpty() && !filteredCategories.contains(selectedCategory)) {
                categoryList.getSelectionModel().selectFirst();
            }
        });

        // 左侧面板：搜索框 + 分类列表
        VBox leftPanel = new VBox(searchField, categoryList);
        VBox.setVgrow(categoryList, Priority.ALWAYS);
        leftPanel.setPrefWidth(180);

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

        body.getChildren().addAll(leftPanel, rightPanel);
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
            boolean needsRestart = doApply();
            cleanupPreview();
            if (!needsRestart) {
                hide();
            }
        });

        buttonBar.getChildren().addAll(cancelBtn, applyBtn, okBtn);

        // --- 组装 ---
        root.getChildren().addAll(titleBar, body, buttonBar);
        rootStackPane.getChildren().add(root);

        Scene scene = new Scene(rootStackPane, 720, 540);
        scene.setFill(Color.TRANSPARENT);

        // 加载主题 CSS，否则 -color-bg-default 等变量无法解析 → 背景透明
        String themeCss = ThemeManager.getCurrentStylesheetUrl();
        if (themeCss != null) {
            scene.getStylesheets().add(themeCss);
        }
        java.net.URL uiCss = getClass().getResource("/styles/ui.css");
        if (uiCss != null) {
            scene.getStylesheets().add(uiCss.toExternalForm());
        }

        setScene(scene);

        // 注册主题变更监听，使设置面板跟随全局主题切换
        ThemeManager.addThemeChangeListener(() -> {
            Scene sc = getScene();
            if (sc == null) return;
            String url = ThemeManager.getCurrentStylesheetUrl();
            if (url == null) return;
            sc.getStylesheets().removeIf(u -> u != null && u.contains("atlantafx"));
            sc.getStylesheets().add(url);
            sc.getRoot().applyCss();
        });

        // Native Image 下 StageStyle.TRANSPARENT 窗口不会自动获得焦点
        setOnShown(_ -> { requestFocus(); toFront(); });
        scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, _ -> {
            if (!isFocused()) requestFocus();
        });

        // 圆角裁剪，防止子节点（列表高亮、滚动内容等）溢出圆角边界
        Rectangle clip = new Rectangle(0, 0, 720, 540);
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        rootStackPane.setClip(clip);

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
            Platform.runLater(() -> selectIndex(findCategoryIndex(selectCategoryName)));
        } else if (selectCategoryName != null) {
            int idx = findCategoryIndex(selectCategoryName);
            if (idx >= 0) selectIndex(idx);
        } else {
            // 重新打开面板时强制刷新当前分类，重建 RoiPreview 等动态组件
            if (selectedCategory != null) {
                refreshCategory(selectedCategory);
            }
        }
    }

    private int findCategoryIndex(String name) {
        if (name == null) return 0;
        for (int i = 0; i < categoryItems.size(); i++) {
            if (categoryItems.get(i).name().equals(name)) return i;
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
    // 分类列表（ListView + AtlanFX 主题）
    // ================================================================

    private FilteredList<SettingCategory> buildCategoryList() {
        List<SettingCategory> items = SettingDefinitions.CATEGORIES.stream()
                .filter(c -> VersionManager.getInstance().getCurrentMode() == VersionMode.ADVANCED
                        || !"物资面板".equals(c.name()))
                .toList();
        categoryItems.clear();
        categoryItems.addAll(items);
        ObservableList<SettingCategory> observableItems = FXCollections.observableArrayList(items);
        return new FilteredList<>(observableItems, _ -> true);
    }

    private void selectCategory(SettingCategory cat) {
        categoryList.getSelectionModel().select(cat);
    }

    private void selectIndex(int index) {
        categoryList.getSelectionModel().select(index);
    }

    // ================================================================
    // 右侧面板刷新
    // ================================================================

    private void refreshCategory(SettingCategory category) {
        configManager.clearControls();

        // 清理上一个渲染策略
        if (currentRenderer != null) {
            currentRenderer.onHide(captureService);
            currentRenderer = null;
        }

        SettingFieldBuilder fieldBuilder = new SettingFieldBuilder(configManager, rootStackPane);
        ScrollPane scroll = buildFieldScrollPane(category, fieldBuilder);

        CategoryRenderer renderer = rendererMap.getOrDefault(category.name(), defaultRenderer);
        rightPanel.getChildren().setAll(
                renderer.render(category, scroll, configManager, fieldBuilder,
                        rootStackPane, this, captureService));
        currentRenderer = renderer;
    }

    private static final CategoryRenderer defaultRenderer = new DefaultCategoryRenderer();

    private ScrollPane buildFieldScrollPane(SettingCategory category, SettingFieldBuilder fieldBuilder) {
        ScrollPane scroll = new ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);

        // 过滤掉 ROI 坐标字段（已由 RoiCategoryRenderer 在独立面板中显示）
        String roiPrefix = "匹配".equals(category.name()) ? "ROI_MAP_"
                : "OCR".equals(category.name()) ? "ROI_OCR_" : null;
        List<SettingDef> fields = roiPrefix != null
                ? category.fields().stream().filter(d -> !d.key().startsWith(roiPrefix)).toList()
                : category.fields();

        // 按子分类分组：subcategory → List<SettingDef>，空子分类的字段放在 "general" 组
        Map<String, List<SettingDef>> groups = new LinkedHashMap<>();
        groups.put("", new ArrayList<>()); // 无子分类的字段
        for (SettingDef def : fields) {
            String sub = def.subcategory();
            String groupKey = (sub == null || sub.isEmpty()) ? "" : sub;
            groups.computeIfAbsent(groupKey, _ -> new ArrayList<>()).add(def);
        }

        VBox outerContent = new VBox(10);
        outerContent.setPadding(new Insets(5, 10, 10, 10));

        for (var entry : groups.entrySet()) {
            String sub = entry.getKey();
            List<SettingDef> groupFields = entry.getValue();
            if (groupFields.isEmpty()) continue;

            // 如果只有一个子分类（或无子分类），用原有布局方式，不显示分组标题
            boolean singleGroup = groups.size() == 1;

            if (!singleGroup && !sub.isEmpty()) {
                // 子分类标题
                Label sectionTitle = new Label(sub);
                sectionTitle.setStyle("-fx-text-fill: -color-fg-default; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 8 0 4 0;");
                outerContent.getChildren().add(sectionTitle);
            }

            if (!singleGroup && sub.isEmpty()) {
                // 未归类的字段放在首位但不显示标题
            }

            { // 统一单栏布局
                VBox content = new VBox(6);
                for (int i = 0; i < groupFields.size(); i++) {
                    SettingDef def = groupFields.get(i);
                    content.getChildren().add(buildFieldRow(def, fieldBuilder));

                    if (i < groupFields.size() - 1) {
                        Region sep = new Region();
                        sep.setStyle("-fx-border-color: -color-border-muted; -fx-border-width: 0 0 0.5 0;");
                        sep.setPrefHeight(1);
                        content.getChildren().add(sep);
                    }
                }
                outerContent.getChildren().add(content);
            }
        }

        scroll.setContent(outerContent);
        return scroll;
    }

    private HBox buildFieldRow(SettingDef def, SettingFieldBuilder fieldBuilder) {
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
        return container;
    }

    // ================================================================
    // 操作
    // ================================================================

    /**
     * @return true 如果有配置变更需要重启才能生效
     */
    private boolean doApply() {
        List<String> restartFields = configManager.applyChanges();
        if (!restartFields.isEmpty()) {
            String msg = "以下配置项需要重启程序才能生效：\n" + String.join("\n", restartFields);
            ConfirmDialog.showSimpleDialog(rootStackPane, "需要重启", msg, "确定", false, () -> {});
            return true;
        }
        return false;
    }

    private void cleanupPreview() {
        if (currentRenderer != null) {
            currentRenderer.onHide(captureService);
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
                ConfirmDialog.showConfirmDialog(rootStackPane, "未保存的更改",
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
