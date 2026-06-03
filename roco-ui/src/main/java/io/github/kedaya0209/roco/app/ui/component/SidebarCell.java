package io.github.kedaya0209.roco.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

/**
 * 侧边栏单元格渲染器 — 从 Sidebar 提取，负责 4 种单元格类型的渲染。
 */
@NotThreadSafe
class SidebarCell extends ListCell<Sidebar.SidebarItem> {

    private static final String BG_STYLE = "-fx-background-color: -color-bg-subtle; -fx-background-radius: 6;";

    private final Supplier<Sidebar.SidebarItem.Category> expandedCategory;
    private final Consumer<Sidebar.SidebarItem> onHeaderClick;
    private final Consumer<Sidebar.SidebarItem> onOptionClick;

    SidebarCell(Supplier<Sidebar.SidebarItem.Category> expandedCategory,
                Consumer<Sidebar.SidebarItem> onHeaderClick,
                Consumer<Sidebar.SidebarItem> onOptionClick) {
        this.expandedCategory = expandedCategory;
        this.onHeaderClick = onHeaderClick;
        this.onOptionClick = onOptionClick;
        setStyle("-fx-background-color: transparent; -fx-background-insets: 0;");
    }

    @Override
    protected void updateItem(Sidebar.SidebarItem item, boolean empty) {
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

    private void renderHeader(Sidebar.SidebarItem item) {
        Node icon = SvgManager.createHoverDrawIcon(item.iconSvg(), 18, 1.5, 400);

        Label title = new Label(item.title());
        title.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");

        if (item.category() == Sidebar.SidebarItem.Category.MATCH) {
            renderMatchToggle(item, icon, title);
            return;
        }

        Label value = new Label(item.currentValue());
        value.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px;");
        value.setPadding(new Insets(0, 2, 0, 0));

        SVGPath arrow = new SVGPath();
        arrow.setContent("M7 10l5 5 5-5z");
        arrow.setStyle("-fx-fill: -color-fg-default;");
        arrow.setRotate(expandedCategory.get() == item.category() ? 180 : 0);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(6, icon, title, spacer, value, arrow);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(36);
        row.setPadding(new Insets(0, 10, 0, 12));
        row.setStyle(BG_STYLE);
        row.setMouseTransparent(true);

        addHoverEvent(icon, row);

        setOnMouseClicked(e -> {
            animateArrow(arrow, expandedCategory.get() == item.category());
            onHeaderClick.accept(item);
        });

        StackPane wrapper = new StackPane(row);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.setMouseTransparent(true);
        setPadding(new Insets(0));
        setGraphic(wrapper);
    }

    private void addHoverEvent(Node icon, HBox row) {
        row.getStyleClass().add("sidebar-header-row");
        setOnMouseEntered(e -> {
            SvgManager.animateHoverDrawIcon(icon, true, 400);
        });
        setOnMouseExited(e -> {
            SvgManager.animateHoverDrawIcon(icon, false, 400);
        });
        setCursor(Cursor.HAND);
    }

    // ── Match Toggle ────────────────────────────────────

    private void renderMatchToggle(Sidebar.SidebarItem item, Node icon, Label title) {
        ToggleSwitch toggle = new ToggleSwitch();
        toggle.setSelected(AppState.getInstance().isMatchingEnabled());
        // Guard: 防止 hook 外部同步导致循环发布事件
        toggle.selectedProperty().addListener((_, _, newVal) -> {
            if (AppState.getInstance().isMatchingEnabled() == newVal) return;
            AppState.getInstance().setMatchingEnabled(newVal);
            AppEvents.publish(StatusEvent.class,
                    newVal
                            ? new StatusEvent("匹配已开启", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL)
                            : new StatusEvent("匹配已暂停", NotificationType.INFO, StatusEvent.DisplayMode.CAROUSEL));
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(6, icon, title, spacer, toggle);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(36);
        row.setPadding(new Insets(0, 12, 0, 12));
        row.setStyle(BG_STYLE);
        row.getStyleClass().add("sidebar-header-row");

        setOnMouseEntered(e -> {
            SvgManager.animateHoverDrawIcon(icon, true, 400);
        });
        setOnMouseExited(e -> {
            SvgManager.animateHoverDrawIcon(icon, false, 400);
        });

        StackPane wrapper = new StackPane(row);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        setPadding(new Insets(0));
        setGraphic(wrapper);
    }

    // ── Option ──────────────────────────────────────────

    private void renderOption(Sidebar.SidebarItem item) {
        Label title = new Label(item.title());
        title.setStyle(String.format(
                "-fx-text-fill: %s; -fx-font-size: 12px; -fx-font-weight: %s;",
                item.selected() ? "-color-success-emphasis" : "-color-fg-muted",
                item.selected() ? "bold" : "normal"
        ));

        HBox row = new HBox(title);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(36);
        row.setPadding(new Insets(0, 0, 0, 20));
        row.setMouseTransparent(true);

        if (item.selected()) {
            row.setStyle("-fx-background-color: -color-success-subtle; -fx-background-radius: 4;");
        } else {
            setOnMouseEntered(e -> row.setStyle("-fx-background-color: -color-bg-inset; -fx-background-radius: 4;"));
            setOnMouseExited(e -> row.setStyle(""));
        }
        setCursor(Cursor.HAND);
        setOnMouseClicked(e -> onOptionClick.accept(item));

        StackPane wrapper = new StackPane(row);
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.setMouseTransparent(true);
        setPadding(new Insets(0));
        setGraphic(wrapper);

        wrapper.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(150), wrapper);
        ft.setToValue(1);
        ft.play();
    }

    // ── Action ──────────────────────────────────────────

    private void renderAction(Sidebar.SidebarItem item) {
        boolean isProgress = item.progress() >= 0;

        setOnMouseClicked(null);
        setOnMouseEntered(null);
        setOnMouseExited(null);
        setCursor(Cursor.DEFAULT);

        Node icon = SvgManager.createHoverDrawIcon(item.iconSvg(), 18, 1.5, 400);

        Label text = new Label(item.title());
        text.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");

        HBox content = new HBox(8, icon, text);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setPrefHeight(36);
        content.setPadding(new Insets(0, 12, 0, 12));
        content.setStyle(BG_STYLE);
        content.setMouseTransparent(true);

        if (!isProgress) {
            addHoverEvent(icon, content);
            if (item.onAction() != null) {
                setOnMouseClicked(e -> item.onAction().run());
            }
        }

        StackPane wrapper = new StackPane();
        if (isProgress) {
            // 进度模式：全高进度条作为背景，内容叠加在上方（仿 WIKI 更新样式）
            ProgressBar progressBar = new ProgressBar(item.progress());
            progressBar.setPrefWidth(Double.MAX_VALUE);
            progressBar.setPrefHeight(36);
            progressBar.setStyle("-fx-accent: -color-accent-emphasis; -fx-background-radius: 6;");

            content.setStyle(null); // 移除背景色，让进度条透出
            StackPane stack = new StackPane(progressBar, content);
            stack.setPrefHeight(36);
            wrapper.getChildren().add(stack);
        } else {
            wrapper.getChildren().add(content);
        }
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        wrapper.setMouseTransparent(true);
        setPadding(new Insets(0));
        setGraphic(wrapper);
    }

    // ── Wiki ────────────────────────────────────────────

    private void renderWiki(Sidebar.SidebarItem item) {
        setOnMouseClicked(null);
        setOnMouseEntered(null);
        setOnMouseExited(null);
        setCursor(Cursor.DEFAULT);

        StackPane wrapper = new StackPane(item.wikiUpdater().getContainer());
        wrapper.setPadding(new Insets(2, 0, 2, 0));
        setPadding(new Insets(0));
        setGraphic(wrapper);
    }

    // ── 箭头动画 ─────────────────────────────────────────

    private void animateArrow(SVGPath arrow, boolean wasExpanded) {
        double to = wasExpanded ? 0 : 180;
        Timeline tl = new Timeline(
                new KeyFrame(Duration.millis(200),
                        new KeyValue(arrow.rotateProperty(), to, Interpolator.EASE_BOTH))
        );
        tl.play();
    }
}
