package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
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

import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class DownloadProgressDialog {

    private DownloadProgressDialog() {}

    /**
     * 下载进度控制
     */
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
                FadeTransition ft = new FadeTransition(Duration.millis(150), mask);
                ft.setToValue(0);
                ft.setOnFinished(e -> parent.getChildren().remove(mask));
                ft.play();
            });
        }
    }

    /**
     * 下载进度弹窗
     */
    public static ProgressControl showDownloadProgressDialog(StackPane rootStack, String version, Runnable onBackground) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

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
            Button bgBtn = new Button("后台下载");
            bgBtn.setPrefWidth(120);
            bgBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
            FxRippleUtil.install(bgBtn);
            bgBtn.setOnAction(e -> {
                mask.setOpacity(0);
                rootStack.getChildren().remove(mask);
                onBackground.run();
            });
            dialogBox.getChildren().add(bgBtn);
        }

        mask.getChildren().add(dialogBox);
        mask.setViewOrder(-20);
        rootStack.getChildren().add(mask);

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();

        return new ProgressControl(mask, progressBar, statusLabel);
    }
}
