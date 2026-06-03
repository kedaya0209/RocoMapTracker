package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class FirstRunDialog extends AbstractDialog {

    private FirstRunDialog() {}

    /**
     * 首次启动三选项弹窗
     */
    public static void showFirstRunDialog(StackPane rootStack,
                                          String title,
                                          String message,
                                          Runnable onDownload,
                                          Runnable onUseBuiltIn,
                                          Runnable onExit) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = createDialogBox();
        dialogBox.setMaxSize(440, 380);

        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
        icon.setStyle("-fx-fill: -color-accent-emphasis;");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label msgLabel = new Label(message);
        msgLabel.setTextAlignment(TextAlignment.CENTER);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: -color-fg-muted;");

        VBox btnBox = new VBox(12);
        btnBox.setAlignment(Pos.CENTER);
        btnBox.setFillWidth(true);

        Button downloadBtn = new Button("立即同步资源");
        downloadBtn.setMaxWidth(260);
        downloadBtn.setPrefHeight(38);
        downloadBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SUCCESS);
        FxRippleUtil.install(downloadBtn);
        downloadBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onDownload));

        Button builtInBtn = new Button("离线启动，后台更新");
        builtInBtn.setMaxWidth(260);
        builtInBtn.setPrefHeight(38);
        builtInBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
        FxRippleUtil.install(builtInBtn);
        builtInBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onUseBuiltIn));

        Button exitBtn = new Button("退出程序");
        exitBtn.setMaxWidth(260);
        exitBtn.setPrefHeight(38);
        exitBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.DANGER);
        FxRippleUtil.install(exitBtn);
        exitBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onExit));

        btnBox.getChildren().addAll(downloadBtn, builtInBtn, exitBtn);

        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, btnBox);
        mask.getChildren().add(dialogBox);
        mask.setViewOrder(-20);
        rootStack.getChildren().add(mask);

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }
}
