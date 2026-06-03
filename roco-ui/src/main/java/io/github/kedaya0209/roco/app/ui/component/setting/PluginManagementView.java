package io.github.kedaya0209.roco.app.ui.component.setting;

import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.ui.component.dialog.PluginUpdateDialog;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginStatus;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.*;

/**
 * 插件管理页面 — 显示已安装插件列表、状态、启用/禁用、更新操作。
 */
@Slf4j
public class PluginManagementView {

    private final VBox root;
    private final VBox pluginList;
    private final Label statusLabel;
    private final Button checkBtn;

    /** 插件 ID → 卡片 HBox，用于更新进度 */
    private final Map<String, HBox> cardMap = new HashMap<>();

    /** 插件 ID → 运行状态指示标签 */
    private final Map<String, Label> runningLabels = new HashMap<>();

    /** 当前正在下载的插件 ID 集合，用于检测下载完成 */
    private final Set<String> downloadingPlugins = new HashSet<>();

    /** 上次检测到的缓存版本号，用于判断是否需要自动刷新 */
    private int lastCacheVersion = -1;

    /** 弹窗根容器（设置面板的 rootStackPane），用于显示确认弹窗 */
    @Setter
    private StackPane dialogRoot;

    public PluginManagementView() {
        this.root = new VBox(10);
        this.root.setPadding(new Insets(5, 10, 10, 10));

        // 顶部工具栏：状态提示 + 检查更新按钮
        HBox toolbar = new HBox(12);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        checkBtn = new Button("检查更新");
        checkBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
        checkBtn.setStyle("-fx-cursor: hand;");
        checkBtn.setPrefWidth(100);
        FxRippleUtil.install(checkBtn);
        checkBtn.setOnAction(_ -> {
            checkBtn.setDisable(true);
            checkBtn.setText("检查中...");
            PluginUpdateManager mgr = PluginUpdateManager.getInstance();
            mgr.setOnCheckComplete(() -> Platform.runLater(() -> {
                refresh(false);
                checkBtn.setText("检查更新");
                checkBtn.setDisable(false);
            }));
            mgr.scanPlugins();
            mgr.checkAllPlugins(true);
        });

        toolbar.getChildren().addAll(statusLabel, spacer, checkBtn);

        // 插件列表
        pluginList = new VBox(8);
        pluginList.setFillWidth(true);

        root.getChildren().addAll(toolbar, pluginList);

        // 进度轮询（每 100ms 刷新卡片背景）
        Timeline progressPoller = new Timeline(
                new KeyFrame(Duration.millis(100), _ -> updateProgressDisplay()));
        progressPoller.setCycleCount(Animation.INDEFINITE);
        progressPoller.play();
    }

    public VBox getNode() {
        return root;
    }

    /**
     * 刷新插件列表。
     */
    public void refresh() {
        refresh(false);
    }

