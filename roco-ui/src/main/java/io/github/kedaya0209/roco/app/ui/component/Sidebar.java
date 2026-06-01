package io.github.kedaya0209.roco.app.ui.component;

import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.config.*;
import io.github.kedaya0209.roco.app.context.CameraContext;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.NavModeEvent;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.match.map.SwitchMapMatcher;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingsStage;
import io.github.kedaya0209.roco.app.ui.service.ui.ThemeManager;
import io.github.kedaya0209.roco.app.ui.util.DialogUtils;
import io.github.kedaya0209.roco.app.ui.util.RestartUtils;
import io.github.kedaya0209.roco.app.update.UpdateManager;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;

import java.io.IOException;

@NotThreadSafe
@Slf4j
public class Sidebar extends VBox {

    private final WikiUpdateManager wikiUpdater;
    private final ListView<SidebarItem> listView;
    private final ObservableList<SidebarItem> items = FXCollections.observableArrayList();
    private RouteManagerStage routeManagerStage;
    private SidebarItem.Category expandedCategory = null;
    private volatile boolean isAlgorithmLoading = false;
    @Setter
    private UiAnimator animator;
    @Setter
    private Runnable onShowVersionSelector;

    public Sidebar() {
        super(0);
        setPadding(new Insets(0, 0, 0, 0));
        setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-muted; -fx-border-width: 0 1 0 0;");

        // 标题
        Label title = new Label("系统设置");
        title.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TEXT_CAPTION);
        title.setStyle("-fx-text-fill: -color-fg-muted;");
        title.setPadding(new Insets(15, 15, 10, 15));

        // ListView
        listView = new ListView<>();
        listView.setStyle(
                "-fx-background-color: transparent; -fx-border-color: transparent;"
                        + "-fx-selection-bar: transparent; -fx-selection-bar-non-focused: transparent;"
                        + "-fx-padding: 0;");
        listView.setFocusTraversable(false);
        wikiUpdater = new WikiUpdateManager();
        wikiUpdater.checkAndShowProgress();

        buildItems();
        listView.setItems(items);
        listView.setCellFactory(_ -> new SidebarCell(
                () -> expandedCategory, this::onHeaderClick, this::onOptionClick));
        VBox.setVgrow(listView, Priority.ALWAYS);
        hideHScrollBar(listView);
        listView.skinProperty().addListener((_, _, sk) -> {
            if (sk != null) hideHScrollBar(listView);
        });

        getChildren().addAll(title, listView);

        // 绑定场景后预初始化 RouteManagerStage
        sceneProperty().addListener((_, _, scene) -> {
            if (scene != null) {
                Platform.runLater(this::initRouteManager);
            }
        });

