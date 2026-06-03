package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.ThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

@ThreadSafe
public class ConfirmDialog extends AbstractDialog {

    private ConfirmDialog() {}

    /**
     * 简易文本弹窗
     */
    public static void showSimpleDialog(StackPane rootStack,
                                        String title,
                                        String message,
                                        String buttonText,
                                        boolean isError,
                                        Runnable onConfirm) {
        String styleClass = isError ? Styles.DANGER : Styles.SUCCESS;
        String iconColor = isError ? "-color-warning-emphasis" : "-color-accent-emphasis";
        buildBaseDialog(rootStack, title, createMessageLabel(message), buttonText, styleClass, iconColor, onConfirm, null);
    }

    /**
     * 自定义内容弹窗
     */
    public static void showConfirmDialog(StackPane rootStack,
                                         String title,
                                         Node content,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
        }
        buildBaseDialog(rootStack, title, content, "确认添加", Styles.SUCCESS, "-color-accent-emphasis", onConfirm, onCancel);
    }

    /**
     * 文本确认弹窗
     */
    public static void showConfirmDialog(StackPane rootStack,
                                         String title,
                                         String message,
                                         String confirmText,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        buildBaseDialog(rootStack, title, createMessageLabel(message), confirmText, Styles.DANGER, "-color-warning-emphasis", onConfirm, onCancel);
    }
}
