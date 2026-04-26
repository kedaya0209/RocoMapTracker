package com.luoke.app.ui.component;

import atlantafx.base.theme.Styles;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

public class NotificationToast extends HBox {

    public NotificationToast(String message, Type type) {
        super(15);
        this.setAlignment(Pos.CENTER_LEFT);
        this.setPadding(new Insets(10, 20, 10, 20));
        this.setMaxWidth(400);
        this.setMaxHeight(50);

        // 样式：深色玻璃拟态
        this.setStyle(String.format(
                "-fx-background-color: #2c2c2c; " +
                        "-fx-background-radius: 8; " +
                        "-fx-border-color: %s; " +
                        "-fx-border-width: 0 0 0 4; " +
                        "-fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.4), 10, 0, 0, 5);", type.color));

        SVGPath icon = new SVGPath();
        icon.setContent(type.iconPath);
        icon.setFill(Color.web(type.color));
        icon.setScaleX(0.8);
        icon.setScaleY(0.8);

        Label label = new Label(message);
        label.setTextFill(Color.WHITE);
        label.getStyleClass().add(Styles.TEXT_BOLD);

        this.getChildren().addAll(icon, label);
    }

    public static void show(StackPane root, String message, Type type) {
        NotificationToast toast = new NotificationToast(message, type);
        StackPane.setAlignment(toast, Pos.TOP_CENTER);
        StackPane.setMargin(toast, new Insets(20, 0, 0, 0));

        toast.setTranslateY(-100); // 初始在屏幕上方可见区域外
        root.getChildren().add(toast);

        // 1. 滑入动画
        Timeline fadeIn = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(toast.translateYProperty(), -100)),
                new KeyFrame(Duration.millis(400), new KeyValue(toast.translateYProperty(), 0, Interpolator.EASE_OUT))
        );

        // 2. 自动滑出动画
        Timeline fadeOut = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(toast.translateYProperty(), 0)),
                new KeyFrame(Duration.millis(400), new KeyValue(toast.translateYProperty(), -100, Interpolator.EASE_IN))
        );
        fadeOut.setDelay(Duration.seconds(3)); // 停顿 3 秒
        fadeOut.setOnFinished(e -> root.getChildren().remove(toast));

        fadeIn.play();
        fadeIn.setOnFinished(e -> fadeOut.play());
    }

    public enum Type {
        SUCCESS("#2ecc71", "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"),
        ERROR("#e74c3c", "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"),
        INFO("#3498db", "M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 8 3.59 8 8-3.59 8-8 8z");

        final String color;
        final String iconPath;

        Type(String color, String iconPath) {
            this.color = color;
            this.iconPath = iconPath;
        }
    }
}