package com.luoke.app.ui.service.resource;

import net.jcip.annotations.ThreadSafe;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Transform;
import javafx.geometry.Bounds;
import javafx.util.Duration;
import java.util.List;

/**
 * SVG hover 画线动画 + CSS 主题色解析。
 */
@ThreadSafe
final class SvgAnimator {

    private SvgAnimator() {
    }

    static Node createHoverDrawIcon(
            String resourcePath, double size,
            Color strokeColor, double strokeWidth, int durationMillis) {

        StackPane box = new StackPane();
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);

        Group group;
        try {
            byte[] raw = SvgIconBuilder.loadRaw(resourcePath);
            group = SvgIconBuilder.buildBaseGroup(raw, size);
            group.setMouseTransparent(true);
            List<SVGPath> paths = SvgIconBuilder.collectPaths(group);

            for (SVGPath p : paths) {
                p.setFill(strokeColor);
            }
            group.getProperties().put("_adjStrokeWidth", strokeWidth);

            box.getChildren().add(group);
        } catch (Exception e) {
            return box;
        }

        box.setPickOnBounds(true);
        Group animGroup = group;
        box.setOnMouseEntered(_ -> animatePaths(animGroup, true, durationMillis));
        box.setOnMouseExited(_ -> animatePaths(animGroup, false, durationMillis));

        return box;
    }

    static Node createHoverDrawIcon(
            String resourcePath, double size,
            double strokeWidth, int durationMillis) {
        StackPane box = new StackPane();
        box.setPrefSize(size, size);
        box.setMinSize(size, size);
        box.setMaxSize(size, size);

        try {
            byte[] raw = SvgIconBuilder.loadRaw(resourcePath);
            Group group = SvgIconBuilder.buildBaseGroup(raw, size);
            group.setMouseTransparent(true);

            List<SVGPath> allPaths = SvgIconBuilder.collectPaths(group);
            double scale = 1.0;
            if (!allPaths.isEmpty()) {
                for (Transform t : allPaths.getFirst().getTransforms()) {
                    if (t instanceof Scale s) {
                        scale = s.getX();
                        break;
                    }
                }
                if (scale == 0) scale = 1.0;
            }
            double adjStrokeWidth = strokeWidth / scale;
            group.getProperties().put("_adjStrokeWidth", adjStrokeWidth);

            for (SVGPath p : allPaths) {
                p.setStyle("-fx-fill: -color-fg-default;");
            }
            box.getChildren().add(group);

            box.setPickOnBounds(true);
            box.setOnMouseEntered(_ -> animatePaths(group, true, durationMillis));
            box.setOnMouseExited(_ -> animatePaths(group, false, durationMillis));

            deferredColorUpdate(box, group);
        } catch (Exception e) {
            return box;
        }

        return box;
    }

    static void animateHoverDrawIcon(Node iconNode, boolean enter, int durationMillis) {
        if (iconNode instanceof StackPane box) {
            for (Node child : box.getChildren()) {
                if (child instanceof Group group) {
                    animatePaths(group, enter, durationMillis);
                    return;
                }
            }
        }
    }

    // ================================================================
    // 内部实现
    // ================================================================

    private static void deferredColorUpdate(StackPane box, Group group) {
        Runnable task = () -> {
            if (box.getScene() == null) return;
            Color fillColor = resolveCssColor(group, "-color-fg-default");
            if (fillColor == null) fillColor = Color.web("#24292f");
            Color accent = resolveCssColor(group, "-color-accent-emphasis");
            if (accent == null) accent = Color.web("#0969da");
            group.getProperties().put("_fgFill", fillColor);
            group.getProperties().put("_accent", accent);
        };
        if (box.getScene() != null) {
            Platform.runLater(task);
        } else {
            box.sceneProperty().addListener(new ChangeListener<Scene>() {
                @Override
                public void changed(ObservableValue<? extends Scene> obs,
                                    Scene oldScene, Scene newScene) {
                    if (newScene != null) {
                        box.sceneProperty().removeListener(this);
                        Platform.runLater(task);
                    }
                }
            });
        }
    }

    private static Color resolveCssColor(Group group, String cssVariable) {
        try {
            Rectangle tmp = new Rectangle();
            tmp.setStyle("-fx-fill: " + cssVariable + ";");
            group.getChildren().add(tmp);
            tmp.applyCss();
            Paint p = tmp.getFill();
            group.getChildren().remove(tmp);
            return p instanceof Color c ? c : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void animatePaths(Group group, boolean enter, int durationMillis) {
        Timeline prev = (Timeline) group.getProperties().get("_drawAnim");
        if (prev != null) {
            prev.stop();
            restorePathsPostAnim(group);
        }

        List<SVGPath> paths = SvgIconBuilder.collectPaths(group);
        if (paths.isEmpty()) return;

        Color strokeColor = (Color) group.getProperties().get("_fgFill");
        if (strokeColor == null) {
            Paint s = paths.getFirst().getStroke();
            if (s instanceof Color c) strokeColor = c;
            if (strokeColor == null) {
                Paint f = paths.getFirst().getFill();
                if (f instanceof Color c) strokeColor = c;
            }
        }
        if (strokeColor == null) strokeColor = Color.web("#24292f");

        double adjStrokeWidth = (double) group.getProperties().getOrDefault("_adjStrokeWidth", 0.0);
        if (adjStrokeWidth <= 0) {
            adjStrokeWidth = paths.getFirst().getStrokeWidth();
            if (adjStrokeWidth <= 0) adjStrokeWidth = 1.5;
        }

        for (SVGPath p : paths) {
            double len = Math.max(SvgPathUtil.computePathLength(p), 10);

            p.getProperties().put("_savedFill", p.getFill());
            p.setFill(Color.TRANSPARENT);

            if (p.getStroke() == null) {
                p.setStroke(strokeColor);
                p.setStrokeWidth(adjStrokeWidth);
                p.setStrokeLineCap(StrokeLineCap.ROUND);
                p.setStrokeLineJoin(StrokeLineJoin.ROUND);
                p.getStrokeDashArray().setAll(len, len);
            }
            p.setStrokeDashOffset(enter ? len : 0.0);
        }

        KeyValue[] kvs = new KeyValue[paths.size()];
        for (int i = 0; i < paths.size(); i++) {
            double totalLen = Math.max(SvgPathUtil.computePathLength(paths.get(i)), 10);
            kvs[i] = new KeyValue(paths.get(i).strokeDashOffsetProperty(),
                    enter ? 0.0 : totalLen, Interpolator.EASE_BOTH);
        }

        Timeline tl = new Timeline(new KeyFrame(Duration.millis(durationMillis), kvs));
        tl.setOnFinished(_ -> restorePathsPostAnim(group));
        group.getProperties().put("_drawAnim", tl);
        tl.play();
    }

    private static void restorePathsPostAnim(Group group) {
        for (SVGPath p : SvgIconBuilder.collectPaths(group)) {
            Color saved = (Color) p.getProperties().remove("_savedFill");
            if (saved != null) p.setFill(saved);
            p.setStroke(null);
            p.getStrokeDashArray().clear();
        }
    }
}
