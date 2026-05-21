package com.luoke.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.RenderConfig;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.context.MapContext;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

/**
 * 玩家渲染器 — 玩家图标 + 潮汐波纹 + 拾取光环（GPU 变换）。
 * <p>
 * 维护独立的 playerGroup，应用世界 Scale/Translate 变换，
 * 确保玩家与地图使用相同坐标空间。
 */
@NotThreadSafe
public class PlayerRenderer implements RenderLayer {

    private final Group playerGroup;
    private final ImageView playerView;
    private Circle[] ripples;
    private final Circle pickupHalo;
    private final Scale playerScale;
    private final Translate playerTranslate;
    private double[] rippleProgress;
    /** 帧计数器，用于装饰效果节流 */
    private int frameCount;
    /** 装饰效果（波纹+光晕）更新间隔：每 N 帧更新一次，位置/旋转每帧更新 */
    private static final int DECORATION_INTERVAL = 2;

    public PlayerRenderer() {
        playerGroup = new Group();
        playerGroup.setPickOnBounds(false);
        playerGroup.setMouseTransparent(true);
        playerScale = new Scale(1, 1, 0, 0);
        playerTranslate = new Translate(0, 0);
        playerGroup.getTransforms().addAll(playerTranslate, playerScale);

        playerView = new ImageView();
        playerView.setFitWidth(RenderConfig.PLAYER_VIEW_SIZE);
        playerView.setFitHeight(RenderConfig.PLAYER_VIEW_SIZE);
        playerView.setMouseTransparent(true);
        playerView.setVisible(false);

        // 潮汐波纹（N 圈从中心扩散到 GRAY_DISTANCE，边扩散边淡出）
        int n = RenderConfig.RIPPLE_COUNT;
        ripples = new Circle[n];
        rippleProgress = new double[n];
        for (int i = 0; i < n; i++) {
            rippleProgress[i] = (double) i / n;
            Circle r = new Circle(0);
            r.setFill(Color.TRANSPARENT);
            r.setStroke(Color.rgb(255, 255, 200, RenderConfig.RIPPLE_ALPHA));
            r.setStrokeWidth(RenderConfig.RIPPLE_STROKE_WIDTH);
            r.setMouseTransparent(true);
            playerGroup.getChildren().add(r);
            ripples[i] = r;
        }

        // 拾取范围边界（静态浅圈，标示最大范围）
        pickupHalo = new Circle(ViewConfig.GRAY_DISTANCE);
        pickupHalo.setFill(Color.TRANSPARENT);
        pickupHalo.setStroke(Color.rgb(255, 255, 200, RenderConfig.HALO_BREATHE_MIN_ALPHA));
        pickupHalo.setStrokeWidth(RenderConfig.HALO_STROKE_WIDTH);
        pickupHalo.setMouseTransparent(true);
        playerGroup.getChildren().add(pickupHalo);

        playerGroup.getChildren().add(playerView);
    }

    @Override
    public Node getNode() {
        return playerGroup;
    }

    public void setPlayerImage(Image image) {
        playerView.setImage(image);
    }

    /**
     * 动态重建波纹圈，当设置中 RIPPLE_COUNT 变更时调用。
     */
    private void rebuildRipples() {
        int n = RenderConfig.RIPPLE_COUNT;
        if (n == ripples.length) return;
        // 移除旧波纹
        for (Circle r : ripples) {
            playerGroup.getChildren().remove(r);
        }
        // 在 pickupHalo 之前插入新波纹
        int insertIdx = Math.max(0, playerGroup.getChildren().indexOf(pickupHalo));
        Circle[] newRipples = new Circle[n];
        double[] newProgress = new double[n];
        for (int i = 0; i < n; i++) {
            newProgress[i] = (double) i / n;
            Circle r = new Circle(0);
            r.setFill(Color.TRANSPARENT);
            r.setStroke(Color.rgb(255, 255, 200, RenderConfig.RIPPLE_ALPHA));
            r.setStrokeWidth(RenderConfig.RIPPLE_STROKE_WIDTH);
            r.setMouseTransparent(true);
            playerGroup.getChildren().add(insertIdx + i, r);
            newRipples[i] = r;
        }
        ripples = newRipples;
        rippleProgress = newProgress;
    }

    @Override
    public void onFrame() {
        frameCount++;
        MapContext mm = MapContext.getInstance();
        CameraContext cam = CameraContext.getInstance();
        double scale = mm.getScale();
        double ox = mm.getOffsetX();
        double oy = mm.getOffsetY();

        playerScale.setX(scale);
        playerScale.setY(scale);
        playerTranslate.setX(ox);
        playerTranslate.setY(oy);

        if (mm.isPlayerInitialized() && playerView.getImage() != null) {
            playerView.setVisible(true);
            // 每帧同步显示尺寸，确保设置中修改 PLAYER_VIEW_SIZE 实时生效
            playerView.setFitWidth(RenderConfig.PLAYER_VIEW_SIZE);
            playerView.setFitHeight(RenderConfig.PLAYER_VIEW_SIZE);

            // 动态重建波纹圈（RIPPLE_COUNT 变化时）
            if (RenderConfig.RIPPLE_COUNT != ripples.length) {
                rebuildRipples();
            }
            double half = playerView.getFitWidth() / 2.0;
            double px = mm.getPlayerX();
            double py = mm.getPlayerY();
            playerView.setLayoutX(px - half);
            playerView.setLayoutY(py - half);
            // 导航模式下 counter-rotate 使图标保持指上
            double playerAngle = mm.getPlayerAngle();
            if (cam.isNavMode()) {
                playerAngle -= cam.getNavAngle();
            }
            playerView.setRotate(playerAngle);

            // 光环和波纹中心每帧追踪玩家位置
            pickupHalo.setCenterX(px);
            pickupHalo.setCenterY(py);
            for (int i = 0; i < RenderConfig.RIPPLE_COUNT; i++) {
                ripples[i].setCenterX(px);
                ripples[i].setCenterY(py);
            }

            // 装饰效果（波纹扩散 + 光晕呼吸）每 DECORATION_INTERVAL 帧更新一次，降低 CPU
            if ((frameCount % DECORATION_INTERVAL) == 0) {
                // 潮汐波纹：N 圈错峰扩散
                for (int i = 0; i < RenderConfig.RIPPLE_COUNT; i++) {
                    rippleProgress[i] += RenderConfig.RIPPLE_STEP * DECORATION_INTERVAL;
                    if (rippleProgress[i] > 1.0) rippleProgress[i] -= 1.0;
                    double p = rippleProgress[i];
                    ripples[i].setRadius(p * ViewConfig.GRAY_DISTANCE);
                    ripples[i].setStroke(Color.rgb(255, 255, 200, (1 - p) * RenderConfig.RIPPLE_ALPHA));
                }

                // 边界慢呼吸：周期约 7s，仅透明度变化，范围不变
                double breath = Math.sin(frameCount * RenderConfig.HALO_BREATHE_FREQ) * 0.5 + 0.5;
                double base = RenderConfig.HALO_BREATHE_MIN_ALPHA;
                double range = RenderConfig.HALO_BREATHE_MAX_ALPHA - base;
                pickupHalo.setStroke(Color.rgb(255, 255, 200, base + breath * range));
            }
        } else {
            playerView.setVisible(false);
        }
    }
}
