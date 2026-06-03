package io.github.kedaya0209.roco.app.ui.component.sidebar;

import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.config.*;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.match.map.SwitchMapMatcher;
import io.github.kedaya0209.roco.app.ui.component.dialog.AboutDialog;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingsStage;
import io.github.kedaya0209.roco.app.ui.component.widget.RouteManagerStage;
import io.github.kedaya0209.roco.app.ui.service.ui.ThemeManager;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
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
    private final CheckUpdateManager checkUpdateMgr;
    private final ListView<SidebarItem> listView;
    private final ObservableList<SidebarItem> items = FXCollections.observableArrayList();
    private RouteManagerStage routeManagerStage;
    private SidebarItem.Category expandedCategory = null;
    private final SidebarActionHandler actionHandler = new SidebarActionHandler();
    @Setter
    private StackPane rootStack;
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
        checkUpdateMgr = new CheckUpdateManager();

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
                ViewportState.getInstance().isNavMode() ? "已开启" : "已关闭",
                null, false, "/icon/navigation.svg", null));

        // 匹配开关
        items.add(new SidebarItem(SidebarItem.Type.HEADER, "匹配开关",
                SidebarItem.Category.MATCH,
                AppState.getInstance().isMatchingEnabled() ? "已开启" : "已关闭",
                null, false, "/icon/match_toggle.svg", null));

        // 操作项
        items.add(new SidebarItem(SidebarItem.Type.ACTION, "路线管理",
                null, null, null, false, "/icon/route.svg", this::openRouteManager));
        items.add(new SidebarItem(SidebarItem.Type.WIKI, null, null, null, wikiUpdater));
        items.add(new SidebarItem(SidebarItem.Type.WIKI, null, null, null, checkUpdateMgr));
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
            case ALGORITHM -> actionHandler.switchAlgorithm(value,
                    () -> updateHeaderValue(header, value), this::closeSidebarAfterDelay);
            case RESOURCE -> actionHandler.switchResource(value, findRootPane());
            case THEME -> actionHandler.switchTheme(value,
                    () -> updateHeaderValue(header, value));
            case NAVIGATION -> {
                if ("打开导航设置".equals(value)) {
                    openSettingsCategory("视角跟随");
                } else {
                    actionHandler.handleNavToggle(
                            newVal -> updateHeaderValue(header, newVal),
                            this::closeSidebarAfterDelay);
                }
            }
            case MATCH -> {
            }
        }
        collapseCurrent();
    }

    private void collapseCurrent() {
        if (expandedCategory == null) return;
        items.removeIf(i -> i.type() == SidebarItem.Type.OPTION && i.category() == expandedCategory);
        expandedCategory = null;
    }

    private void closeSidebarAfterDelay() {
        Platform.runLater(() -> {
            if (animator != null) animator.closeSidebar();
        });
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
        Platform.runLater(() -> checkUpdateMgr.setProgress(progress));
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
        AboutDialog.showAboutDialog(rootPane,
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
        if (rootStack != null) return rootStack;
        Node node = this;
        while ((node = node.getParent()) != null) {
            if (node instanceof StackPane sp) return sp;
        }
        return null;
    }

    @ThreadSafe
    public record SidebarItem(Type type, String title, Category category, String currentValue,
                              SidebarComponent wikiUpdater, boolean selected, String iconSvg,
                              Runnable onAction, double progress) {

        SidebarItem(Type type, String title, Category category, String currentValue,
                    SidebarComponent wikiUpdater, boolean selected, String iconSvg,
                    Runnable onAction) {
            this(type, title, category, currentValue, wikiUpdater, selected, iconSvg, onAction, -1);
        }

        SidebarItem(Type type, String title, Category category, String currentValue,
                    SidebarComponent wikiUpdater) {
            this(type, title, category, currentValue, wikiUpdater, false, null, null, -1);
        }

        SidebarItem(Type type, String title, Category category, String currentValue,
                    SidebarComponent wikiUpdater, boolean selected) {
            this(type, title, category, currentValue, wikiUpdater, selected, null, null, -1);
        }

        @ThreadSafe
        enum Type {HEADER, OPTION, ACTION, WIKI}

        @ThreadSafe
        enum Category {ALGORITHM, RESOURCE, THEME, NAVIGATION, MATCH}
    }

}
