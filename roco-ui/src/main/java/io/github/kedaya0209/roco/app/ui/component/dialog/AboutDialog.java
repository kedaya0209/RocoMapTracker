package io.github.kedaya0209.roco.app.ui.component.dialog;

import net.jcip.annotations.NotThreadSafe;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.io.IOException;

import io.github.kedaya0209.roco.app.ui.service.resource.SvgManager;
import io.github.kedaya0209.roco.app.ui.util.FxRippleUtil;

@NotThreadSafe
public class AboutDialog {

    private AboutDialog() {}

    public static void showAboutDialog(StackPane rootStack,
                                        String appName,
                                        String version,
                                        String buildTimestamp,
                                        String repoUrl) {
        StackPane mask = AbstractDialog.createMask();

        VBox dialogBox = AbstractDialog.createDialogBox();

        Node iconNode;
        try {
            iconNode = SvgManager.createIcon("/icon/rmt.svg", 64, null);
        } catch (Exception e) {
            SVGPath fallback = AbstractDialog.createDefaultIcon("-color-accent-emphasis");
            iconNode = fallback;
        }

        VBox body = new VBox(10);
        body.setAlignment(Pos.CENTER);
        body.getChildren().add(iconNode);

        Label nameLabel = new Label(appName);
        nameLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -color-fg-default;");
        body.getChildren().add(nameLabel);

        Label versionLabel = new Label("版本: " + version);
        versionLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 13px;");
        body.getChildren().add(versionLabel);

        if (buildTimestamp != null && !buildTimestamp.isBlank()
                && !"unknown".equals(buildTimestamp) && !"${maven.build.timestamp}".equals(buildTimestamp)) {
            Label timeLabel = new Label("构建时间: " + buildTimestamp);
            timeLabel.setStyle("-fx-text-fill: -color-fg-subtle; -fx-font-size: 11px;");
            body.getChildren().add(timeLabel);
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
            repoRow.setAlignment(Pos.CENTER);
            repoRow.getChildren().addAll(repoPrefix, link);
            body.getChildren().add(repoRow);
        }

        Label disclaimer = new Label("仅供学习交流使用，一切后果由使用者自行承担。");
        disclaimer.setStyle("-fx-text-fill: #FF9800; -fx-font-size: 12px;"
                + "-fx-padding: 10 14; -fx-background-color: rgba(255,152,0,0.1);"
                + "-fx-background-radius: 6;");
        disclaimer.setWrapText(true);
        disclaimer.setMaxWidth(380);

        VBox wrapper = new VBox(14, body, disclaimer);
        wrapper.setAlignment(Pos.CENTER);

        StackPane contentContainer = new StackPane();
        contentContainer.setAlignment(Pos.CENTER);
        contentContainer.getChildren().add(wrapper);
        VBox.setVgrow(contentContainer, Priority.ALWAYS);

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);
        Button confirmBtn = AbstractDialog.createButton("确定", atlantafx.base.theme.Styles.SUCCESS,
                () -> rootStack.getChildren().remove(mask));
        btnBox.getChildren().add(confirmBtn);

        dialogBox.getChildren().addAll(contentContainer, btnBox);
        mask.getChildren().add(dialogBox);

        mask.setOnMouseClicked(e -> {
            javafx.animation.FadeTransition ft = new javafx.animation.FadeTransition(Duration.millis(150), mask);
            ft.setToValue(0);
            ft.setOnFinished(ev -> rootStack.getChildren().remove(mask));
            ft.play();
        });
        dialogBox.setOnMouseClicked(Event::consume);

        rootStack.getChildren().add(mask);
        AbstractDialog.fadeIn(mask);
    }
}
