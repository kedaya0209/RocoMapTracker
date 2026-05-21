package com.luoke.app.ui.component.setting;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.RenderConfig;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.utils.ResourceUtils;
import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家图标与光环实时预览 — 设置面板「玩家」分类顶部展示。
 * 暗色半透明底衬确保低透明度光环清晰可见，AnimationTimer {@code 60fps} 驱动动画，
 * 配置值按 {@link #REFRESH_INTERVAL} 帧间隔从控件读取缓存，减少高频读取开销。
 */
@NotThreadSafe
@Slf4j
public class PlayerPreview {

    private static final int MAX_RIPPLES = 10;
    private static final double PREVIEW_HEIGHT = 200;
    /** 配置值刷新间隔（帧数）：每 15 帧 ~4fps 刷新一次，减少控件读取开销 */
    private static final int REFRESH_INTERVAL = 15;

    private final Pane root;
    private final Circle backdrop;
    private final ImageView playerView;
    private final Circle[] ripples;
    private final Circle pickupHalo;
    private final double[] rippleProgress;
    private final AnimationTimer timer;
    private final SettingConfigManager configManager;

    // 缓存配置值，按 REFRESH_INTERVAL 批量刷新
    private double cachedGrayDist;
    private double cachedRippleStep;
    private double cachedRippleAlpha;
    private double cachedRippleStroke;
    private int cachedRippleCount;
    private double cachedHaloFreq;
    private double cachedHaloMinAlpha;
    private double cachedHaloMaxAlpha;
    private double cachedHaloStroke;
    private double cachedPlayerSize;

    public PlayerPreview(SettingConfigManager configManager) {
        this.configManager = configManager;

        root = new Pane();
        root.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 8; " +
                "-fx-border-color: -color-border-muted; -fx-border-width: 0 0 1 0;");
        root.setPrefHeight(PREVIEW_HEIGHT);
        root.setMinHeight(120);
        root.setMaxHeight(260);

        // --- 暗色底衬：为低透明度光环提供对比背景 ---
        backdrop = new Circle(0);
        backdrop.setFill(Color.rgb(0, 0, 0, 0.35));
        backdrop.setMouseTransparent(true);
        root.getChildren().add(backdrop);

        // --- 波纹（最多 10 圈） ---
        ripples = new Circle[MAX_RIPPLES];
        rippleProgress = new double[MAX_RIPPLES];
        for (int i = 0; i < MAX_RIPPLES; i++) {
            Circle r = new Circle(0);
            r.setFill(Color.TRANSPARENT);
            r.setStroke(Color.rgb(255, 255, 200, 0.5));
            r.setMouseTransparent(true);
            ripples[i] = r;
            root.getChildren().add(r);
            rippleProgress[i] = (double) i / MAX_RIPPLES;
        }

        // --- 光环 ---
        pickupHalo = new Circle(0);
        pickupHalo.setFill(Color.TRANSPARENT);
        pickupHalo.setStroke(Color.rgb(255, 255, 200, 0.3));
        pickupHalo.setMouseTransparent(true);
        root.getChildren().add(pickupHalo);

        // --- 玩家图标 ---
        playerView = new ImageView();
        playerView.setMouseTransparent(true);
        playerView.setPreserveRatio(true);
        try {
            Image img = new Image(ResourceUtils.getResourceStream(ResourceConfigContext.getPlayerIcon()));
            if (!img.isError()) {
                playerView.setImage(img);
            }
        } catch (Exception e) {
            log.warn("加载玩家图标失败", e);
        }
        root.getChildren().add(playerView);

        // --- 动画循环 ---
        timer = new AnimationTimer() {
            private long lastFrame = 0;
            private int frameCount = 0;

            @Override
            public void handle(long now) {
                if (lastFrame == 0) {
                    lastFrame = now;
                    return;
                }
                if (now - lastFrame < 16_000_000) return;
                lastFrame = now;
                frameCount++;
                // 每帧更新动画；每隔 REFRESH_INTERVAL 帧刷新配置缓存
                boolean refresh = (frameCount % REFRESH_INTERVAL) == 0;
                tick(frameCount, refresh);
            }
        };

        // 初始化配置缓存
        refreshCache();
    }

    private static double clamp(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    private double readDouble(String key, double fallback) {
        Object v = configManager.getCurrentValue(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    private int readInt(String key, int fallback) {
        Object v = configManager.getCurrentValue(key);
        return v instanceof Number n ? n.intValue() : fallback;
    }

    private void refreshCache() {
        cachedGrayDist = readDouble("GRAY_DISTANCE", ViewConfig.GRAY_DISTANCE);
        cachedRippleStep = readDouble("RIPPLE_STEP", RenderConfig.RIPPLE_STEP);
        cachedRippleAlpha = readDouble("RIPPLE_ALPHA", RenderConfig.RIPPLE_ALPHA);
        cachedRippleStroke = readDouble("RIPPLE_STROKE_WIDTH", RenderConfig.RIPPLE_STROKE_WIDTH);
        cachedRippleCount = Math.min(readInt("RIPPLE_COUNT", RenderConfig.RIPPLE_COUNT), MAX_RIPPLES);
        cachedHaloFreq = readDouble("HALO_BREATHE_FREQ", RenderConfig.HALO_BREATHE_FREQ);
        cachedHaloMinAlpha = readDouble("HALO_BREATHE_MIN_ALPHA", RenderConfig.HALO_BREATHE_MIN_ALPHA);
        cachedHaloMaxAlpha = readDouble("HALO_BREATHE_MAX_ALPHA", RenderConfig.HALO_BREATHE_MAX_ALPHA);
        cachedHaloStroke = readDouble("HALO_STROKE_WIDTH", RenderConfig.HALO_STROKE_WIDTH);
        cachedPlayerSize = readDouble("PLAYER_VIEW_SIZE", RenderConfig.PLAYER_VIEW_SIZE);
    }

    private void tick(int frameCount, boolean refresh) {
        if (refresh) refreshCache();
        double w = root.getWidth();
        double h = root.getHeight();
        if (w < 10 || h < 10) return;

        double cx = w / 2.0;
        double cy = h / 2.0;

        // 每帧从缓存读取配置值（由 refreshCache 按 REFRESH_INTERVAL 刷新）
        double grayDist = cachedGrayDist;
        double rippleStep = cachedRippleStep;
        double rippleAlpha = cachedRippleAlpha;
        double rippleStroke = cachedRippleStroke;
        int rippleCount = cachedRippleCount;
        double haloFreq = cachedHaloFreq;
        double haloMinAlpha = cachedHaloMinAlpha;
        double haloMaxAlpha = cachedHaloMaxAlpha;
        double haloStroke = cachedHaloStroke;
        double playerSize = cachedPlayerSize;

        // 等比缩放：确保光环不超出预览区域
        double maxR = Math.min(w, h) * 0.42;
        double scale = grayDist > 0 ? Math.min(1.0, maxR / grayDist) : 1.0;
        double displayRadius = grayDist * scale;

        // 玩家图标 — 每帧全量更新（无缓存，保证实时响应）
        double sps = playerSize * scale;
        playerView.setFitWidth(sps);
        playerView.setFitHeight(sps);
        playerView.setLayoutX(cx - sps / 2.0);
        playerView.setLayoutY(cy - sps / 2.0);

        // 底衬 — 半透明暗区，为光环/波纹提供对比
        backdrop.setRadius(displayRadius * 1.6);
        backdrop.setCenterX(cx);
        backdrop.setCenterY(cy);

        // 光环 — 透明度呼吸 + 描边
        pickupHalo.setRadius(displayRadius);
        pickupHalo.setCenterX(cx);
        pickupHalo.setCenterY(cy);
        pickupHalo.setStrokeWidth(haloStroke * scale);
        double breath = Math.sin(frameCount * haloFreq) * 0.5 + 0.5;
        double haloAlpha = haloMinAlpha + (haloMaxAlpha - haloMinAlpha) * breath;
        pickupHalo.setStroke(Color.rgb(255, 255, 200, clamp(haloAlpha)));

        // 波纹 — 错峰扩散，自动显隐
        for (int i = 0; i < rippleCount; i++) {
            rippleProgress[i] += rippleStep;
            if (rippleProgress[i] > 1.0) rippleProgress[i] -= 1.0;
            double p = rippleProgress[i];
            ripples[i].setRadius(p * displayRadius);
            ripples[i].setCenterX(cx);
            ripples[i].setCenterY(cy);
            ripples[i].setStroke(Color.rgb(255, 255, 200, clamp((1 - p) * rippleAlpha)));
            ripples[i].setStrokeWidth(rippleStroke * scale);
            ripples[i].setVisible(true);
        }
        for (int i = rippleCount; i < MAX_RIPPLES; i++) {
            ripples[i].setVisible(false);
        }
    }

    public Pane getNode() {
        return root;
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
    }
}
