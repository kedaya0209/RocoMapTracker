package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

@NotThreadSafe
public class UpdateDialog extends AbstractDialog {

    private UpdateDialog() {}

    public static void showUpdateDialog(StackPane rootStack,
                                         String title,
                                         String currentVersion,
                                         String newVersion,
                                         String releaseNotes,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        VBox content = new VBox(8);
        content.setMaxWidth(Double.MAX_VALUE);
        content.setAlignment(Pos.TOP_LEFT);

        Label currentLabel = new Label("当前版本: " + currentVersion);
        currentLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        Label newLabel = new Label("最新版本: " + newVersion);
        newLabel.setStyle("-fx-text-fill: -color-success-emphasis; -fx-font-size: 13px; -fx-font-weight: bold;");

        content.getChildren().addAll(currentLabel, newLabel);

        if (releaseNotes != null && !releaseNotes.isBlank()) {
            Label notesHeader = new Label("更新内容");
            notesHeader.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px; -fx-font-weight: bold;");
            notesHeader.setPadding(new Insets(6, 0, 0, 0));

            TextArea notesArea = getTextArea(releaseNotes);

            content.getChildren().add(notesArea);
        }

        StackPane mask = createMask();
        VBox dialogBox = createDialogBox(420, 520);
        dialogBox.getChildren().addAll(
                createDefaultIcon("-color-accent-emphasis"),
                createTitleLabel(title),
                content,
                buttonBox(
                        createButton("立即更新", Styles.SUCCESS, () -> {
                            rootStack.getChildren().remove(mask);
                            if (onConfirm != null) onConfirm.run();
                        }),
                        createButton("取消", null, () ->
                                fadeOutAndRemove(rootStack, mask, onCancel))));
        mask.getChildren().add(dialogBox);
        showOnStack(rootStack, mask);
    }

    private static TextArea getTextArea(String releaseNotes) {
        TextArea notesArea = new TextArea(releaseNotes.strip());
        notesArea.setEditable(false);
        notesArea.setWrapText(true);
        notesArea.setMaxHeight(280);
        notesArea.setPrefHeight(220);
        notesArea.setStyle(
                "-fx-background-color: -color-bg-subtle; " +
                "-fx-text-fill: -color-fg-muted; " +
                "-fx-font-size: 12px; " +
                "-fx-background-radius: 6; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 6;");
        return notesArea;
    }

    public static void showUpdateReadyDialog(StackPane rootStack,
                                              String version,
                                              Runnable onInstallNow,
                                              Runnable onLater) {
        StackPane mask = createMask();
        VBox dialogBox = createDialogBox(420, 260);
        dialogBox.getChildren().addAll(
                createDefaultIcon("-color-accent-emphasis"),
                createTitleLabel("更新就绪"),
                createMessageLabel("版本 " + version + " 已下载完成"),
                buttonBox(
                        createButton("立即更新", Styles.SUCCESS, () ->
                                fadeOutAndRemove(rootStack, mask, onInstallNow)),
                        createButton("下次再说", null, () ->
                                fadeOutAndRemove(rootStack, mask, onLater))));
        mask.getChildren().add(dialogBox);
        showOnStack(rootStack, mask);
    }

    private static HBox buttonBox(Node... buttons) {
        HBox box = new HBox(15);
        box.setAlignment(Pos.CENTER);
        box.getChildren().addAll(buttons);
        return box;
    }
}
