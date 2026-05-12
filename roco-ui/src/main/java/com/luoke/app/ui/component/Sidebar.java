package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.ui.ModernCanvasApp;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.RestartUtils;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;

@Slf4j
public class Sidebar extends VBox {

    private StackPane btnContainer;
    private Button updateBtn;
    private ProgressBar progressBar;
    private Label progressLabel;
    private Label statusLabel;
    private RouteManagerStage routeManagerStage;

    private final double ITEM_HEIGHT = 38;

    private Button selectedAlgoBtn = null;
    private Button selectedResourceBtn = null;
    private Button selectedThemeBtn = null;
    private boolean isAlgorithmLoading = false;

    public Sidebar() {
        super(0);
        setPadding(new Insets(20, 15, 20, 15));
        setPrefWidth(240);
        setStyle("-fx-background-color: -color-bg-default; -fx-border-color: -color-border-muted; -fx-border-width: 0 1 0 0;");

        // 1. 标题
        Label title = new Label("系统设置");
        title.getStyleClass().addAll(Styles.TEXT_BOLD, Styles.TEXT_CAPTION);
        title.setStyle("-fx-text-fill: -color-fg-muted;");
        title.setPadding(new Insets(0, 0, 15, 5));

        VBox controlsGroup = new VBox(10);

        // 2. 匹配算法菜单
        VBox algoMenu = createExpandableMenu("匹配算法选择",
                SwitchMapMatcher.getInstance().getMatchers().toArray(new String[0]),
                AppConfig.MAP_MATCHAER,
                this::switchAlgorithm,
                true, false
        );
        VBox.setMargin(algoMenu, new Insets(8, 0, 0, 0));

        // 3. 资源模式菜单
        VBox resourceMenu = createExpandableMenu("资源模式切换",
                ResourceConfigContext.getTags().toArray(new String[0]),
                AppConfig.INTERNAL_RESOURCE ? "内置资源" : "WIKI资源",
                this::switchResource,
                false, false
        );

        // 4. 主题切换菜单
        VBox themeMenu = createExpandableMenu("主题切换",
                ModernCanvasApp.getAvailableThemes(),
                AppConfig.THEME,
                this::switchTheme,
                false, true
        );

        // 5. 路线管理模块
        VBox routeArea = createRouteArea();

        // 5. 更新按钮区域
        createProgressButton();

        controlsGroup.getChildren().addAll(algoMenu, resourceMenu, themeMenu, routeArea, btnContainer);

        Region bottomSpacer = new Region();
        VBox.setVgrow(bottomSpacer, Priority.ALWAYS);

        getChildren().addAll(title, controlsGroup, bottomSpacer);

        if (!AppConfig.INTERNAL_RESOURCE) {
            initWikiFooter();
        }
    }

    /**
     * 创建路径工具区域
     */
    private VBox createRouteArea() {
        VBox container = new VBox(0);

        Button routeBtn = new Button("路线管理");
        routeBtn.setMaxWidth(Double.MAX_VALUE);
        routeBtn.setPrefHeight(ITEM_HEIGHT);
        routeBtn.setStyle("-fx-background-color: -color-bg-subtle; -fx-text-fill: -color-fg-default; -fx-background-radius: 6;");

        routeBtn.setOnAction(e -> {
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
        });

        container.getChildren().addAll(routeBtn);
        return container;
    }

