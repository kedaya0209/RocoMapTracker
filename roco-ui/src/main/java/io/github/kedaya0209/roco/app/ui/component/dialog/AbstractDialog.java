package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class AbstractDialog {

    protected AbstractDialog() {}

    // ============================================================
    // 原语：mask / dialogBox / 动画
    // ============================================================

    protected static StackPane createMask() {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");
        mask.setViewOrder(-20);
        return mask;
    }

    protected static VBox createDialogBox() {
        return createDialogBox(420, 320);
    }

    protected static VBox createDialogBox(double maxWidth, double maxHeight) {
        VBox box = new VBox(25);
        box.setMaxSize(maxWidth, maxHeight);
        box.setPadding(new Insets(30));
        box.setAlignment(Pos.CENTER);
        box.setStyle(
                "-fx-background-color: -color-bg-default; " +
                "-fx-border-color: -color-border-muted; " +
                "-fx-border-radius: 12; " +
                "-fx-background-radius: 12; " +
                "-fx-border-width: 1.5; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);");
        return box;
    }

    protected static void showOnStack(StackPane rootStack, StackPane mask) {
        rootStack.getChildren().add(mask);
        fadeIn(mask);
    }

    protected static void fadeIn(Node node) {
        node.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), node);
        ft.setToValue(1);
        ft.play();
    }

    protected static void fadeOutAndRemove(StackPane root, Node node, Runnable callback) {
        FadeTransition ft = new FadeTransition(Duration.millis(150), node);
        ft.setToValue(0);
        ft.setOnFinished(_ -> {
            root.getChildren().remove(node);
            if (callback != null) callback.run();
        });
        ft.play();
    }

    // ============================================================
    // 原语：通用子组件
    // ============================================================

    protected static SVGPath createDefaultIcon(String fillColor) {
        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
        icon.setStyle("-fx-fill: " + fillColor + ";");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);
        return icon;
    }

    protected static Label createTitleLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        return label;
    }

    protected static Label createMessageLabel(String message) {
        return createMessageLabel(message, "-color-fg-muted");
    }

    protected static Label createMessageLabel(String message, String textFill) {
        Label msgLabel = new Label(message);
        msgLabel.setTextAlignment(TextAlignment.CENTER);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: " + textFill + ";");
        return msgLabel;
    }

    protected static Button createButton(String text, String styleClass, Runnable onAction) {
        Button btn = new Button(text);
        btn.setPrefWidth(120);
        if (styleClass != null) {
            btn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, styleClass);
        } else {
            btn.getStyleClass().add(Styles.BUTTON_OUTLINED);
        }
        FxRippleUtil.install(btn);
        if (onAction != null) {
            btn.setOnAction(_ -> onAction.run());
        }
        return btn;
    }
}
