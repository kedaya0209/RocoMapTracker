package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.animation.RotateTransition;
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

import io.github.kedaya0209.roco.app.update.plugin.PluginAsset;
import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateInfo;

@NotThreadSafe
public class PluginUpdateDialog {

    private PluginUpdateDialog() {}

    public static void showPluginUpdatesDialog(StackPane rootStack,
                                                Map<PluginInfo, PluginUpdateInfo> updates,
                                                Consumer<List<String>> onDownloadSelected) {
        StackPane mask = AbstractDialog.createMask();

        VBox dialogBox = AbstractDialog.createDialogBox(480, 420);

        SVGPath icon = AbstractDialog.createDefaultIcon("-color-accent-emphasis");
        Label titleLabel = AbstractDialog.createTitleLabel("插件更新可用");

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

            getItem(plugin, detailPanel, createDetailRow("描述", plugin.description()), createDetailRow("入口", plugin.entry()), createDetailRow("仓库", plugin.source().repo()));
            if (update.releaseNotes() != null && !update.releaseNotes().isBlank())
                detailPanel.getChildren().add(createDetailRow("更新说明", update.releaseNotes().strip()));
            if (!update.remoteAssets().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (PluginAsset a : update.remoteAssets()) {
                    if (!sb.isEmpty()) sb.append(", ");
                    sb.append(a.remoteName());
                }
                detailPanel.getChildren().add(createDetailRow("文件", sb.toString()));
            }

            boolean[] expanded = {false};
            arrowWrapper.setOnMouseClicked(_ -> {
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

        Button cancelBtn = AbstractDialog.createButton("取消", null, () -> {
            FadeTransition ft = new FadeTransition(Duration.millis(150), mask);
            ft.setToValue(0);
            ft.setOnFinished(_ -> rootStack.getChildren().remove(mask));
            ft.play();
        });
        Button updateBtn = AbstractDialog.createButton("更新选中", Styles.SUCCESS, () -> {
            rootStack.getChildren().remove(mask);
            if (!selectedIds.isEmpty() && onDownloadSelected != null)
                onDownloadSelected.accept(List.copyOf(selectedIds));
        });

        btnBox.getChildren().addAll(cancelBtn, updateBtn);
        dialogBox.getChildren().addAll(icon, titleLabel, scrollWrapper, btnBox);
        mask.getChildren().add(dialogBox);

        rootStack.getChildren().add(mask);
        AbstractDialog.fadeIn(mask);
    }

    public static void getItem(PluginInfo plugin, VBox detailPanel, HBox desc, HBox entry, HBox repository) {
        if (!plugin.description().isEmpty())
            detailPanel.getChildren().add(desc);
        if (!plugin.entry().isEmpty())
            detailPanel.getChildren().add(entry);
        if (plugin.source() != null && !plugin.source().repo().isEmpty())
            detailPanel.getChildren().add(repository);
    }

    private static HBox createDetailRow(String label, String value) {
        return getRow(label, value);
    }

    public static HBox getRow(String label, String value) {
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
