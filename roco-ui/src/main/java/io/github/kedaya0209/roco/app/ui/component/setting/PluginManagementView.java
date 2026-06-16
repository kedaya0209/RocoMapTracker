package io.github.kedaya0209.roco.app.ui.component.setting;

import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.component.dialog.PluginUpdateDialog;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.PluginProcessRegistry;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;
import io.github.kedaya0209.roco.app.process.ProcessMonitor;
import io.github.kedaya0209.roco.app.update.plugin.PluginAsset;
import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginStatus;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
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

    /** 插件 ID → 运行状态指示标签（仅状态变化时更新避免闪烁） */
    private final Map<String, Label> runningLabels = new HashMap<>();

    /** 插件 ID → 上次运行状态，用于防闪烁比较 */
    private final Map<String, Boolean> lastRunningState = new HashMap<>();

    /** 插件 ID → 状态徽标，运行时隐藏"正常" */
    private final Map<String, Label> badgeLabels = new HashMap<>();

    /** 插件 ID → CPU 使用率标签 */
    private final Map<String, Label> cpuLabels = new HashMap<>();
    /** 插件 ID → 内存占用标签 */
    private final Map<String, Label> memLabels = new HashMap<>();

    /** 插件 ID → 下载进度标签 */
    private final Map<String, Label> progressLabels = new HashMap<>();

    /** 当前正在下载的插件 ID 集合，用于检测下载完成 */
    private final Set<String> downloadingPlugins = new HashSet<>();

    /** 上次检测到的缓存版本号，用于判断是否需要自动刷新 */
    private int lastCacheVersion = -1;

    /** CPU/内存采样节流 — 1s 间隔 */
    private long lastMonitorNs = 0;

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
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("检查完成", NotificationType.INFO));
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
        lastRunningState.clear();
        badgeLabels.clear();
        cpuLabels.clear();
        memLabels.clear();
        progressLabels.clear();

        PluginUpdateManager mgr = PluginUpdateManager.getInstance();
        List<PluginInfo> userPlugins = rescan ? mgr.scanPlugins() : mgr.getCachedPlugins();

        // 构建插件条目（内置 + 用户安装）
        List<PluginEntry> entries = new ArrayList<>();
        File placeholderDir = new File(".");
        entries.add(new PluginEntry(new PluginInfo("capture", "capture", "Capture Service",
                "1.0.0", "内置截图采集服务", PathConfig.CAPTURE_ICON_PATH, "", null, List.of(),
                PluginStatus.NORMAL, placeholderDir), true));
        entries.add(new PluginEntry(new PluginInfo("sift", "sift", "SIFT Match",
                "1.0.0", "内置 SIFT 匹配服务", PathConfig.MATCH_ICON_PATH, "", null, List.of(),
                PluginStatus.NORMAL, placeholderDir), true));
        for (PluginInfo p : userPlugins) {
            entries.add(new PluginEntry(p, false));
        }

        long normalCount = userPlugins.stream().filter(p -> p.status() == PluginStatus.NORMAL).count();
        long updateCount = userPlugins.stream().filter(p -> p.status() == PluginStatus.HAS_UPDATE).count();
        long damagedCount = userPlugins.stream().filter(p -> p.status() == PluginStatus.DAMAGED).count();
        statusLabel.setText(String.format("共 %d 个插件 | %d 正常 | %d 可更新 | %d 异常",
                entries.size(), normalCount + 2, updateCount, damagedCount));

        Accordion accordion = new Accordion();
        accordion.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0; " +
                "-fx-background-insets: 0; -fx-border-width: 0;");
        for (PluginEntry entry : entries) {
            accordion.getPanes().add(createPluginCard(entry.plugin(), entry.builtIn()));
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
            Label progressLabel = progressLabels.get(pluginId);
            if (p > 0 && p < 1) {
                updateCardStyle(entry.getValue(), p);
                downloadingPlugins.add(pluginId);
                if (progressLabel != null) {
                    progressLabel.setText(String.format("下载中 %.0f%%", p * 100));
                    progressLabel.setVisible(true);
                    progressLabel.setManaged(true);
                }
            } else {
                // 不在下载中，重置背景
                updateCardStyle(entry.getValue(), 0);
                if (progressLabel != null) {
                    progressLabel.setVisible(false);
                    progressLabel.setManaged(false);
                }
                if (downloadingPlugins.remove(pluginId)) {
                    // 之前正在下载，现在完成了 → 刷新卡片内容
                    needRefresh = true;
                }
            }
            // 运行状态（仅状态变化时更新，避免闪烁）
            Label rl = runningLabels.get(pluginId);
            if (rl != null) {
                boolean running = mgr.isPluginRunning(pluginId);
                Boolean prev = lastRunningState.get(pluginId);
                if (prev == null || prev != running) {
                    lastRunningState.put(pluginId, running);
                    rl.setVisible(running);
                    rl.setManaged(running);
                    Label badge = badgeLabels.get(pluginId);
                    if (badge != null) {
                        badge.setVisible(!running);
                        badge.setManaged(!running);
                    }
                }
            }
        }

        // CPU/内存监控（1s 节流）
        long now = System.nanoTime();
        if (now - lastMonitorNs >= 1_000_000_000L) {
            lastMonitorNs = now;
            Map<String, ProcessMonitor.Reading> readings = PluginProcessRegistry.sample();
            Set<String> updated = new HashSet<>();
            for (Map.Entry<String, ProcessMonitor.Reading> r : readings.entrySet()) {
                String id = r.getKey();
                updated.add(id);
                ProcessMonitor.Reading reading = r.getValue();
                Label cpuLbl = cpuLabels.get(id);
                Label memLbl = memLabels.get(id);
                if (cpuLbl != null) {
                    cpuLbl.setText(String.format("CPU: %.2f%%", reading.cpuPercent()));
                }
                if (memLbl != null) {
                    long kb = reading.memoryKB();
                    if (kb >= 10240) {
                        memLbl.setText(String.format("MEM: %d MB", kb / 1024));
                    } else {
                        memLbl.setText(String.format("MEM: %.1f MB", kb / 1024.0));
                    }
                }
            }
            // 进程已退出的插件重置为 "--"
            for (Map.Entry<String, Label> entry : cpuLabels.entrySet()) {
                if (!updated.contains(entry.getKey())) {
                    entry.getValue().setText("CPU: --");
                }
            }
            for (Map.Entry<String, Label> entry : memLabels.entrySet()) {
                if (!updated.contains(entry.getKey())) {
                    entry.getValue().setText("MEM: --");
                }
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

    /** 插件条目包装：插件元数据 + 是否是内置插件 */
    private record PluginEntry(PluginInfo plugin, boolean builtIn) {}

    // ==================== 构建方法（共用部件） ====================

    private static StackPane createArrowWrapper() {
        Label arrowLabel = new Label("▶");
        arrowLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px; -fx-cursor: hand;");
        StackPane wrapper = new StackPane(arrowLabel);
        wrapper.setPrefWidth(16);
        wrapper.setPrefHeight(16);
        return wrapper;
    }

    private static HBox createCardBase() {
        HBox card = new HBox(12);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 8; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 8; -fx-border-width: 0.5;");
        card.setEffect(new DropShadow(6, 0, 2, Color.rgb(0, 0, 0, 0.12)));
        return card;
    }

    private static VBox createInfoBox(PluginInfo plugin) {
        VBox info = new VBox(3);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label nameLabel = new Label(plugin.title() + "  (" + plugin.id() + ")");
        nameLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: -color-fg-default;");
        Label versionLabel = new Label("v" + plugin.version());
        versionLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        info.getChildren().addAll(nameLabel, versionLabel);
        return info;
    }

    private Label createCpuLabel(String pluginId) {
        Label label = new Label("CPU: --");
        label.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 10px;");
        cpuLabels.put(pluginId, label);
        return label;
    }

    private Label createMemLabel(String pluginId) {
        Label label = new Label("MEM: --");
        label.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 10px;");
        memLabels.put(pluginId, label);
        return label;
    }

    private static VBox createDetailPanel(PluginInfo plugin) {
        VBox panel = new VBox(4);
        panel.setPadding(new Insets(10, 14, 14, 14));
        String repo = plugin.source() != null ? plugin.source().repo() : "内置";
        PluginUpdateDialog.getItem(plugin, panel,
                PluginUpdateDialog.getRow("描述", plugin.description()),
                PluginUpdateDialog.getRow("入口", plugin.entry()),
                PluginUpdateDialog.getRow("仓库", repo));
        if (plugin.assets() != null && !plugin.assets().isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (PluginAsset a : plugin.assets()) {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(a.remoteName());
            }
            panel.getChildren().add(PluginUpdateDialog.getRow("文件", sb.toString()));
        }
        return panel;
    }

    private static void setupTitledPaneSkin(TitledPane pane) {
        pane.skinProperty().addListener((_, _, sk) -> {
            if (sk == null) return;
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
                titleRegion.setStyle("-fx-background-color: -color-bg-inset; -fx-background-insets: 0; " +
                        "-fx-background-radius: 8; -fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 8; -fx-border-width: 0.5; -fx-padding: 0; -fx-alignment: center-left;");
            }
            Region contentRegion = (Region) pane.lookup(".content");
            if (contentRegion != null) {
                contentRegion.setStyle("-fx-background-color: transparent; -fx-background-insets: 0; " +
                        "-fx-background-radius: 0; -fx-border-color: transparent; " +
                        "-fx-border-width: 0; -fx-padding: 0;");
            }
        });
    }

    private TitledPane createTitledPane(Node graphic, VBox detailPanel, StackPane arrowWrapper) {
        TitledPane pane = new TitledPane();
        pane.setText(null);
        pane.setGraphic(graphic);
        pane.setContent(detailPanel);
        pane.setExpanded(false);
        pane.setAnimated(false);
        pane.setStyle("-fx-background-color: transparent; -fx-border-color: transparent; -fx-padding: 0 0 8 0;");
        setupTitledPaneSkin(pane);
        pane.expandedProperty().addListener((_, _, expanded) -> arrowWrapper.setRotate(expanded ? 90 : 0));
        return pane;
    }

    // ==================== 内置插件卡片 ====================

    private TitledPane createBuiltInCard(PluginInfo plugin) {
        StackPane arrowWrapper = createArrowWrapper();
        HBox card = createCardBase();

        Node iconNode;
        if (plugin.icon() != null && !plugin.icon().isEmpty()
                && getClass().getResource(plugin.icon()) != null) {
            iconNode = SvgManager.createIcon(plugin.icon(), 36);
        } else {
            iconNode = createLetterIcon(plugin);
        }

        VBox info = createInfoBox(plugin);

        Label builtInBadge = new Label("内置");
        builtInBadge.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px; " +
                "-fx-background-color: -color-bg-inset; -fx-background-radius: 4; " +
                "-fx-border-color: -color-border-muted; -fx-border-radius: 4; -fx-border-width: 0.5; " +
                "-fx-padding: 2 8 2 8;");

        VBox rightBox = new VBox(2);
        rightBox.setAlignment(Pos.CENTER_RIGHT);
        rightBox.getChildren().addAll(builtInBadge, createCpuLabel(plugin.id()), createMemLabel(plugin.id()));

        card.getChildren().addAll(arrowWrapper, iconNode, info, rightBox);

        return createTitledPane(card, createDetailPanel(plugin), arrowWrapper);
    }

    // ==================== 用户插件卡片 ====================

    private TitledPane createPluginCard(PluginInfo plugin, boolean builtIn) {
        if (builtIn) return createBuiltInCard(plugin);

        StackPane arrowWrapper = createArrowWrapper();
        HBox card = createCardBase();

        Node iconNode;
        File iconFile = plugin.icon() != null && !plugin.icon().isEmpty()
                ? new File(plugin.pluginDir(), plugin.icon()) : null;
        if (iconFile != null && iconFile.isFile()) {
            try {
                iconNode = plugin.icon().toLowerCase().endsWith(".svg")
                        ? SvgManager.createIconFromFile(iconFile, 36)
                        : new ImageView(new Image(iconFile.toURI().toString()));
                if (iconNode instanceof ImageView iv) {
                    iv.setFitWidth(36);
                    iv.setFitHeight(36);
                }
            } catch (Exception e) {
                iconNode = createLetterIcon(plugin);
            }
        } else {
            iconNode = createLetterIcon(plugin);
        }

        VBox info = createInfoBox(plugin);

        Label progressLabel = new Label();
        progressLabel.setStyle("-fx-text-fill: #2a7de1; -fx-font-size: 11px; -fx-font-weight: bold;");
        progressLabel.setVisible(false);
        progressLabel.setManaged(false);
        info.getChildren().add(progressLabel);
        progressLabels.put(plugin.id(), progressLabel);

        CheckBox enableCb = new CheckBox();
        enableCb.setSelected(!PluginUpdateManager.getInstance().isPluginDisabled(plugin.id()));
        enableCb.setStyle("-fx-cursor: hand;");
        enableCb.selectedProperty().addListener((_, _, sel) -> {
            PluginUpdateManager.getInstance().setPluginEnabled(plugin.id(), sel);
            log.info("插件 {} 已{}", plugin.id(), sel ? "启用" : "禁用");
        });

        Label badge = createStatusBadge(plugin.status());
        badgeLabels.put(plugin.id(), badge);
        Label runningLabel = new Label("● 运行中");
        runningLabel.setStyle("-fx-text-fill: #28c850; -fx-font-size: 11px; -fx-font-weight: bold;");
        runningLabel.setVisible(false);
        runningLabel.setManaged(false);
        runningLabels.put(plugin.id(), runningLabel);

        VBox statusBox = new VBox(2);
        statusBox.setAlignment(Pos.CENTER_RIGHT);
        if (plugin.status() != PluginStatus.HAS_UPDATE) {
            statusBox.getChildren().add(badge);
        }
        statusBox.getChildren().addAll(runningLabel, createCpuLabel(plugin.id()), createMemLabel(plugin.id()));

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().add(statusBox);
        if (plugin.status() == PluginStatus.HAS_UPDATE) {
            actions.getChildren().add(createUpdateButton(plugin));
        }

        // 滑动揭示删除按钮
        StackPane swipeContainer = new StackPane();
        swipeContainer.setMaxWidth(Double.MAX_VALUE);
        swipeContainer.getChildren().add(createDeleteButton(plugin));
        card.getChildren().addAll(arrowWrapper, enableCb, iconNode, info, actions);
        swipeContainer.getChildren().add(card);

        installSwipeGesture(card);

        cardMap.put(plugin.id(), card);

        VBox detailPanel = createDetailPanel(plugin);

        TitledPane pane = createTitledPane(swipeContainer, detailPanel, arrowWrapper);
        pane.expandedProperty().addListener((_, _, expanded) -> {
            if (expanded && card.getTranslateX() < 0) {
                snapTranslate(card, 0);
            }
        });
        return pane;
    }

    private StackPane createDeleteButton(PluginInfo plugin) {
        StackPane deleteBtn = new StackPane();
        deleteBtn.setPrefSize(28, 28);
        deleteBtn.setMinSize(28, 28);
        deleteBtn.setMaxSize(28, 28);
        deleteBtn.setStyle("-fx-cursor: hand; -fx-background-radius: 6; -fx-background-color: -color-bg-inset;");
        Node deleteIconRed = SvgManager.createIcon("/icon/delete.svg", 20, "-fx-fill: #e05050;");
        Node deleteIconWhite = SvgManager.createIcon("/icon/delete.svg", 20, "-fx-fill: white;");
        deleteIconWhite.setVisible(false);
        deleteBtn.getChildren().addAll(deleteIconRed, deleteIconWhite);
        deleteBtn.setOnMousePressed(MouseEvent::consume);
        deleteBtn.setOnMouseEntered(_ -> {
            deleteBtn.setStyle("-fx-background-color: #e05050; -fx-background-radius: 6; -fx-cursor: hand;");
            deleteIconRed.setVisible(false);
            deleteIconWhite.setVisible(true);
        });
        deleteBtn.setOnMouseExited(_ -> {
            deleteBtn.setStyle("-fx-cursor: hand; -fx-background-radius: 6; -fx-background-color: -color-bg-inset;");
            deleteIconRed.setVisible(true);
            deleteIconWhite.setVisible(false);
        });
        deleteBtn.setOnMouseClicked(e -> {
            e.consume();
            Runnable uninstall = () -> {
                PluginUpdateManager.getInstance().uninstallPlugin(plugin.id());
                refresh();
            };
            if (dialogRoot != null) {
                ConfirmDialog.showConfirmDialog(dialogRoot,
                        "卸载插件",
                        "确定要卸载「" + plugin.title() + "」吗？\n插件目录和下载缓存将被删除。",
                        "确认卸载", uninstall, () -> {});
            } else {
                uninstall.run();
            }
        });
        StackPane.setAlignment(deleteBtn, Pos.CENTER_RIGHT);
        StackPane.setMargin(deleteBtn, new Insets(0, 14, 0, 0));
        return deleteBtn;
    }

    private static void installSwipeGesture(HBox card) {
        final boolean[] swiping = {false};
        final double[] startX = {0};
        final double[] startTx = {0};
        final double REVEAL_WIDTH = 46;
        final double SWIPE_THRESHOLD = 18;

        card.setOnMousePressed(e -> {
            startX[0] = e.getSceneX();
            startTx[0] = card.getTranslateX();
            swiping[0] = false;
            if (card.getTranslateX() < 0) e.consume();
        });

        card.setOnMouseDragged(e -> {
            double delta = e.getSceneX() - startX[0];
            if (!swiping[0] && Math.abs(delta) > 10) swiping[0] = true;
            if (swiping[0]) {
                double newTx = startTx[0] + delta;
                newTx = Math.min(newTx, 0);
                newTx = Math.max(newTx, -REVEAL_WIDTH);
                card.setTranslateX(newTx);
            }
        });

        card.setOnMouseReleased(e -> {
            if (swiping[0]) {
                e.consume();
                snapTranslate(card, card.getTranslateX() < -SWIPE_THRESHOLD ? -REVEAL_WIDTH : 0);
                return;
            }
            if (card.getTranslateX() < 0) {
                e.consume();
                snapTranslate(card, 0);
            }
        });

        card.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
            if (swiping[0] || card.getTranslateX() < 0) {
                e.consume();
                swiping[0] = false;
            }
        });
    }

    private Button createUpdateButton(PluginInfo plugin) {
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
            mgr.setUpdateCompletionCallback(plugin.id(), message ->
                    Platform.runLater(() -> {
                        if (dialogRoot != null) {
                            ConfirmDialog.showSimpleDialog(dialogRoot, "插件更新", message,
                                    "确定", false, this::refresh);
                        }
                    }));
            mgr.startUpdate(plugin.id());
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

    /** 滑动回弹/展开动画 */
    private static void snapTranslate(Node node, double targetX) {
        Timeline snap = new Timeline(
                new KeyFrame(Duration.millis(150),
                        new KeyValue(node.translateXProperty(), targetX, Interpolator.EASE_OUT))
        );
        snap.play();
    }
}
