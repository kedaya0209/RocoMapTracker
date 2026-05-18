package com.luoke.app.ui.util;

import atlantafx.base.theme.Styles;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

public class DialogUtils {

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
     * 自定义内容弹窗（居中修复版）
     */
    public static void showConfirmDialog(StackPane rootStack,
                                         String title,
                                         Node content,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        // 确保 content 本身不会因为宽度问题导致视觉偏移
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
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        buildBaseDialog(rootStack, title, createMessageLabel(message), "确认退出", Styles.DANGER, "-color-warning-emphasis", onConfirm, onCancel);
    }

    private static void buildBaseDialog(StackPane rootStack,
                                        String title,
                                        Node content,
                                        String confirmBtnText,
                                        String confirmBtnStyleClass,
                                        String iconStyle,
                                        Runnable onConfirm,
                                        Runnable onCancel) {
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

        // 图标
        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
        icon.setStyle("-fx-fill: " + iconStyle + ";");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        // 标题
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        // --- 核心居中处理层 ---
        StackPane contentContainer = new StackPane();
        contentContainer.setAlignment(Pos.CENTER);
        contentContainer.getChildren().add(content);
        // 让容器自适应宽度，方便垂直堆叠
        VBox.setVgrow(contentContainer, javafx.scene.layout.Priority.ALWAYS);

        // 按钮组
        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button confirmBtn = new Button(confirmBtnText);
        confirmBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, confirmBtnStyleClass);
        confirmBtn.setPrefWidth(120);
        FxRippleUtil.install(confirmBtn);
        confirmBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onConfirm));

        btnBox.getChildren().add(confirmBtn);

        if (onCancel != null) {
            Button cancelBtn = new Button("取消");
            cancelBtn.setPrefWidth(120);
            cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED); // 使用主题自带的样式减少冗余
            FxRippleUtil.install(cancelBtn);
            cancelBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onCancel));
            btnBox.getChildren().add(cancelBtn);
        }

        dialogBox.getChildren().addAll(icon, titleLabel, contentContainer, btnBox);
        mask.getChildren().add(dialogBox);
        rootStack.getChildren().add(mask);

        // 入场动画
        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }

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

        VBox dialogBox = new VBox(25);
        dialogBox.setMaxSize(440, 380);
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

        // 图标
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

        // 按钮组
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
        rootStack.getChildren().add(mask);

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }

    private static Label createMessageLabel(String message) {
        Label msgLabel = new Label(message);
        msgLabel.setTextAlignment(TextAlignment.CENTER);
        msgLabel.setWrapText(true);
        msgLabel.setStyle("-fx-text-fill: -color-fg-muted;");
        return msgLabel;
    }

    private static void fadeOutAndRemove(StackPane root, Node node, Runnable callback) {
        FadeTransition ft = new FadeTransition(Duration.millis(150), node);
        ft.setToValue(0);
        ft.setOnFinished(e -> {
            root.getChildren().remove(node);
            if (callback != null) callback.run();
        });
        ft.play();
    }
}