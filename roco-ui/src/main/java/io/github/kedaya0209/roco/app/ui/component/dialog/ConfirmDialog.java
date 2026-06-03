package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.ThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

@ThreadSafe
public class ConfirmDialog extends AbstractDialog {

    private ConfirmDialog() {}

    public static void showSimpleDialog(StackPane rootStack,
                                        String title,
                                        String message,
                                        String buttonText,
                                        boolean isError,
                                        Runnable onConfirm) {
        String styleClass = isError ? Styles.DANGER : Styles.SUCCESS;
        String iconColor = isError ? "-color-warning-emphasis" : "-color-accent-emphasis";
        StackPane mask = createMask();
        VBox dialogBox = createDialogBox();
        dialogBox.getChildren().addAll(
                createDefaultIcon(iconColor),
                createTitleLabel(title),
                createMessageLabel(message),
                buttonBox(createButton(buttonText, styleClass, () -> {
                    rootStack.getChildren().remove(mask);
                    if (onConfirm != null) onConfirm.run();
                })));
        mask.getChildren().add(dialogBox);
        showOnStack(rootStack, mask);
    }

    public static void showConfirmDialog(StackPane rootStack,
                                         String title,
                                         Node content,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        StackPane mask = createMask();
        VBox dialogBox = createDialogBox();
        dialogBox.getChildren().addAll(
                createDefaultIcon("-color-accent-emphasis"),
                createTitleLabel(title),
                content,
                buttonBox(
                        createButton("确认添加", Styles.SUCCESS, () -> {
                            rootStack.getChildren().remove(mask);
                            if (onConfirm != null) onConfirm.run();
                        }),
                        createButton("取消", null, () ->
                                fadeOutAndRemove(rootStack, mask, onCancel))));
        mask.getChildren().add(dialogBox);
        showOnStack(rootStack, mask);
    }

    public static void showConfirmDialog(StackPane rootStack,
                                         String title,
                                         String message,
                                         String confirmText,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        StackPane mask = createMask();
        VBox dialogBox = createDialogBox();
        dialogBox.getChildren().addAll(
                createDefaultIcon("-color-warning-emphasis"),
                createTitleLabel(title),
                createMessageLabel(message),
                buttonBox(
                        createButton(confirmText, Styles.DANGER, () -> {
                            rootStack.getChildren().remove(mask);
                            if (onConfirm != null) onConfirm.run();
                        }),
                        createButton("取消", null, () ->
                                fadeOutAndRemove(rootStack, mask, onCancel))));
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