    private void refresh(boolean rescan) {
        pluginList.getChildren().clear();
        cardMap.clear();
        runningLabels.clear();

        PluginUpdateManager mgr = PluginUpdateManager.getInstance();
        List<PluginInfo> plugins = rescan ? mgr.scanPlugins() : mgr.getCachedPlugins();

        if (plugins.isEmpty()) {
            Label empty = new Label("未发现插件，请将插件解压到 plugins/ 目录下");
            empty.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
            empty.setPadding(new Insets(20, 0, 0, 0));
            pluginList.getChildren().add(empty);
            statusLabel.setText("未安装任何插件");
            return;
        }

        long normalCount = plugins.stream().filter(p -> p.status() == PluginStatus.NORMAL).count();
        long updateCount = plugins.stream().filter(p -> p.status() == PluginStatus.HAS_UPDATE).count();
        long damagedCount = plugins.stream().filter(p -> p.status() == PluginStatus.DAMAGED).count();
        statusLabel.setText(String.format("共 %d 个插件 | %d 正常 | %d 可更新 | %d 异常",
                plugins.size(), normalCount, updateCount, damagedCount));

        Accordion accordion = new Accordion();
        accordion.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; " +
                "-fx-background-insets: 0; -fx-border-width: 0;");
        for (PluginInfo plugin : plugins) {
            accordion.getPanes().add(createPluginCard(plugin));
        }
        pluginList.getChildren().add(accordion);
    }

    /** 轮询插件下载进度 + 运行状态，更新卡片 */
    private void updateProgressDisplay() {
        PluginUpdateManager mgr = PluginUpdateManager.getInstance();

        // 检测文件变更，自动刷新
        int ver = mgr.getCacheVersion();
        if (ver != lastCacheVersion) {
            lastCacheVersion = ver;
            refresh();
            return;
        }

        boolean needRefresh = false;
        for (Map.Entry<String, HBox> entry : cardMap.entrySet()) {
            String pluginId = entry.getKey();
            // 下载进度
            double p = mgr.getDownloadProgress(pluginId);
            if (p > 0 && p < 1) {
                updateCardStyle(entry.getValue(), p);
                downloadingPlugins.add(pluginId);
            } else {
                // 不在下载中，重置背景
                updateCardStyle(entry.getValue(), 0);
                if (downloadingPlugins.remove(pluginId)) {
                    // 之前正在下载，现在完成了 → 刷新卡片内容
                    needRefresh = true;
                }
            }
            // 运行状态
            Label rl = runningLabels.get(pluginId);
            if (rl != null) {
                boolean running = mgr.isPluginRunning(pluginId);
                rl.setVisible(running);
                rl.setManaged(running);
            }
        }
        if (needRefresh) {
            Platform.runLater(this::refresh);
        }
    }

    /** 用蓝色线性渐变填充卡片背景表示进度 */
    private static void updateCardStyle(HBox card, double progress) {
        String base = "-fx-background-radius: 8; -fx-border-color: -color-border-muted; -fx-border-radius: 8; -fx-border-width: 0.5;";
        if (progress <= 0) {
            card.setStyle("-fx-background-color: -color-bg-inset; " + base);
        } else {
            String pct = String.format("%.1f%%", progress * 100);
            card.setStyle("-fx-background-color: linear-gradient(to right, rgba(42,125,225,0.25) 0%, rgba(42,125,225,0.25) " + pct + ", -color-bg-inset " + pct + ", -color-bg-inset 100%); " + base);
        }
    }

    private TitledPane createPluginCard(PluginInfo plugin) {
        // 自定义箭头（替代 TitledPane 默认箭头，放在 card 内部）
        Label arrowLabel = new Label("▶");
        arrowLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px; -fx-cursor: hand;");
        StackPane arrowWrapper = new StackPane(arrowLabel);
        arrowWrapper.setPrefWidth(16);
        arrowWrapper.setPrefHeight(16);
        arrowWrapper.setRotate(0);

        // 主行 — 作为 TitledPane 的 graphic
        HBox card = new HBox(12);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 8; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 8; -fx-border-width: 0.5;");

        // 插件图标（优先加载 icon 文件，回退到首字母占位）
        Node iconNode;
        File iconFile = plugin.icon() != null && !plugin.icon().isEmpty()
                ? new File(plugin.pluginDir(), plugin.icon()) : null;
        if (iconFile != null && iconFile.isFile()) {
            try {
                if (plugin.icon().toLowerCase().endsWith(".svg")) {
                    iconNode = SvgManager.createIconFromFile(iconFile, 36);
                } else {
                    ImageView iv = new ImageView(new Image(iconFile.toURI().toString()));
                    iv.setFitWidth(36);
                    iv.setFitHeight(36);
                    iconNode = iv;
                }
            } catch (Exception e) {
                iconNode = createLetterIcon(plugin);
            }
        } else {
            iconNode = createLetterIcon(plugin);
        }

        // 插件信息
        VBox info = new VBox(3);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);

        Label nameLabel = new Label(plugin.title() + "  (" + plugin.id() + ")");
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -color-fg-default;");

        Label versionLabel = new Label("v" + plugin.version());
        versionLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        info.getChildren().addAll(nameLabel, versionLabel);

        // 启用/禁用复选框
        CheckBox enableCb = new CheckBox();
        enableCb.setSelected(!PluginUpdateManager.getInstance().isPluginDisabled(plugin.id()));
        enableCb.setStyle("-fx-cursor: hand;");
        enableCb.selectedProperty().addListener((_, _, sel) -> {
            PluginUpdateManager.getInstance().setPluginEnabled(plugin.id(), sel);
            log.info("插件 {} 已{}", plugin.id(), sel ? "启用" : "禁用");
        });

        // 状态徽章
        Label badge = createStatusBadge(plugin.status());

        // 操作按钮
        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);

        if (plugin.status() != PluginStatus.HAS_UPDATE) {
            actions.getChildren().add(badge);
        }

        // 运行状态指示
        Label runningLabel = new Label("● 运行中");
        runningLabel.setStyle("-fx-text-fill: #28c850; -fx-font-size: 11px; -fx-font-weight: bold;");
        runningLabel.setVisible(false);
        runningLabel.setManaged(false);
        actions.getChildren().add(runningLabel);
        runningLabels.put(plugin.id(), runningLabel);

        // 更新按钮（仅在 HAS_UPDATE 状态时显示）
        if (plugin.status() == PluginStatus.HAS_UPDATE) {
            Button updateBtn = getButton(plugin);
            actions.getChildren().add(updateBtn);
        }

        // 删除按钮
        StackPane deleteBtn = new StackPane();
        deleteBtn.setPrefSize(28, 28);
        deleteBtn.setStyle("-fx-cursor: hand; -fx-background-radius: 6;");
        Node deleteIcon = SvgManager.createIcon("/icon/delete.svg", 20, "-fx-fill: #e05050;");
        deleteBtn.getChildren().add(deleteIcon);
        deleteBtn.setOnMouseEntered(_ -> {
            deleteBtn.setStyle("-fx-background-color: #e05050; -fx-background-radius: 6; -fx-cursor: hand;");
            deleteBtn.getChildren().set(0, SvgManager.createIcon("/icon/delete.svg", 20, "-fx-fill: white;"));
        });
        deleteBtn.setOnMouseExited(_ -> {
            deleteBtn.setStyle("-fx-background-radius: 6; -fx-cursor: hand;");
            deleteBtn.getChildren().set(0, SvgManager.createIcon("/icon/delete.svg", 20, "-fx-fill: #e05050;"));
        });
        deleteBtn.setOnMouseClicked(_ -> {
            if (dialogRoot != null) {
                ConfirmDialog.showConfirmDialog(dialogRoot,
                        "卸载插件",
                        "确定要卸载「" + plugin.title() + "」吗？\n插件目录和下载缓存将被删除。",
                        "确认卸载",
                        () -> {
                            PluginUpdateManager.getInstance().uninstallPlugin(plugin.id());
                            refresh();
                        },
                        () -> {});
            } else {
                PluginUpdateManager.getInstance().uninstallPlugin(plugin.id());
                refresh();
            }
        });

        actions.getChildren().add(deleteBtn);

        card.getChildren().addAll(arrowWrapper, enableCb, iconNode, info, actions);
        cardMap.put(plugin.id(), card);

        // 详情面板（由 TitledPane 管理展开/折叠）
        VBox detailPanel = new VBox(4);
        detailPanel.setPadding(new Insets(10, 14, 14, 14)); // 缩进与图标对齐

        PluginUpdateDialog.getItem(plugin, detailPanel, createDetailRow("描述", plugin.description()), createDetailRow("入口", plugin.entry()), createDetailRow("仓库", plugin.source().repo()));
        if (!plugin.assets().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (var a : plugin.assets()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(a.remoteName());
            }
            detailPanel.getChildren().add(createDetailRow("文件", sb.toString()));
        }

        TitledPane pane = new TitledPane();
        pane.setText(null);
        pane.setGraphic(card);
        pane.setContent(detailPanel);
        pane.setExpanded(false);
        pane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0 0 8 0;");

        // 隐藏 TitledPane 默认箭头 + 覆盖 title/content 默认样式
        pane.skinProperty().addListener((_, _, sk) -> {
            if (sk != null) {
                Region arrowBtn = (Region) pane.lookup(".arrow-button");
                if (arrowBtn != null) {
                    arrowBtn.setVisible(false);
                    arrowBtn.setManaged(false);
                    arrowBtn.setPrefWidth(0);
                    arrowBtn.setPrefHeight(0);
                    arrowBtn.setMinWidth(0);
                    arrowBtn.setMinHeight(0);
                }
                Region titleRegion = (Region) pane.lookup(".title");
                if (titleRegion != null) {
                    titleRegion.setStyle(
                            "-fx-background-color: -color-bg-inset; " +
                                    "-fx-background-insets: 0; " +
                                    "-fx-background-radius: 8; " +
                                    "-fx-border-color: -color-border-muted; " +
                                    "-fx-border-radius: 8; " +
                                    "-fx-border-width: 0.5; " +
                                    "-fx-padding: 0; " +
                                    "-fx-alignment: center-left;"
                    );
                }
                Region contentRegion = (Region) pane.lookup(".content");
                if (contentRegion != null) {
                    contentRegion.setStyle(
                            "-fx-background-color: transparent; " +
                                    "-fx-background-insets: 0; " +
                                    "-fx-background-radius: 0; " +
                                    "-fx-border-color: transparent; " +
                                    "-fx-border-width: 0; " +
                                    "-fx-padding: 0;"
                    );
                }
            }
        });

        // 自定义箭头跟随展开/折叠旋转
        pane.expandedProperty().addListener((_, _, expanded) -> arrowWrapper.setRotate(expanded ? 90 : 0));

        return pane;
    }

    private Button getButton(PluginInfo plugin) {
        Button updateBtn = new Button("更新");
        updateBtn.setPrefHeight(26);
        updateBtn.setPadding(new Insets(0, 14, 0, 14));
        updateBtn.setStyle("-fx-cursor: hand; -fx-font-size: 12px; -fx-background-radius: 13; " +
                "-fx-border-radius: 13; -fx-border-width: 0; -fx-background-color: #2a7de1; " +
                "-fx-text-fill: white; -fx-font-weight: bold;");
        updateBtn.setOnAction(_ -> {
            updateBtn.setDisable(true);
            updateBtn.setText("...");
            PluginUpdateManager mgr = PluginUpdateManager.getInstance();
            String pid = plugin.id();
            mgr.setUpdateCompletionCallback(pid, message ->
                    Platform.runLater(() -> {
                        if (dialogRoot != null) {
                            ConfirmDialog.showSimpleDialog(dialogRoot, "插件更新", message,
                                    "确定", false, this::refresh);
                        }
                    }));
            mgr.startUpdate(pid);
        });
        return updateBtn;
    }

    /** 创建首字母占位图标 */
    private static Node createLetterIcon(PluginInfo plugin) {
        Label label = new Label(plugin.title().isEmpty()
                ? plugin.id().substring(0, 1).toUpperCase()
                : plugin.title().substring(0, 1).toUpperCase());
        label.setPrefSize(36, 36);
        label.setAlignment(Pos.CENTER);
        label.setStyle("-fx-background-color: -color-accent-subtle; -fx-background-radius: 6; " +
                "-fx-text-fill: -color-accent-fg; -fx-font-weight: bold; -fx-font-size: 16px;");
        return label;
    }

    private HBox createDetailRow(String label, String value) {
        return PluginUpdateDialog.getRow(label, value);
    }

    private Label createStatusBadge(PluginStatus status) {
        Label badge = new Label();
        badge.setPadding(new Insets(2, 10, 2, 10));
        badge.setStyle("-fx-background-radius: 10; -fx-font-size: 11px; -fx-font-weight: bold;");

        switch (status) {
            case NORMAL -> {
                badge.setText("正常");
                badge.setStyle(badge.getStyle() + "-fx-background-color: rgba(40, 200, 80, 0.2); -fx-text-fill: #28c850;");
            }
            case DISABLED -> {
                badge.setText("已禁用");
                badge.setStyle(badge.getStyle() + "-fx-background-color: rgba(150, 150, 150, 0.2); -fx-text-fill: #969696;");
            }
            case DAMAGED -> {
                badge.setText("异常");
                badge.setStyle(badge.getStyle() + "-fx-background-color: rgba(240, 80, 80, 0.2); -fx-text-fill: #f05050;");
            }
            case HAS_UPDATE -> {
                badge.setText("可更新");
                badge.setStyle(badge.getStyle() + "-fx-background-color: rgba(40, 160, 240, 0.2); -fx-text-fill: #28a0f0;");
            }
            default -> {
                badge.setText("未知");
                badge.setStyle(badge.getStyle() + "-fx-background-color: rgba(200, 180, 60, 0.2); -fx-text-fill: #c8b43c;");
            }
        }
        return badge;
    }
}
