package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class LoadingOverlay extends VBox {
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("正在初始化资源...");
    private final Button cancelBtn = new Button("取消下载");

    public LoadingOverlay(Runnable onCancel) {
        this.setAlignment(Pos.CENTER);
        this.setSpacing(25);
        this.setStyle("-fx-background-color: #1e1e1e;");

        statusLabel.setTextFill(Color.WHITE);
        statusLabel.setFont(Font.font("System", FontWeight.BOLD, 16));

        progressBar.setPrefWidth(400);
        progressBar.getStyleClass().add(Styles.MEDIUM);

        cancelBtn.getStyleClass().add(Styles.DANGER);
        cancelBtn.setOnAction(e -> {
            cancelBtn.setDisable(true);
            statusLabel.setText("正在取消并清理中...");
            if (onCancel != null) onCancel.run();
        });

        this.getChildren().addAll(statusLabel, progressBar, cancelBtn);
    }

    public void updateProgress(double progress, String text) {
        progressBar.setProgress(progress);
        statusLabel.setText(text);
    }
}