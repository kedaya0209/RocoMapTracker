package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class UpdateDialog extends AbstractDialog {

    private UpdateDialog() {}

    /**
     * 更新确认弹窗 — 显示版本号和 release notes
     */
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

            content.getChildren().add(notesArea);
        }

        buildBaseDialog(rootStack, title, content, "立即更新", Styles.SUCCESS,
                "-color-accent-emphasis", onConfirm, onCancel, null, 520);
    }

    /**
     * 更新就绪弹窗
     */
    public static void showUpdateReadyDialog(StackPane rootStack,
                                              String version,
                                              Runnable onInstallNow,
                                              Runnable onLater) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = createDialogBox();
        dialogBox.setMaxSize(420, 260);

        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z");
        icon.setStyle("-fx-fill: -color-accent-emphasis;");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        Label titleLabel = new Label("更新就绪");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label msgLabel = new Label("版本 " + version + " 已下载完成");
        msgLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button laterBtn = new Button("下次再说");
        laterBtn.setPrefWidth(120);
        laterBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(laterBtn);

        Button installBtn = new Button("立即更新");
        installBtn.setPrefWidth(120);
        installBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SUCCESS);
        FxRippleUtil.install(installBtn);

        btnBox.getChildren().addAll(laterBtn, installBtn);
        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, btnBox);
        mask.getChildren().add(dialogBox);
        mask.setViewOrder(-20);
        rootStack.getChildren().add(mask);

        installBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onInstallNow));
        laterBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onLater));

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }
}
