package com.luoke.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.DownloadConfig;
import com.luoke.app.config.NavigConfig;
import com.luoke.app.config.UiConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.config.BuildConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.ui.component.setting.SettingsStage;
import com.luoke.app.ui.service.SvgManager;
import com.luoke.app.ui.service.ThemeManager;
import com.luoke.app.update.UpdateManager;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.FxRippleUtil;
import com.luoke.app.ui.util.RestartUtils;
import java.io.IOException;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@NotThreadSafe
@Slf4j
public class Sidebar extends VBox {

    private final WikiUpdateManager wikiUpdater;
    private final ListView<SidebarItem> listView;
    private final ObservableList<SidebarItem> items = FXCollections.observableArrayList();
    private RouteManagerStage routeManagerStage;
    // 当前展开状态
    private SidebarItem.Category expandedCategory = null;
    private volatile boolean isAlgorithmLoading = false;
    @Setter
    private UiAnimator animator;

    public Sidebar() {
        super(0);
        setPadding(new Insets(15, 15, 15, 15));
        setPrefWidth(270);
        setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-muted; -fx-border-width: 0 1 0 0;");

        // 标题
        Label title = new Label("系统设置");
        title.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TEXT_CAPTION);
        title.setStyle("-fx-text-fill: -color-fg-muted;");
        title.setPadding(new Insets(0, 0, 10, 5));

        // ListView
        listView = new ListView<>();
        listView.setPrefWidth(240);
        listView.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent; -fx-hbar-policy: never;");
        listView.setFocusTraversable(false);

        wikiUpdater = new WikiUpdateManager();
        wikiUpdater.checkAndShowProgress();

        buildItems();
        listView.setItems(items);
        listView.setCellFactory(lv -> new SidebarCell());

        // 让 ListView 自动填满可用高度
        VBox.setVgrow(listView, Priority.ALWAYS);

        getChildren().addAll(title, listView);