    private void initWikiFooter() {
        VBox footer = new VBox(2);
        footer.setAlignment(Pos.CENTER);
        footer.setPadding(new Insets(10, 0, 0, 0));

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
        } catch (Exception e) {
            log.error("跳转失败", e);
        }
    }

    private VBox createExpandableMenu(String title, String[] items, String defaultValue,
                                      BiConsumer<String, Button> onAction, boolean isAlgo, boolean isTheme) {
        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefHeight(ITEM_HEIGHT);
        header.setCursor(Cursor.HAND);
        header.setPadding(new Insets(0, 12, 0, 12));
        header.setStyle("-fx-background-color: -color-bg-subtle; -fx-background-radius: 6;");

        Label label = new Label(title);
        label.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        SVGPath arrow = new SVGPath();
        arrow.setContent("M7 10l5 5 5-5z");
        arrow.setStyle("-fx-fill: -color-fg-default;");
        header.getChildren().addAll(label, spacer, arrow);

        VBox content = new VBox(5);
        content.setPadding(new Insets(8, 0, 0, 0));
        content.setOpacity(0);
        content.setPrefHeight(0);
        content.setMinHeight(0);

        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(this.widthProperty());
        clip.heightProperty().bind(content.prefHeightProperty());
        content.setClip(clip);

        for (String itemName : items) {
            Button btn = new Button(itemName);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setPrefHeight(34);
            btn.setAlignment(Pos.CENTER_LEFT);
            btn.setPadding(new Insets(0, 0, 0, 15));
            btn.setCursor(Cursor.HAND);
            btn.setStyle("-fx-background-color: transparent; -fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

            if (itemName.equalsIgnoreCase(defaultValue)) {
                applySelectedStyle(btn);
                if (isAlgo) selectedAlgoBtn = btn;
                else if (isTheme) selectedThemeBtn = btn;
                else selectedResourceBtn = btn;
            }

            btn.setOnMouseEntered(e -> {
                if (!isAlgorithmLoading && !isCurrentlySelected(btn, isAlgo, isTheme)) {
                    btn.setStyle("-fx-background-color: -color-bg-inset; -fx-text-fill: -color-fg-default;");
                }
            });
            btn.setOnMouseExited(e -> {
                if (!isAlgorithmLoading && !isCurrentlySelected(btn, isAlgo, isTheme)) {
                    btn.setStyle("-fx-background-color: transparent; -fx-text-fill: -color-fg-muted;");
                }
            });

            btn.setOnAction(e -> onAction.accept(itemName, btn));
            content.getChildren().add(btn);
        }

        double expandedHeight = items.length * 39 + 8;
        Timeline animation = new Timeline();
        header.setOnMouseClicked(e -> {
            boolean opening = content.getPrefHeight() == 0;
            animation.stop();
            animation.getKeyFrames().setAll(
                    new KeyFrame(Duration.millis(250),
                            new KeyValue(content.prefHeightProperty(), opening ? expandedHeight : 0, javafx.animation.Interpolator.EASE_BOTH),
                            new KeyValue(content.opacityProperty(), opening ? 1 : 0, javafx.animation.Interpolator.EASE_BOTH),
                            new KeyValue(arrow.rotateProperty(), opening ? 180 : 0, javafx.animation.Interpolator.EASE_BOTH)
                    )
            );
            animation.play();
        });

        return new VBox(header, content);
    }

    private void applySelectedStyle(Button btn) {
        btn.setStyle("-fx-background-color: -color-success-subtle; -fx-text-fill: -color-success-emphasis;");
    }

    private boolean isCurrentlySelected(Button btn, boolean isAlgo, boolean isTheme) {
        if (isAlgo) return btn == selectedAlgoBtn;
        if (isTheme) return btn == selectedThemeBtn;
        return btn == selectedResourceBtn;
    }

    private void switchAlgorithm(String algo, Button clickedBtn) {
        if (isAlgorithmLoading) return;
        if (isCurrentlySelected(clickedBtn, true, false)) return;

        isAlgorithmLoading = true;
        if (selectedAlgoBtn != null) {
            selectedAlgoBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        }
        selectedAlgoBtn = clickedBtn;
        applySelectedStyle(clickedBtn);

        Thread.ofVirtual().start(() -> {
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

    private void switchTheme(String name, Button clickedBtn) {
        if (isCurrentlySelected(clickedBtn, false, true)) return;

        if (selectedThemeBtn != null) {
            selectedThemeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");
        }
        selectedThemeBtn = clickedBtn;
        applySelectedStyle(clickedBtn);

        ModernCanvasApp.switchTheme(name);
        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                new StatusEvent("主题已切换: " + name, NotificationType.SUCCESS));
    }

    private void switchResource(String resource, Button clickedBtn) {
        boolean isInternal = resource.equals("内置资源");
        if (isInternal == AppConfig.INTERNAL_RESOURCE) return;

        DialogUtils.showConfirmDialog(
                (StackPane) getScene().getRoot(),
                "模式切换",
                "切换资源模式需要重启程序生效，是否继续？",
                () -> {
                    AppConfig.INTERNAL_RESOURCE = isInternal;
                    AppConfig.save();
                    RestartUtils.restart();
                },
                () -> {
                }
        );
    }

    private void createProgressButton() {
        btnContainer = new StackPane();
        btnContainer.setPadding(new Insets(0, 0, 0, 0));

        updateBtn = new Button("更新WIKI资源");
        updateBtn.setMaxWidth(Double.MAX_VALUE);
        updateBtn.setPrefHeight(ITEM_HEIGHT);
        updateBtn.setStyle("-fx-background-color: -color-bg-subtle; -fx-text-fill: -color-fg-default; -fx-background-radius: 6; -fx-font-size: 13px; -fx-cursor: hand;");

        updateBtn.setOnAction(e -> {
            DialogUtils.showConfirmDialog(
                    (StackPane) getScene().getRoot(),
                    "确认更新",
                    "确认同步最新WIKI数据？下载过程中请保持网络畅通。",
                    () -> {
                        switchToLoadingState();
                        startDownloadTask();
                    },
                    () -> {}
            );
        });

        progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setPrefHeight(ITEM_HEIGHT);
        progressBar.setVisible(false);
        progressBar.setStyle("-fx-background-radius: 6; -fx-accent: -color-accent-emphasis;");

        progressLabel = new Label("");
        progressLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: -color-fg-default; -fx-font-weight: bold;");
        progressLabel.setMouseTransparent(true);
        progressLabel.setVisible(false);

        statusLabel = new Label("");
        statusLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: -color-accent-emphasis;");
        statusLabel.setTranslateY(-28);
        statusLabel.setVisible(false);

        btnContainer.getChildren().addAll(updateBtn, progressBar, progressLabel, statusLabel);
    }

    private void startDownloadTask() {
        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                Platform.runLater(() -> switchToNormalState());
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("WIKI资源同步完成", NotificationType.SUCCESS));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    switchToNormalState();
                    HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                            new StatusEvent("资源同步失败，请检查网络", NotificationType.ERROR));
                });
            }
        });
    }

    private void switchToLoadingState() {
        updateBtn.setDisable(true);
        updateBtn.setOpacity(0);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        statusLabel.setVisible(true);

        DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
            Platform.runLater(() -> {
                double p = (total == 0) ? 0 : (double) completed / total;
                progressBar.setProgress(p);
                progressLabel.setText(completed + " / " + total);
                statusLabel.setText(DownloadProgressContext.getInstance().getStatusText());

                HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS,
                        new ProgressEvent(p, "WIKI同步: " + DownloadProgressContext.getInstance().getStatusText()));
            });
        });
    }

    /**
     * 向上遍历节点树找到 StackPane 根容器
     */
    private StackPane findRootPane() {
        javafx.scene.Node node = this;
        while ((node = node.getParent()) != null) {
            if (node instanceof StackPane sp) return sp;
        }
        return null;
    }

    private void switchToNormalState() {
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        statusLabel.setVisible(false);
        updateBtn.setDisable(false);
        updateBtn.setOpacity(1);
    }
}