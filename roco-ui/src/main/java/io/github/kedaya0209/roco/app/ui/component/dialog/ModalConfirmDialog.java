package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class ModalConfirmDialog {

    private ModalConfirmDialog() {}

    /**
     * Stage 模态确认弹窗 — 用于关闭确认等需要置顶的场景。
     */
    public static void showModalConfirmDialog(Stage owner,
                                              String title,
                                              String message,
                                              String confirmText,
                                              Runnable onConfirm,
                                              Runnable onCancel) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.TRANSPARENT);

        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = new VBox(25);
        dialogBox.setMaxSize(420, 320);
        dialogBox.setPadding(new Insets(30));
        dialogBox.setAlignment(Pos.CENTER);
        dialogBox.setStyle(
                "-fx-background-color: -color-bg-default; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 12; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);"
        );

        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
        icon.setStyle("-fx-fill: -color-warning-emphasis;");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label msgLabel = new Label(message);
        msgLabel.setTextAlignment(TextAlignment.CENTER);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button confirmBtn = new Button(confirmText);
        confirmBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.DANGER);
        confirmBtn.setPrefWidth(120);
        FxRippleUtil.install(confirmBtn);
        confirmBtn.setOnAction(e -> {
            dialog.close();
            if (onConfirm != null) onConfirm.run();
        });

        btnBox.getChildren().add(confirmBtn);

        if (onCancel != null) {
            Button cancelBtn = new Button("取消");
            cancelBtn.setPrefWidth(120);
            cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
            FxRippleUtil.install(cancelBtn);
            cancelBtn.setOnAction(e -> {
                dialog.close();
                if (onCancel != null) onCancel.run();
            });
            btnBox.getChildren().add(cancelBtn);
        }

        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, btnBox);
        mask.getChildren().add(dialogBox);

        mask.setOnMouseClicked(e -> {
            dialog.close();
            if (onCancel != null) onCancel.run();
        });
        dialogBox.setOnMouseClicked(Event::consume);

        Scene scene = new Scene(mask);
        scene.setFill(null);
        if (owner.getScene() != null) {
            scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        dialog.setScene(scene);
        dialog.setX(owner.getX());
        dialog.setY(owner.getY());
        dialog.setWidth(owner.getWidth());
        dialog.setHeight(owner.getHeight());

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();

        dialog.showAndWait();
    }
}