        if (!DownloadConfig.INTERNAL_RESOURCE) {
            initWikiFooter();
        }
    }

    private void buildItems() {
        items.clear();

        items.add(new SidebarItem(SidebarItem.Type.ACTION, "设置",
                null, null, null, false, "/icon/settings.svg", this::openSettings));
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "版本切换",
                null, null, null, false, "/icon/change.svg", this::onVersionSwitchClick));

        // 分类菜单
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "匹配算法选择",
                SidebarItem.Category.ALGORITHM, SiftConfig.MAP_MATCHAER,
                null, false, "/icon/match.svg", null));
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "资源模式切换",
                SidebarItem.Category.RESOURCE,
                DownloadConfig.INTERNAL_RESOURCE ? "内置资源" : "WIKI资源",
                null, false, "/icon/resources.svg", null));
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "主题切换",
                SidebarItem.Category.THEME, UiConfig.THEME,
                null, false, "/icon/theme.svg", null));
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "视角跟随",
                SidebarItem.Category.NAVIGATION,
                NavigConfig.NAVIGATION_ENABLED ? "已开启" : "已关闭",
                null, false, "/icon/navigation.svg", null));

        // 匹配开关
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "匹配开关",
                SidebarItem.Category.MATCH,
                SiftConfig.SIFT_MATCHING_ENABLED ? "已开启" : "已关闭",
                null, false, "/icon/match_toggle.svg", null));

        // 操作项
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "路线管理",
                null, null, null, false, "/icon/route.svg", this::openRouteManager));
        items.add(new SidebarItem(SidebarItem.Type.WIKI, null, null, null, wikiUpdater));
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "检查更新",
                null, null, null, false, "/icon/update.svg", () -> {
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent("正在检查更新，请稍候...", NotificationType.INFO));
            UpdateManager.getInstance().manualCheck(null);
        }));
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "插件管理",
                null, null, null, false, "/icon/plugins.svg",
                () -> openSettingsCategory("插件管理")));
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "关于",
                null, null, null, false, "/icon/about.svg", this::openAboutDialog));
    }

    // ══════════ Header / Option 处理 ══════════

    private void onVersionSwitchClick() {
        if (onShowVersionSelector != null) onShowVersionSelector.run();
    }

    private void onHeaderClick(SidebarItem item) {
        if (item.category() == SidebarItem.Category.MATCH) return;
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
            case NAVIGATION -> new String[]{"启用视角跟随", "打开导航设置"};
            case MATCH -> new String[]{};
        };

        String currentValue = item.currentValue();
        int insertIndex = items.indexOf(item) + 1;
        for (int i = 0; i < options.length; i++) {
            boolean selected = options[i].equalsIgnoreCase(currentValue)
                    || (item.category() == SidebarItem.Category.NAVIGATION
                    && options[i].startsWith(currentValue != null && currentValue.contains("开启") ? "关闭" : "启用"));
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
            case MATCH -> {
            }
        }
        collapseCurrent();
    }

    private void handleNavOption(String value, SidebarItem header) {
        switch (value) {
            case "启用视角跟随" -> {
                boolean enabled = !NavigConfig.NAVIGATION_ENABLED;
                NavigConfig.NAVIGATION_ENABLED = enabled;
                CameraContext.getInstance().setNavMode(enabled);
                HookRegistry.INSTANCE.publish(HookEventType.NAV_MODE_CHANGED, new NavModeEvent(enabled));
                AppEvents.publish(NavModeEvent.class, new NavModeEvent(enabled));
                updateHeaderValue(header, enabled ? "已开启" : "已关闭");
                closeSidebarAfterDelay();
            }
            case "打开导航设置" -> openSettingsCategory("视角跟随");
        }
    }

    private void closeSidebarAfterDelay() {
        Platform.runLater(() -> {
            if (animator != null) animator.closeSidebar();
        });
    }

    private void collapseCurrent() {
        if (expandedCategory == null) return;
        items.removeIf(i -> i.type() == SidebarItem.Type.OPTION && i.category() == expandedCategory);
        expandedCategory = null;
    }

    // ══════════ 算法/资源/主题 切换 ══════════

    private void switchAlgorithm(String algo, SidebarItem header) {
        if (isAlgorithmLoading) return;
        isAlgorithmLoading = true;
        updateHeaderValue(header, algo);

        Thread.ofPlatform().daemon(true).name("sidebar-switch-algo").start(() -> {
            try {
                SwitchMapMatcher.getInstance().switchMapMatcher(algo);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("正在重启匹配引擎: " + algo + " ...", NotificationType.INFO));
            } catch (Exception e) {
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
                "立即重启",
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

    // ══════════ 工具方法 ══════════

    private void updateHeaderValue(SidebarItem header, String newValue) {
        int idx = items.indexOf(header);
        if (idx >= 0) {
            items.set(idx, new SidebarItem(SidebarItem.Type.HEADER, header.title(),
                    header.category(), newValue, null, false, header.iconSvg(), null));
        }
    }

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

    // ══════════ Dialogs / Windows ══════════

    private void openSettings() {
        openSettingsCategory(null);
        initRouteManager();
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

    private void openRouteManager() {
        StackPane rootPane = initRouteManager();
        if (rootPane == null) return;
        if (routeManagerStage.isShowing()) {
            routeManagerStage.toFront();
        } else {
            routeManagerStage.setX(rootPane.getScene().getWindow().getX() + 250);
            routeManagerStage.setY(rootPane.getScene().getWindow().getY() + 100);
            routeManagerStage.show();
        }
    }

    private StackPane initRouteManager() {
        StackPane rootPane = findRootPane();
        if (rootPane == null) return null;
        if (routeManagerStage == null) {
            routeManagerStage = RouteManagerStage.getInstance(rootPane);
            routeManagerStage.initOwner(rootPane.getScene().getWindow());
        }
        return rootPane;
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

    // ══════════ 页脚 ══════════

    private void initWikiFooter() {
        VBox footer = new VBox(2);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(8, 0, 0, 0));

        Hyperlink wikiLink = new Hyperlink("数据来源：洛克王国WIKI");
        String linkStyle = "-fx-text-fill: -color-fg-subtle; -fx-font-size: 10px;"
                + "-fx-underline: true; -fx-border-color: transparent; -fx-padding: 0;";
        wikiLink.setStyle(linkStyle);
        wikiLink.setOnMouseEntered(e -> wikiLink.setStyle(
                "-fx-text-fill: -color-fg-muted; -fx-font-size: 10px;"
                        + "-fx-underline: true; -fx-border-color: transparent; -fx-padding: 0;"));
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

    private static void hideHScrollBar(ListView<?> lv) {
        Platform.runLater(() -> {
            for (Node hbar : lv.lookupAll(".scroll-bar:horizontal")) {
                hbar.setStyle("-fx-pref-height: 0; -fx-min-height: 0; -fx-max-height: 0;");
            }
        });
    }

    // ══════════ Item Model ══════════

    private StackPane findRootPane() {
        Node node = this;
        while ((node = node.getParent()) != null) {
            if (node instanceof StackPane sp) return sp;
        }
        return null;
    }

    @ThreadSafe
    public record SidebarItem(Type type, String title, Category category, String currentValue,
                              WikiUpdateManager wikiUpdater, boolean selected, String iconSvg,
                              Runnable onAction, double progress) {

        public SidebarItem(Type type, String title, Category category, String currentValue,
                           WikiUpdateManager wikiUpdater, boolean selected, String iconSvg,
                           Runnable onAction) {
            this(type, title, category, currentValue, wikiUpdater, selected, iconSvg, onAction, -1);
        }

        public SidebarItem(Type type, String title, Category category, String currentValue,
                           WikiUpdateManager wikiUpdater) {
            this(type, title, category, currentValue, wikiUpdater, false, null, null, -1);
        }

        public SidebarItem(Type type, String title, Category category, String currentValue,
                           WikiUpdateManager wikiUpdater, boolean selected) {
            this(type, title, category, currentValue, wikiUpdater, selected, null, null, -1);
        }

        @ThreadSafe
        enum Type {HEADER, OPTION, ACTION, WIKI}

        @ThreadSafe
        enum Category {ALGORITHM, RESOURCE, THEME, NAVIGATION, MATCH}
    }

}