        if (!DownloadConfig.INTERNAL_RESOURCE) {
            initWikiFooter();
        }
    }

    private void buildItems() {
        items.clear();

        // 设置按钮（置顶）
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "设置", null, null, null, false, "/icon/settings.svg", this::openSettings));

        // 匹配算法选择
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "匹配算法选择",
                SidebarItem.Category.ALGORITHM, SiftConfig.MAP_MATCHAER, null, false, "/icon/match.svg", null));

        // 资源模式切换
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "资源模式切换",
                SidebarItem.Category.RESOURCE,
                DownloadConfig.INTERNAL_RESOURCE ? "内置资源" : "WIKI资源", null, false, "/icon/resources.svg", null));

        // 主题切换
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "主题切换",
                SidebarItem.Category.THEME, UiConfig.THEME, null, false, "/icon/theme.svg", null));

        // 视角跟随模式
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "视角跟随",
                SidebarItem.Category.NAVIGATION, NavigConfig.NAVIGATION_ENABLED ? "已开启" : "已关闭", null, false, "/icon/navigation.svg", null));

        // 匹配开关
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "匹配开关",
                SidebarItem.Category.MATCH, SiftConfig.SIFT_MATCHING_ENABLED ? "已开启" : "已关闭", null, false, "/icon/match_toggle.svg", null));

        // 路线管理
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "路线管理", null, null, null, false, "/icon/route.svg", this::openRouteManager));

        // 检查更新
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "检查更新", null, null, null, false, "/icon/update.svg", () -> {
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent("正在检查更新，请稍候...", NotificationType.INFO));
            UpdateManager.getInstance().manualCheck(null);
        }));

        // 关于
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "关于", null, null, null, false, "/icon/about.svg", this::openAboutDialog));

        // WIKI 更新（特殊容器）
        items.add(new SidebarItem(SidebarItem.Type.WIKI, null, null, null, wikiUpdater));
    }

    private void onHeaderClick(SidebarItem item) {
        if (item.category() == SidebarItem.Category.MATCH) return; // Switch 控件直接控制，不展开
        if (expandedCategory == item.category()) {
            collapseCurrent();
            return;
        }
        collapseCurrent();
        expandedCategory = item.category();
        String[] options = switch (item.category()) {
            case ALGORITHM -> SwitchMapMatcher.getInstance().getMatchers().toArray(new String[0]);
            case RESOURCE -> ResourceConfigContext.getTags().toArray(new String[0]);
            case THEME -> ThemeManager.getAvailableThemes();
            case NAVIGATION -> new String[]{
                "启用视角跟随",
                "打开导航设置"
            };
            case MATCH -> new String[]{}; // 不会被执行（早期 return 保护）
        };

        String currentValue = item.currentValue();
        int insertIndex = items.indexOf(item) + 1;
        for (int i = 0; i < options.length; i++) {
            boolean selected = options[i].equalsIgnoreCase(currentValue);
            items.add(insertIndex + i,
                    new SidebarItem(SidebarItem.Type.OPTION, options[i],
                            item.category(), null, null, selected));
        }
    }

    private void onOptionClick(SidebarItem option) {
        String value = option.title();
        SidebarItem.Category cat = option.category();
        SidebarItem header = items.stream()
                .filter(i -> i.type() == SidebarItem.Type.HEADER && i.category() == cat)
                .findFirst().orElse(null);
        if (header == null) return;

        switch (cat) {
            case ALGORITHM -> switchAlgorithm(value, header);
            case RESOURCE -> switchResource(value, header);
            case THEME -> switchTheme(value, header);
            case NAVIGATION -> handleNavOption(value, header);
            case MATCH -> {} // Switch 控件直接控制
        }
        collapseCurrent();
    }

    private void handleNavOption(String value, SidebarItem header) {
        switch (value) {
            case "启用视角跟随" -> {
                boolean enabled = !NavigConfig.NAVIGATION_ENABLED;
                NavigConfig.NAVIGATION_ENABLED = enabled;
                CameraContext.getInstance().setNavMode(enabled);
                TitleBar.getInstance().setNavModeFromExternal(enabled);
                updateHeaderValue(header, enabled ? "已开启" : "已关闭");
                closeSidebarAfterDelay();
            }
            case "打开导航设置" -> openSettingsCategory("视角跟随");
        }
    }

    private void closeSidebarAfterDelay() {
        Platform.runLater(() -> {
            if (animator != null) {
                animator.closeSidebar();
            }
        });
    }

    private void collapseCurrent() {
        if (expandedCategory == null) return;
        items.removeIf(i -> i.type() == SidebarItem.Type.OPTION && i.category() == expandedCategory);
        expandedCategory = null;
    }

    private void switchAlgorithm(String algo, SidebarItem header) {
        if (isAlgorithmLoading) return;

        isAlgorithmLoading = true;
        updateHeaderValue(header, algo);

        Thread.ofPlatform().daemon(true).name("sidebar-switch-algo").start(() -> {
            try {
                SwitchMapMatcher.getInstance().switchMapMatcher(algo);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("正在重启匹配引擎: " + algo + " ...", NotificationType.INFO));
            } catch (Exception e) { // SwitchMapMatcher 涉及原生库调用，可能抛出多种异常
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("切换算法失败", NotificationType.ERROR));
            } finally {
                Platform.runLater(() -> isAlgorithmLoading = false);
            }
        });
    }

    private void switchResource(String resource, SidebarItem header) {
        boolean isInternal = resource.equals("内置资源");
        if (isInternal == DownloadConfig.INTERNAL_RESOURCE) return;

        DialogUtils.showConfirmDialog(
                findRootPane(),
                "模式切换",
                "切换资源模式需要重启程序生效，是否继续？",
                () -> {
                    DownloadConfig.INTERNAL_RESOURCE = isInternal;
                    ConfigPersistence.save();
                    RestartUtils.restart();
                },
                () -> {
                }
        );
    }

    private void switchTheme(String name, SidebarItem header) {
        updateHeaderValue(header, name);
        ThemeManager.switchTheme(name);
        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                new StatusEvent("主题已切换: " + name, NotificationType.SUCCESS));
    }

    private void updateHeaderValue(SidebarItem header, String newValue) {
        int idx = items.indexOf(header);
        if (idx >= 0) {
            items.set(idx, new SidebarItem(SidebarItem.Type.HEADER, header.title(),
                    header.category(), newValue, null, false, header.iconSvg(), null));
        }
    }

    private void openSettings() {
        openSettingsCategory(null);
    }

    private void openSettingsCategory(String categoryName) {
        StackPane rootPane = findRootPane();
        if (rootPane == null) return;

        SettingsStage settingsStage = SettingsStage.getInstance();
        if (settingsStage.getOwner() == null && rootPane.getScene() != null) {
            settingsStage.initOwner(rootPane.getScene().getWindow());
        }
        settingsStage.showDialog(rootPane, categoryName);
    }

    /**
     * 更新"检查更新"项显示下载进度（后台下载模式）
     * @param progress -1 表示隐藏进度，0~1 表示下载百分比
     */
    public void setDownloadProgress(double progress) {
        Platform.runLater(() -> {
            for (int i = 0; i < items.size(); i++) {
                SidebarItem item = items.get(i);
                if (!"检查更新".equals(item.title())) continue;
                String title = progress < 0 ? "检查更新"
                        : String.format("检查更新 (%.0f%%)", progress * 100);
                items.set(i, new SidebarItem(SidebarItem.Type.ACTION, title,
                        null, null, null, false, "/icon/update.svg",
                        item.onAction(), progress));
                return;
            }
        });
    }

    private void openRouteManager() {
        StackPane rootPane = findRootPane();
        if (rootPane == null) return;

        if (routeManagerStage == null) {
            routeManagerStage = RouteManagerStage.getInstance(rootPane);
            routeManagerStage.initOwner(rootPane.getScene().getWindow());
        }

        if (routeManagerStage.isShowing()) {
            routeManagerStage.toFront();
        } else {
            routeManagerStage.setX(rootPane.getScene().getWindow().getX() + 250);
            routeManagerStage.setY(rootPane.getScene().getWindow().getY() + 100);
            routeManagerStage.show();
        }
    }

    private void openAboutDialog() {
        StackPane rootPane = findRootPane();
        if (rootPane == null) return;
        DialogUtils.showAboutDialog(rootPane,
                BuildConfig.APP_NAME,
                BuildConfig.APP_VERSION,
                BuildConfig.BUILD_TIMESTAMP,
                "https://github.com/kedaya0209/RocoMapTracker");
    }

    private void initWikiFooter() {
        VBox footer = new VBox(2);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(8, 0, 0, 0));

        Hyperlink wikiLink = new Hyperlink("数据来源：洛克王国WIKI");
        String linkStyle = "-fx-text-fill: -color-fg-subtle; -fx-font-size: 10px; -fx-underline: true; -fx-border-color: transparent; -fx-padding: 0;";
        wikiLink.setStyle(linkStyle);
        wikiLink.setOnMouseEntered(e -> wikiLink.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 10px; -fx-underline: true; -fx-border-color: transparent; -fx-padding: 0;"));
        wikiLink.setOnMouseExited(e -> wikiLink.setStyle(linkStyle));
        wikiLink.setOnAction(e -> {
            openWebpage("https://wiki.biligame.com/rocom/");
            wikiLink.setVisited(false);
        });

        Label copyrightLabel = new Label("仅供学习交流使用");
        copyrightLabel.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 9px;");

        footer.getChildren().addAll(wikiLink, copyrightLabel);
        getChildren().add(footer);
    }

    private void openWebpage(String url) {
        try {
            new ProcessBuilder("cmd", "/c", "start", url).start();
        } catch (IOException e) {
            log.error("跳转失败", e);
        }
    }

    // ========== Item Model ==========

    private StackPane findRootPane() {
        Node node = this;
        while ((node = node.getParent()) != null) {
            if (node instanceof StackPane sp) return sp;
        }
        return null;
    }

    // ========== Item Model ==========

    public record SidebarItem(Type type, String title, Category category, String currentValue,
                              WikiUpdateManager wikiUpdater, boolean selected, String iconSvg, Runnable onAction,
                              double progress) {

        public SidebarItem(Type type, String title, Category category, String currentValue,
                           WikiUpdateManager wikiUpdater, boolean selected, String iconSvg,
                           Runnable onAction) {
            this(type, title, category, currentValue, wikiUpdater, selected, iconSvg, onAction, -1);
        }

        public SidebarItem(Type type, String title, Category category, String currentValue, WikiUpdateManager wikiUpdater) {
            this(type, title, category, currentValue, wikiUpdater, false, null, null, -1);
        }

        public SidebarItem(Type type, String title, Category category, String currentValue, WikiUpdateManager wikiUpdater, boolean selected) {
            this(type, title, category, currentValue, wikiUpdater, selected, null, null, -1);
        }

        enum Type {HEADER, OPTION, ACTION, WIKI}

        enum Category {ALGORITHM, RESOURCE, THEME, NAVIGATION, MATCH}
    }

    // ========== Cell ==========

    private class SidebarCell extends ListCell<SidebarItem> {
        private static final String BG_STYLE = "-fx-background-color: -color-bg-subtle; -fx-background-radius: 6;";
        private static final String BG_HOVER = "-fx-background-color: -color-bg-inset; -fx-background-radius: 6;";

        {
            // 禁止 ListView 选中时的默认矩形背景
            setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
        }

        @Override
        protected void updateItem(SidebarItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
                setPadding(new Insets(0));
                setOnMouseClicked(null);
                return;
            }
            switch (item.type()) {
                case HEADER -> renderHeader(item);
                case OPTION -> renderOption(item);
                case ACTION -> renderAction(item);
                case WIKI -> renderWiki(item);
            }
        }

        // ── Header ──────────────────────────────────────────

        private void renderHeader(SidebarItem item) {
            Node icon = SvgManager.createHoverDrawIcon(item.iconSvg(), 18, 1.5, 400);

            Label title = new Label(item.title());
            title.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");

            // 匹配开关 — 使用 AtlanFX ToggleSwitch
            if (item.category() == SidebarItem.Category.MATCH) {
                renderMatchToggle(item, icon, title);
                return;
            }

            Label value = new Label(item.currentValue());
            value.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px;");
            value.setPadding(new Insets(0, 4, 0, 0));

            SVGPath arrow = new SVGPath();
            arrow.setContent("M7 10l5 5 5-5z");
            arrow.setStyle("-fx-fill: -color-fg-default;");
            arrow.setRotate(expandedCategory == item.category() ? 180 : 0);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox row = new HBox(6, icon, title, spacer, value, arrow);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPrefHeight(38);
            row.setPadding(new Insets(0, 12, 0, 12));
            row.setStyle(BG_STYLE);
            row.setMouseTransparent(true);  // 事件由 Cell 处理，row 只做显示

            // hover: 在 cell 上监听 (图标画线 + 行背景)
            setOnMouseEntered(e -> {
                row.setStyle(BG_HOVER);
                SvgManager.animateHoverDrawIcon(icon, true, 400);
            });
            setOnMouseExited(e -> {
                row.setStyle(BG_STYLE);
                SvgManager.animateHoverDrawIcon(icon, false, 400);
            });
            setCursor(Cursor.HAND);

            setOnMouseClicked(e -> {
                animateArrow(arrow, expandedCategory == item.category());
                onHeaderClick(item);
            });

            StackPane wrapper = new StackPane(row);
            wrapper.setPadding(new Insets(2, 0, 2, 0));
            wrapper.setMouseTransparent(true);
            setPadding(new Insets(0));
            setGraphic(wrapper);
        }

        // ── Match Toggle ────────────────────────────────────

        private void renderMatchToggle(SidebarItem item, Node icon, Label title) {
            ToggleSwitch toggle = new ToggleSwitch();
            toggle.setSelected(SiftConfig.SIFT_MATCHING_ENABLED);
            toggle.selectedProperty().addListener((obs, oldVal, newVal) -> {
                SiftConfig.SIFT_MATCHING_ENABLED = newVal;
                TitleBar.getInstance().publishMatchToggleEvent();
            });

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            HBox row = new HBox(6, icon, title, spacer, toggle);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPrefHeight(38);
            row.setPadding(new Insets(0, 12, 0, 12));
            row.setStyle(BG_STYLE);

            setOnMouseEntered(e -> {
                row.setStyle(BG_HOVER);
                SvgManager.animateHoverDrawIcon(icon, true, 400);
            });
            setOnMouseExited(e -> {
                row.setStyle(BG_STYLE);
                SvgManager.animateHoverDrawIcon(icon, false, 400);
            });

            StackPane wrapper = new StackPane(row);
            wrapper.setPadding(new Insets(2, 0, 2, 0));
            setPadding(new Insets(0));
            setGraphic(wrapper);
        }

        // ── Option ──────────────────────────────────────────

        private void renderOption(SidebarItem item) {
            Label title = new Label(item.title());
            title.setStyle(item.selected()
                    ? "-fx-text-fill: -color-success-emphasis; -fx-font-size: 12px; -fx-font-weight: bold;"
                    : "-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

            HBox row = new HBox(title);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPrefHeight(28);
            row.setPadding(new Insets(0, 0, 0, 20));
            row.setMouseTransparent(true);

            if (item.selected()) {
                row.setStyle("-fx-background-color: -color-success-subtle; -fx-background-radius: 4;");
            } else {
                setOnMouseEntered(e -> row.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 4;"));
                setOnMouseExited(e -> row.setStyle(""));
            }
            setCursor(Cursor.HAND);
            setOnMouseClicked(e -> onOptionClick(item));

            StackPane wrapper = new StackPane(row);
            wrapper.setPadding(new Insets(1, 0, 1, 0));
            wrapper.setMouseTransparent(true);
            setPadding(new Insets(0));
            setGraphic(wrapper);

            // 展开动画：淡入
            wrapper.setOpacity(0);
            FadeTransition ft = new FadeTransition(Duration.millis(150), wrapper);
            ft.setToValue(1);
            ft.play();
        }

        // ── Action（按钮样式，无箭头）────────────────────────────

        private void renderAction(SidebarItem item) {
            boolean isProgress = item.progress() >= 0;

            setOnMouseClicked(null);
            setOnMouseEntered(null);
            setOnMouseExited(null);
            setCursor(Cursor.DEFAULT);

            Node icon = SvgManager.createHoverDrawIcon(item.iconSvg(), 18, 1.5, 400);

            Label text = new Label(item.title());
            if (isProgress) {
                text.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
            } else {
                text.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");
            }

            HBox content = new HBox(8, icon, text);
            content.setAlignment(Pos.CENTER_LEFT);

            Button btn = new Button();
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setPrefHeight(36);
            btn.setAlignment(Pos.BASELINE_LEFT);
            btn.setStyle("-fx-background-color: -color-bg-subtle; -fx-text-fill: -color-fg-default; -fx-background-radius: 6; -fx-font-size: 13px; -fx-cursor: hand; -fx-padding: 0 12 0 12; -fx-effect: none; -fx-background-insets: 0;");
            btn.setGraphic(content);

            if (isProgress) {
                // 进度模式：纯展示，无交互
                btn.setMouseTransparent(true);
                btn.setCursor(Cursor.DEFAULT);
            } else {
                FxRippleUtil.install(btn);
                btn.setOnMouseEntered(e -> SvgManager.animateHoverDrawIcon(icon, true, 400));
                btn.setOnMouseExited(e -> SvgManager.animateHoverDrawIcon(icon, false, 400));
                if (item.onAction() != null) {
                    btn.setOnAction(e -> item.onAction().run());
                }
            }

            Pane wrapper = new Pane() {
                @Override
                protected void layoutChildren() {
                    double w = getWidth();
                    double h = getHeight();
                    if (w > 0 && h > 0) {
                        btn.resizeRelocate(0, 0, w, h);
                    }
                }
            };
            wrapper.getChildren().add(btn);
            setPadding(new Insets(2, 0, 2, 0));
            setGraphic(wrapper);
        }

        // ── Wiki ────────────────────────────────────────────

        private void renderWiki(SidebarItem item) {
            setOnMouseClicked(null);
            setOnMouseEntered(null);
            setOnMouseExited(null);
            setCursor(Cursor.DEFAULT);

            setPadding(new Insets(2, 0, 2, 0));
            setGraphic(item.wikiUpdater().getContainer());
        }

        // ── 箭头动画 ─────────────────────────────────────────

        private void animateArrow(SVGPath arrow, boolean wasExpanded) {
            double from = wasExpanded ? 180 : 0;
            double to = wasExpanded ? 0 : 180;
            Timeline tl = new Timeline(
                    new KeyFrame(Duration.millis(200),
                            new KeyValue(arrow.rotateProperty(), to, javafx.animation.Interpolator.EASE_BOTH))
            );
            tl.play();
        }
    }
}
