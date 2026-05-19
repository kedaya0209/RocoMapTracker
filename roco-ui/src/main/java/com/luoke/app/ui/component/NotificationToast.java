package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import com.luoke.app.config.RenderConfig;
import com.luoke.app.config.UiConfig;
import com.luoke.app.hook.event.NotificationType;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

public class NotificationToast extends HBox {

    public NotificationToast(String message, NotificationType type) {
        super(15);
        this.setAlignment(Pos.CENTER_LEFT);
        this.setPadding(new Insets(10, 20, 10, 20));
        this.setMaxWidth(UiConfig.TOAST_MAX_WIDTH);
        this.setMaxHeight(UiConfig.TOAST_MAX_HEIGHT);

        String color = colorOf(type);
        this.setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 8; " +
                "-fx-border-color: " + color + "; " +
                "-fx-border-width: 0 0 0 4; " +
                "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 10, 0, 0, 5);");

        SVGPath icon = new SVGPath();
        icon.setContent(iconPathOf(type));
        icon.setStyle("-fx-fill: " + color + ";");
        icon.setScaleX(0.8);
        icon.setScaleY(0.8);

        Label label = new Label(message);
        label.setStyle("-fx-text-fill: -color-fg-default;");
        label.getStyleClass().add(Styles.TEXT_BOLD);

        this.getChildren().addAll(icon, label);
    }

    // 将核心层 NotificationType 映射到 UI 层的颜色和图标
    private static String colorOf(NotificationType type) {
        return switch (type) {
            case SUCCESS -> "-color-success-emphasis";
            case ERROR -> "-color-danger-emphasis";
            case INFO -> "-color-accent-emphasis";
        };
    }

    private static String iconPathOf(NotificationType type) {
        return switch (type) {
            case SUCCESS -> "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z";
            case ERROR ->
                    "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z";
            case INFO ->
                    "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z";
        };
    }

    public static void show(StackPane root, String message, NotificationType type) {
        NotificationToast toast = new NotificationToast(message, type);
        StackPane.setAlignment(toast, Pos.TOP_CENTER);
        StackPane.setMargin(toast, new Insets(55, 0, 0, 0));

        toast.setTranslateY(-100); // 初始在屏幕上方可见区域外
        root.getChildren().add(toast);

        // 1. 滑入动画
        Timeline fadeIn = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(toast.translateYProperty(), -100)),
                new KeyFrame(Duration.millis(RenderConfig.TOAST_FADE_IN_MS), new KeyValue(toast.translateYProperty(), 0, Interpolator.EASE_OUT))
        );

        // 2. 自动滑出动画
        Timeline fadeOut = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(toast.translateYProperty(), 0)),
                new KeyFrame(Duration.millis(RenderConfig.TOAST_FADE_OUT_MS), new KeyValue(toast.translateYProperty(), -100, Interpolator.EASE_IN))
        );
        fadeOut.setDelay(Duration.seconds(RenderConfig.TOAST_DISPLAY_SEC)); // 停顿
        fadeOut.setOnFinished(_ -> root.getChildren().remove(toast));

        fadeIn.play();
        fadeIn.setOnFinished(_ -> fadeOut.play());
    }

}