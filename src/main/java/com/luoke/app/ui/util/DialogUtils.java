package com.luoke.app.ui.util;

import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

public class DialogUtils {

    /**
     * 显示自定义沉浸式对话框
     *
     * @param rootStack  场景的根容器 (StackPane)
     * @param title      标题
     * @param message    内容
     * @param buttonText 按钮文字
     * @param isError    是否为错误样式 (影响图标颜色和按钮色调)
     * @param onConfirm  点击确认后的回调
     */
    public static void showSimpleDialog(StackPane rootStack,
                                        String title,
                                        String message,
                                        String buttonText,
                                        boolean isError,
                                        Runnable onConfirm) {

        // 1. 遮罩层
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        // 2. 对话框容器
        VBox dialogBox = new VBox(20);
        dialogBox.setMaxSize(380, 240);
        dialogBox.setPadding(new Insets(30));
        dialogBox.setAlignment(Pos.CENTER);
        dialogBox.setStyle(
                "-fx-background-color: #1e1e1e; " +
                        "-fx-border-color: " + (isError ? "#f44336" : "rgba(255,255,255,0.1)") + "; " +
                        "-fx-border-radius: 12; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);"
        );

        // 3. 图标 (SVG)
        SVGPath icon = new SVGPath();
        if (isError) {
            // 错误图标 (感叹号)
            icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
            icon.setFill(Color.web("#f44336"));
        } else {
            // 信息图标 (圆圈 i)
            icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z");
            icon.setFill(Color.web("#00BFFF")); // 你定义的统一蓝色
        }
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        // 4. 文字
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label msgLabel = new Label(message);
        msgLabel.setTextAlignment(TextAlignment.CENTER);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: #BBBBBB;");

        // 5. 按钮
        Button actionBtn = new Button(buttonText);
        actionBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, isError ? Styles.DANGER : Styles.SUCCESS);
        actionBtn.setPrefWidth(120);

        actionBtn.setOnAction(e -> {
            fadeOutAndRemove(rootStack, mask, onConfirm);
        });

        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, actionBtn);
        mask.getChildren().add(dialogBox);
        rootStack.getChildren().add(mask);

        // 入场动画
        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(250), mask);
        ft.setToValue(1);
        ft.play();
    }

    private static void fadeOutAndRemove(StackPane root, Node node, Runnable callback) {
        FadeTransition ft = new FadeTransition(Duration.millis(200), node);
        ft.setToValue(0);
        ft.setOnFinished(e -> {
            root.getChildren().remove(node);
            if (callback != null) callback.run();
        });
        ft.play();
    }
}