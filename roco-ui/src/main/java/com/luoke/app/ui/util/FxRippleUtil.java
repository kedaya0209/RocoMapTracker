package com.luoke.app.ui.util;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * 为任意 Region 安装 Material Ripple 效果。
 * <p>
 * ripplePane 直接挂到 scene root 上，通过 clip 限制在按钮区域内，
 * 完全脱离按钮父容器的布局链，避免 layout 闪烁。
 * 不修改 Button 本身，不破坏 CSS、pseudo class、accent theme。
 */
public final class FxRippleUtil {

    private FxRippleUtil() {
    }

    /**
     * 给 Button（或任意 Region）安装 ripple 效果。
     */
    public static void install(Region button) {
        // 使用 addEventHandler 不覆盖已有 handler
        button.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            Scene scene = button.getScene();
            if (scene == null) return;
            if (!(scene.getRoot() instanceof Pane rootPane)) return;

            // 按钮在场景中的坐标范围
            Bounds b = button.localToScene(button.getLayoutBounds());
            if (b.getWidth() <= 0 || b.getHeight() <= 0) return;

            // 临时 ripplePane 直接挂到 root，位置对齐按钮
            Pane ripplePane = new Pane();
            ripplePane.setManaged(false);
            ripplePane.setMouseTransparent(true);
            ripplePane.setPickOnBounds(false);
            ripplePane.setLayoutX(b.getMinX());
            ripplePane.setLayoutY(b.getMinY());

            // clip 防止水波溢出按钮区域
            Rectangle clip = new Rectangle(b.getWidth(), b.getHeight());
            clip.setArcWidth(0);
            clip.setArcHeight(0);
            ripplePane.setClip(clip);

            // 计算点击位置（相对于按钮）
            double x = e.getSceneX() - b.getMinX();
            double y = e.getSceneY() - b.getMinY();

            createRipple(ripplePane, x, y, button);

            rootPane.getChildren().add(ripplePane);
        });
    }

    private static void createRipple(Pane layer, double x, double y, Region button) {
        double radius = Math.max(button.getWidth(), button.getHeight()) * 1.3;

        Circle ripple = new Circle();
        ripple.setCenterX(x);
        ripple.setCenterY(y);
        ripple.setRadius(0);
        ripple.setOpacity(0.22);
        ripple.setFill(resolveRippleColor(button));

        layer.getChildren().add(ripple);

        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(ripple.radiusProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(ripple.opacityProperty(), 0.22)),
                new KeyFrame(Duration.millis(450),
                        new KeyValue(ripple.radiusProperty(), radius, Interpolator.EASE_OUT),
                        new KeyValue(ripple.opacityProperty(), 0))
        );

        tl.setOnFinished(_ -> {
            Parent p = layer.getParent();
            if (p instanceof Pane parentPane) {
                parentPane.getChildren().remove(layer);
            }
        });
        tl.play();
    }

    private static Color resolveRippleColor(Region node) {
        // accent / primary 按钮使用白色水波（在彩色背景上可见）
        if (node.getStyleClass().contains("accent")
                || node.getStyleClass().contains("primary")) {
            return Color.WHITE;
        }
        // 其余用半透明黑
        return Color.rgb(0, 0, 0, 0.18);
    }
}
