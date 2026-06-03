package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

@NotThreadSafe
public class DownloadProgressDialog {

    private DownloadProgressDialog() {}

    @NotThreadSafe
    public static class ProgressControl {
        private final StackPane mask;
        private final ProgressBar progressBar;
        private final Label statusLabel;

        ProgressControl(StackPane mask, ProgressBar progressBar, Label statusLabel) {
            this.mask = mask;
            this.progressBar = progressBar;
            this.statusLabel = statusLabel;
        }

        public void updateProgress(double progress, String text) {
            Platform.runLater(() -> {
                if (progress < 0) {
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                } else {
                    progressBar.setProgress(Math.clamp(progress, 0, 1));
                }
                if (text != null) statusLabel.setText(text);
            });
        }

        public void close() {
            Platform.runLater(() -> {
                Node parentNode = mask.getParent();
                if (!(parentNode instanceof StackPane parent)) return;
                javafx.animation.FadeTransition ft =
                        new javafx.animation.FadeTransition(Duration.millis(150), mask);
                ft.setToValue(0);
                ft.setOnFinished(e -> parent.getChildren().remove(mask));
                ft.play();
            });
        }
    }

    public static ProgressControl showDownloadProgressDialog(StackPane rootStack, String version, Runnable onBackground) {
        StackPane mask = AbstractDialog.createMask();

        VBox dialogBox = new VBox(20);
        dialogBox.setMaxSize(420, 240);
        dialogBox.setPadding(new Insets(30));
        dialogBox.setAlignment(Pos.CENTER);
        dialogBox.setStyle(
                "-fx-background-color: -color-bg-default; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 12; " +
                "-fx-background-radius: 12; " +
                "-fx-border-width: 1.5;");

        Label titleLabel = new Label("正在下载 " + version + " ...");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(360);
        progressBar.getStyleClass().add(Styles.MEDIUM);

        Label statusLabel = new Label("准备下载...");
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        dialogBox.getChildren().addAll(titleLabel, progressBar, statusLabel);

        if (onBackground != null) {
            Button bgBtn = AbstractDialog.createButton("后台下载", Styles.ACCENT, () -> {
                rootStack.getChildren().remove(mask);
                onBackground.run();
            });
            dialogBox.getChildren().add(bgBtn);
        }

        mask.getChildren().add(dialogBox);
        rootStack.getChildren().add(mask);
        AbstractDialog.fadeIn(mask);

        return new ProgressControl(mask, progressBar, statusLabel);
    }
}
