package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.shape.SVGPath;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

@NotThreadSafe
public class FirstRunDialog extends AbstractDialog {

    private FirstRunDialog() {}

    public static void showFirstRunDialog(StackPane rootStack,
                                          String title,
                                          String message,
                                          Runnable onDownload,
                                          Runnable onUseBuiltIn,
                                          Runnable onExit) {
        StackPane mask = createMask();

        VBox dialogBox = createDialogBox(440, 380);

        SVGPath icon = createDefaultIcon("-color-accent-emphasis");
        Label titleLabel = createTitleLabel(title);
        Label msgLabel = createMessageLabel(message);

        VBox btnBox = new VBox(12);
        btnBox.setAlignment(Pos.CENTER);
        btnBox.setFillWidth(true);

        Button downloadBtn = createButton("立即同步资源", Styles.SUCCESS, () ->
                fadeOutAndRemove(rootStack, mask, onDownload));
        downloadBtn.setMaxWidth(260);
        downloadBtn.setPrefHeight(38);

        Button builtInBtn = createButton("离线启动，后台更新", Styles.ACCENT, () ->
                fadeOutAndRemove(rootStack, mask, onUseBuiltIn));
        builtInBtn.setMaxWidth(260);
        builtInBtn.setPrefHeight(38);

        Button exitBtn = createButton("退出程序", Styles.DANGER, () ->
                fadeOutAndRemove(rootStack, mask, onExit));
        exitBtn.setMaxWidth(260);
        exitBtn.setPrefHeight(38);

        btnBox.getChildren().addAll(downloadBtn, builtInBtn, exitBtn);

        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, btnBox);
        mask.getChildren().add(dialogBox);
        showOnStack(rootStack, mask);
    }
}
