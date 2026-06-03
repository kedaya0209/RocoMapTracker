package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.animation.RotateTransition;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateInfo;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class PluginUpdateDialog {

    private PluginUpdateDialog() {}

    /**
     * 插件更新批量选择弹窗
     */
    public static void showPluginUpdatesDialog(StackPane rootStack,
                                                Map<PluginInfo, PluginUpdateInfo> updates,
                                                Consumer<List<String>> onDownloadSelected) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = new VBox(20);
        dialogBox.setMaxSize(480, 420);
        dialogBox.setPadding(new Insets(30));
        dialogBox.setAlignment(Pos.CENTER);
        dialogBox.setStyle(
                "-fx-background-color: -color-bg-default; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 12; " +
                "-fx-background-radius: 12; " +
                "-fx-border-width: 1.5; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);");

        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z");
        icon.setStyle("-fx-fill: -color-accent-emphasis;");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        Label titleLabel = new Label("插件更新可用");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        VBox listBox = new VBox(6);
        listBox.setMaxHeight(240);
        listBox.setAlignment(Pos.TOP_LEFT);

        List<String> selectedIds = new CopyOnWriteArrayList<>();
        for (Map.Entry<PluginInfo, PluginUpdateInfo> entry : updates.entrySet()) {
            PluginInfo plugin = entry.getKey();
            PluginUpdateInfo update = entry.getValue();

            Label arrowLabel = new Label("▶");
            arrowLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 11px; -fx-cursor: hand;");
            StackPane arrowWrapper = new StackPane(arrowLabel);
            arrowWrapper.setPrefWidth(16);
            arrowWrapper.setPrefHeight(16);

            CheckBox cb = new CheckBox(plugin.title() + "  " + plugin.version() + " → " + update.version());
            cb.setSelected(true);
            cb.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px;");
            cb.selectedProperty().addListener((_, _, sel) -> {
                if (sel) selectedIds.add(plugin.id());
                else selectedIds.remove(plugin.id());
            });
            selectedIds.add(plugin.id());

            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().addAll(arrowWrapper, cb);

            VBox detailPanel = new VBox(4);
            detailPanel.setPadding(new Insets(4, 0, 4, 24));
            detailPanel.setVisible(false);
            detailPanel.setManaged(false);

            if (!plugin.description().isEmpty()) {
                detailPanel.getChildren().add(createDetailRow("描述", plugin.description()));
            }
            if (!plugin.entry().isEmpty()) {
                detailPanel.getChildren().add(createDetailRow("入口", plugin.entry()));
            }
            if (plugin.source() != null && !plugin.source().repo().isEmpty()) {
                detailPanel.getChildren().add(createDetailRow("仓库", plugin.source().repo()));
            }
            if (update.releaseNotes() != null && !update.releaseNotes().isBlank()) {
                detailPanel.getChildren().add(createDetailRow("更新说明", update.releaseNotes().strip()));
            }
            if (!update.remoteAssets().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (var a : update.remoteAssets()) {
                    if (!sb.isEmpty()) sb.append(", ");
                    sb.append(a.remoteName());
                }
                detailPanel.getChildren().add(createDetailRow("文件", sb.toString()));
            }

            boolean[] expanded = {false};
            arrowWrapper.setOnMouseClicked(e -> {
                expanded[0] = !expanded[0];
                RotateTransition rt = new RotateTransition(Duration.millis(200), arrowWrapper);
                rt.setToAngle(expanded[0] ? 90 : 0);
                rt.play();
                detailPanel.setVisible(expanded[0]);
                detailPanel.setManaged(expanded[0]);
            });

            VBox item = new VBox(0);
            item.getChildren().addAll(row, detailPanel);
            listBox.getChildren().add(item);
        }

        StackPane scrollWrapper = new StackPane(listBox);
        scrollWrapper.setMaxHeight(260);
        VBox.setVgrow(scrollWrapper, Priority.ALWAYS);

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button("取消");
        cancelBtn.setPrefWidth(120);
        cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(cancelBtn);

        Button updateBtn = new Button("更新选中");
        updateBtn.setPrefWidth(120);
        updateBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SUCCESS);
        FxRippleUtil.install(updateBtn);

        btnBox.getChildren().addAll(cancelBtn, updateBtn);
        dialogBox.getChildren().addAll(icon, titleLabel, scrollWrapper, btnBox);
        mask.getChildren().add(dialogBox);
        mask.setViewOrder(-20);
        rootStack.getChildren().add(mask);

        cancelBtn.setOnAction(e -> {
            FadeTransition ft = new FadeTransition(Duration.millis(150), mask);
            ft.setToValue(0);
            ft.setOnFinished(ev -> rootStack.getChildren().remove(mask));
            ft.play();
        });
        updateBtn.setOnAction(e -> {
            rootStack.getChildren().remove(mask);
            if (!selectedIds.isEmpty() && onDownloadSelected != null) {
                onDownloadSelected.accept(List.copyOf(selectedIds));
            }
        });

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }

    private static HBox createDetailRow(String label, String value) {
        Label lbl = new Label(label + ": ");
        lbl.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px; -fx-font-weight: bold;");
        Label val = new Label(value);
        val.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 12px;");
        val.setWrapText(true);
        HBox row = new HBox(6);
        row.setAlignment(Pos.TOP_LEFT);
        row.getChildren().addAll(lbl, val);
        return row;
    }
}
