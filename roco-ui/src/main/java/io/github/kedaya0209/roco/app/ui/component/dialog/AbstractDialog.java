package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.event.Event;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class AbstractDialog {

    protected AbstractDialog() {}

    protected static void buildBaseDialog(StackPane rootStack,
                                          String title,
                                          Node content,
                                          String confirmBtnText,
                                          String confirmBtnStyleClass,
                                          String iconStyle,
                                          Runnable onConfirm,
                                          Runnable onCancel) {
        buildBaseDialog(rootStack, title, content, confirmBtnText, confirmBtnStyleClass,
                iconStyle, onConfirm, onCancel, null);
    }

    protected static void buildBaseDialog(StackPane rootStack,
                                          String title,
                                          Node content,
                                          String confirmBtnText,
                                          String confirmBtnStyleClass,
                                          String iconStyle,
                                          Runnable onConfirm,
                                          Runnable onCancel,
                                          Node iconNode) {
        buildBaseDialog(rootStack, title, content, confirmBtnText, confirmBtnStyleClass,
                iconStyle, onConfirm, onCancel, iconNode, 320);
    }

    protected static void buildBaseDialog(StackPane rootStack,
                                          String title,
                                          Node content,
                                          String confirmBtnText,
                                          String confirmBtnStyleClass,
                                          String iconStyle,
                                          Runnable onConfirm,
                                          Runnable onCancel,
                                          Node iconNode,
                                          double maxHeight) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = new VBox(25);
        dialogBox.setMaxSize(420, maxHeight);
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

        Node icon;
        if (iconNode != null) {
            icon = iconNode;
        } else {
            SVGPath defaultIcon = new SVGPath();
            defaultIcon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
            defaultIcon.setStyle("-fx-fill: " + iconStyle + ";");
            defaultIcon.setScaleX(1.8);
            defaultIcon.setScaleY(1.8);
            icon = defaultIcon;
        }

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        StackPane contentContainer = new StackPane();
        contentContainer.setAlignment(Pos.CENTER);
        contentContainer.getChildren().add(content);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button confirmBtn = new Button(confirmBtnText);
        confirmBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, confirmBtnStyleClass);
        confirmBtn.setPrefWidth(120);
        FxRippleUtil.install(confirmBtn);
        confirmBtn.setOnAction(e -> {
            rootStack.getChildren().remove(mask);
            if (onConfirm != null) onConfirm.run();
        });

        btnBox.getChildren().add(confirmBtn);

        if (onCancel != null) {
            Button cancelBtn = new Button("取消");
            cancelBtn.setPrefWidth(120);
            cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
            FxRippleUtil.install(cancelBtn);
            cancelBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onCancel));
            btnBox.getChildren().add(cancelBtn);
        }

        dialogBox.getChildren().addAll(icon, titleLabel, contentContainer, btnBox);
        mask.getChildren().add(dialogBox);
        mask.setViewOrder(-20);
        rootStack.getChildren().add(mask);

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }

    protected static void fadeOutAndRemove(StackPane root, Node node, Runnable callback) {
        FadeTransition ft = new FadeTransition(Duration.millis(150), node);
        ft.setToValue(0);
        ft.setOnFinished(e -> {
            root.getChildren().remove(node);
            if (callback != null) callback.run();
        });
        ft.play();
    }

    protected static Label createMessageLabel(String message) {
        Label msgLabel = new Label(message);
        msgLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        return msgLabel;
    }

    protected static VBox createDialogBox() {
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
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);");
        return dialogBox;
    }
}
