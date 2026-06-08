package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.shape.SVGPath;
import javafx.event.Event;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

@NotThreadSafe
public class ModalConfirmDialog {

    private ModalConfirmDialog() {}

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

        StackPane mask = AbstractDialog.createMask();
        mask.setViewOrder(0);

        VBox dialogBox = AbstractDialog.createDialogBox();

        SVGPath icon = AbstractDialog.createDefaultIcon("-color-warning-emphasis");
        Label titleLabel = AbstractDialog.createTitleLabel(title);
        Label msgLabel = AbstractDialog.createMessageLabel(message);

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(javafx.geometry.Pos.CENTER);

        Button confirmBtn = AbstractDialog.createButton(confirmText, Styles.DANGER, () -> {
            dialog.close();
            if (onConfirm != null) onConfirm.run();
        });
        btnBox.getChildren().add(confirmBtn);

        if (onCancel != null) {
            Button cancelBtn = AbstractDialog.createButton("取消", null, () -> {
                dialog.close();
                onCancel.run();
            });
            btnBox.getChildren().add(cancelBtn);
        }

        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, btnBox);
        mask.getChildren().add(dialogBox);

        mask.setOnMouseClicked(_ -> {
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

        AbstractDialog.fadeIn(mask);
        dialog.showAndWait();
    }
}
