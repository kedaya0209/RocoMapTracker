package com.luoke.app.ui.util;

import net.jcip.annotations.ThreadSafe;
import atlantafx.base.theme.Styles;
import java.io.IOException;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.util.Duration;

import com.luoke.app.ui.service.SvgManager;

@ThreadSafe
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
                                         String confirmText,
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
        buildBaseDialog(rootStack, title, content, confirmBtnText, confirmBtnStyleClass,
                iconStyle, onConfirm, onCancel, null);
    }

    /** 带自定义图标的弹窗（默认最大高度 320） */
    private static void buildBaseDialog(StackPane rootStack,
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

    /** 完整参数的弹窗（指定最大高度） */
    private static void buildBaseDialog(StackPane rootStack,
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

        // 图标
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

        // 标题
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        // --- 核心居中处理层 ---
        StackPane contentContainer = new StackPane();
        contentContainer.setAlignment(Pos.CENTER);
        contentContainer.getChildren().add(content);
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
            cancelBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
            FxRippleUtil.install(cancelBtn);
            cancelBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onCancel));
            btnBox.getChildren().add(cancelBtn);
        }

        dialogBox.getChildren().addAll(icon, titleLabel, contentContainer, btnBox);
        mask.getChildren().add(dialogBox);
        rootStack.getChildren().add(mask);

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();
    }

    /**
     * 更新确认弹窗 — 显示版本号和 release notes
     */
    public static void showUpdateDialog(StackPane rootStack,
                                         String title,
                                         String currentVersion,
                                         String newVersion,
                                         String releaseNotes,
                                         Runnable onConfirm,
                                         Runnable onCancel) {
        VBox content = new VBox(8);
        content.setMaxWidth(Double.MAX_VALUE);
        content.setAlignment(Pos.TOP_LEFT);

        Label currentLabel = new Label("当前版本: " + currentVersion);
        currentLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        Label newLabel = new Label("最新版本: " + newVersion);
        newLabel.setStyle("-fx-text-fill: -color-success-emphasis; -fx-font-size: 13px; -fx-font-weight: bold;");

        content.getChildren().addAll(currentLabel, newLabel);

        if (releaseNotes != null && !releaseNotes.isBlank()) {
            Label notesHeader = new Label("更新内容");
            notesHeader.setStyle("-fx-text-fill: -color-fg-default; -fx-font-size: 13px; -fx-font-weight: bold;");
            notesHeader.setPadding(new Insets(6, 0, 0, 0));

            TextArea notesArea = new TextArea(releaseNotes.strip());
            notesArea.setEditable(false);
            notesArea.setWrapText(true);
            notesArea.setMaxHeight(280);
            notesArea.setPrefHeight(220);
            notesArea.setStyle(
                    "-fx-background-color: -color-bg-subtle; " +
                    "-fx-text-fill: -color-fg-muted; " +
                    "-fx-font-size: 12px; " +
                    "-fx-background-radius: 6; " +
                    "-fx-border-color: -color-border-muted; " +
                    "-fx-border-radius: 6;");

            content.getChildren().add(notesArea);
        }

        buildBaseDialog(rootStack, title, content, "立即更新", Styles.SUCCESS,
                "-color-accent-emphasis", onConfirm, onCancel, null, 520);
    }

    /**
     * 关于弹窗 — 左右布局：图标 | 项目信息
     */
    public static void showAboutDialog(StackPane rootStack,
                                        String appName,
                                        String version,
                                        String buildTimestamp,
                                        String repoUrl) {
        // 左侧图标
        Node iconNode;
        try {
            iconNode = SvgManager.createIcon("/icon/rmt.svg", 64, null);
        } catch (Exception e) {
            SVGPath fallback = new SVGPath();
            fallback.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-2h2v2zm0-4h-2V7h2v6z");
            fallback.setStyle("-fx-fill: -color-accent-emphasis;");
            iconNode = fallback;
        }

        // 右侧信息
        VBox infoBox = new VBox(6);
        infoBox.setAlignment(Pos.CENTER_LEFT);

        Label nameLabel = new Label(appName);
        nameLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label versionLabel = new Label("版本: " + version);
        versionLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        infoBox.getChildren().addAll(nameLabel, versionLabel);

        if (buildTimestamp != null && !buildTimestamp.isBlank()
                && !"unknown".equals(buildTimestamp) && !"${maven.build.timestamp}".equals(buildTimestamp)) {
            Label timeLabel = new Label("构建时间: " + buildTimestamp);
            timeLabel.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px;");
            infoBox.getChildren().add(timeLabel);
        }

        if (repoUrl != null && !repoUrl.isBlank()) {
            Label repoPrefix = new Label("项目地址: ");
            repoPrefix.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 12px;");
            Hyperlink link = new Hyperlink(repoUrl);
            link.setStyle("-fx-font-size: 12px; -fx-text-fill: -color-accent-emphasis;");
            link.setOnAction(e -> {
                try {
                    new ProcessBuilder("cmd", "/c", "start", repoUrl).start();
                } catch (IOException ex) {
                    // ignore
                }
            });
            FlowPane repoRow = new FlowPane(0, 2);
            repoRow.setAlignment(Pos.CENTER_LEFT);
            repoRow.getChildren().addAll(repoPrefix, link);
            infoBox.getChildren().add(repoRow);
        }

        // 左右容器
        HBox content = new HBox(20);
        content.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().addAll(iconNode, infoBox);

        buildBaseDialog(rootStack, "关于", content, "确定", Styles.SUCCESS,
                "-color-accent-emphasis", () -> {}, null, null);
    }

    /**
     * 下载进度控制 — 可在任意线程调用 update/close，自动切换到 JavaFX 线程
     */
    public static class ProgressControl {
        private final StackPane mask;
        private final ProgressBar progressBar;
        private final Label statusLabel;

        ProgressControl(StackPane mask, ProgressBar progressBar, Label statusLabel) {
            this.mask = mask;
            this.progressBar = progressBar;
            this.statusLabel = statusLabel;
        }

        public void updateProgress(double progress, String text) {
            Platform.runLater(() -> {
                if (progress < 0) {
                    progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                } else {
                    progressBar.setProgress(Math.clamp(progress, 0, 1));
                }
                if (text != null) statusLabel.setText(text);
            });
        }

        public void close() {
            Platform.runLater(() -> {
                Node parentNode = mask.getParent();
                if (!(parentNode instanceof StackPane parent)) return;
                FadeTransition ft = new FadeTransition(Duration.millis(150), mask);
                ft.setToValue(0);
                ft.setOnFinished(e -> parent.getChildren().remove(mask));
                ft.play();
            });
        }
    }

    /**
     * 下载进度弹窗
     * @param onBackground 非 null 时显示"后台下载"按钮
     */
    public static ProgressControl showDownloadProgressDialog(StackPane rootStack, String version, Runnable onBackground) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = new VBox(20);
        dialogBox.setMaxSize(420, 240);
        dialogBox.setPadding(new Insets(30));
        dialogBox.setAlignment(Pos.CENTER);
        dialogBox.setStyle(
                "-fx-background-color: -color-bg-default; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 12; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-width: 1.5;");

        Label titleLabel = new Label("正在下载 " + version + " ...");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(360);
        progressBar.getStyleClass().add(Styles.MEDIUM);

        Label statusLabel = new Label("准备下载...");
        statusLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");

        dialogBox.getChildren().addAll(titleLabel, progressBar, statusLabel);

        if (onBackground != null) {
            Button bgBtn = new Button("后台下载");
            bgBtn.setPrefWidth(120);
            bgBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.ACCENT);
            FxRippleUtil.install(bgBtn);
            bgBtn.setOnAction(e -> {
                mask.setOpacity(0);
                rootStack.getChildren().remove(mask);
                onBackground.run();
            });
            dialogBox.getChildren().add(bgBtn);
        }

        mask.getChildren().add(dialogBox);
        rootStack.getChildren().add(mask);

        mask.setOpacity(0);
        FadeTransition ft = new FadeTransition(Duration.millis(200), mask);
        ft.setToValue(1);
        ft.play();

        return new ProgressControl(mask, progressBar, statusLabel);
    }

    /**
     * 更新就绪弹窗 — 用户选择立即更新或下次再说
     */
    public static void showUpdateReadyDialog(StackPane rootStack,
                                              String version,
                                              Runnable onInstallNow,
                                              Runnable onLater) {
        StackPane mask = new StackPane();
        mask.setStyle("-fx-background-color: rgba(0, 0, 0, 0.8);");

        VBox dialogBox = new VBox(25);
        dialogBox.setMaxSize(420, 260);
        dialogBox.setPadding(new Insets(30));
        dialogBox.setAlignment(Pos.CENTER);
        dialogBox.setStyle(
                "-fx-background-color: -color-bg-default; " +
                        "-fx-border-color: -color-border-muted; " +
                        "-fx-border-radius: 12; " +
                        "-fx-background-radius: 12; " +
                        "-fx-border-width: 1.5; " +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.5), 20, 0, 0, 10);");

        SVGPath icon = new SVGPath();
        icon.setContent("M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z");
        icon.setStyle("-fx-fill: -color-accent-emphasis;");
        icon.setScaleX(1.8);
        icon.setScaleY(1.8);

        Label titleLabel = new Label("更新就绪");
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");

        Label msgLabel = new Label("版本 " + version + " 已下载完成");
        msgLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 14px;");

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button laterBtn = new Button("下次再说");
        laterBtn.setPrefWidth(120);
        laterBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED);
        FxRippleUtil.install(laterBtn);

        Button installBtn = new Button("立即更新");
        installBtn.setPrefWidth(120);
        installBtn.getStyleClass().addAll(Styles.BUTTON_OUTLINED, Styles.SUCCESS);
        FxRippleUtil.install(installBtn);

        btnBox.getChildren().addAll(laterBtn, installBtn);
        dialogBox.getChildren().addAll(icon, titleLabel, msgLabel, btnBox);
        mask.getChildren().add(dialogBox);
        rootStack.getChildren().add(mask);

        // 按钮事件（使用 fadeOutAndRemove 统一关闭弹窗）
        installBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onInstallNow));
        laterBtn.setOnAction(e -> fadeOutAndRemove(rootStack, mask, onLater));

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