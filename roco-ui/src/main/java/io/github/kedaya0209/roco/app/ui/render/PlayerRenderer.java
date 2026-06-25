package io.github.kedaya0209.roco.app.ui.render;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Rotate;
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
    private double lastPlayerSize = -1;
    private final Translate playerTranslate;
    private final Rotate playerRotate;
    private double[] rippleProgress;
    /** 帧计数器，用于装饰效果节流 */
    private int frameCount;
    /** 装饰效果（波纹+光晕）更新间隔：每 N 帧更新一次，位置/旋转每帧更新 */
    private static final int DECORATION_INTERVAL = 2;

    /** 由 MapRenderer 在每帧开始时写入的快照，避免子渲染器独立读取 MapContext volatile 字段 */
    double snapshotScale, snapshotOx, snapshotOy;
    double snapshotPivotX, snapshotPivotY;
    double snapshotPlayerX, snapshotPlayerY;

    /** 渲染侧插值位置：每帧向快照位置 lerp，消除匹配更新间的离散跳跃感 */
    private double renderX = Double.NaN, renderY = Double.NaN;
    /** 渲染侧插值因子：0.5 在 60fps 下 3 帧收敛 87%，5 帧收敛 97%，无感知延迟 */
    private static final double LERP_FACTOR = 0.5;
    /** 等比例渲染后半宽/半高，setPlayerImage 时根据裁剪后宽高比计算 */
    private double halfW = 18, halfH = 18;

    public PlayerRenderer() {
        playerGroup = new Group();
        playerGroup.setPickOnBounds(false);
        playerGroup.setMouseTransparent(true);
        playerScale = new Scale(1, 1, 0, 0);
        playerTranslate = new Translate(0, 0);
        playerRotate = new Rotate(0, 0, 0);
        playerGroup.getTransforms().addAll(playerRotate, playerTranslate, playerScale);

        playerView = new ImageView();
        playerView.setFitWidth(RenderConfig.PLAYER_VIEW_SIZE);
        playerView.setPreserveRatio(true);
        playerView.setSmooth(true);
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

    /**
     * 设置玩家图标，自动裁剪空白并等比例渲染。
     */
    public void setPlayerImage(Image image) {
        CropResult result = cropPlayerImage(image);
        if (result.image() != null) {
            playerView.setImage(result.image());
        } else {
            playerView.setImage(image);
        }
        halfW = RenderConfig.PLAYER_VIEW_SIZE / 2.0;
        halfH = halfW * result.aspectRatio();
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
        double scale = snapshotScale;
        double ox = snapshotOx;
        double oy = snapshotOy;

        playerScale.setX(scale);
        playerScale.setY(scale);
        playerTranslate.setX(ox);
        playerTranslate.setY(oy);

        // 导航模式旋转变换 — 从 ViewportState 读取
        ViewportState vp = ViewportState.getInstance();
        if (vp.isNavMode() && vp.getNavAngle() != 0) {
            playerRotate.setPivotX(snapshotPivotX);
            playerRotate.setPivotY(snapshotPivotY);
            playerRotate.setAngle(-vp.getNavAngle());
        } else if (playerRotate.getAngle() != 0) {
            playerRotate.setAngle(0);
        }

        boolean initialized = vp.isPlayerInitialized();
        if (initialized && playerView.getImage() != null) {
            playerView.setVisible(true);
            if (lastPlayerSize != RenderConfig.PLAYER_VIEW_SIZE) {
                lastPlayerSize = RenderConfig.PLAYER_VIEW_SIZE;
                playerView.setFitWidth(RenderConfig.PLAYER_VIEW_SIZE);
            }

            // 动态重建波纹圈（RIPPLE_COUNT 变化时）
            if (RenderConfig.RIPPLE_COUNT != ripples.length) {
                rebuildRipples();
            }
            // 渲染侧位置插值：每帧向快照（EMA 平滑后的匹配坐标）lerp，
            // 消除匹配更新间的离散跳跃，使箭头移动位移量均匀一致
            double[] lerped = MapRenderer.lerpPoint(renderX, renderY,
                    snapshotPlayerX, snapshotPlayerY, LERP_FACTOR, 2500);
            renderX = lerped[0];
            renderY = lerped[1];
            double px = renderX;
            double py = renderY;
            playerView.setLayoutX(px - halfW);
            playerView.setLayoutY(py - halfH);
            // 导航模式下 group 层 Rotate(-navAngle) 已提供逆旋转，
            // setRotate 只需设置玩家真实朝向，无需再减 navAngle
            double playerAngle = vp.isHasAngle() ? vp.getPlayerAngle() : 0;
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
                updateRippleFrame(ripples, rippleProgress, RenderConfig.RIPPLE_COUNT,
                        RenderConfig.RIPPLE_STEP * DECORATION_INTERVAL,
                        ViewConfig.GRAY_DISTANCE, RenderConfig.RIPPLE_ALPHA,
                        RenderConfig.RIPPLE_STROKE_WIDTH);
                updateHaloBreath(pickupHalo, frameCount,
                        RenderConfig.HALO_BREATHE_FREQ,
                        RenderConfig.HALO_BREATHE_MIN_ALPHA,
                        RenderConfig.HALO_BREATHE_MAX_ALPHA,
                        ViewConfig.GRAY_DISTANCE,
                        RenderConfig.HALO_STROKE_WIDTH);
            }
        } else {
            playerView.setVisible(false);
        }
    }

    // ==================== 共享静态工具方法（PlayerPreview 复用） ====================

    /**
     * 裁剪图标透明空白边缘。
     *
     * @return 裁剪结果；裁剪失败时 {@link CropResult#image()} 为 null，调用方应回退使用原图
     */
    public static CropResult cropPlayerImage(Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (w <= 0 || h <= 0) return new CropResult(null, 1.0);
        PixelReader reader = image.getPixelReader();
        if (reader == null) return new CropResult(null, 1.0);
        int minX = w, minY = h, maxX = 0, maxY = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (reader.getColor(x, y).getOpacity() > 0) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (minX > maxX || minY > maxY) return new CropResult(null, 1.0);
        int cropW = maxX - minX + 1;
        int cropH = maxY - minY + 1;
        WritableImage cropped = new WritableImage(cropW, cropH);
        PixelWriter writer = cropped.getPixelWriter();
        for (int y = 0; y < cropH; y++) {
            for (int x = 0; x < cropW; x++) {
                writer.setColor(x, y, reader.getColor(minX + x, minY + y));
            }
        }
        return new CropResult(cropped, (double) cropH / cropW);
    }

    /**
     * 更新波纹扩散一帧。每调用一次 progress[i] 前进 step。
     */
    public static void updateRippleFrame(Circle[] ripples, double[] progress, int count,
                                          double step, double maxRadius, double alpha,
                                          double strokeWidth) {
        for (int i = 0; i < count; i++) {
            progress[i] += step;
            if (progress[i] > 1.0) progress[i] -= 1.0;
            double p = progress[i];
            ripples[i].setRadius(p * maxRadius);
            ripples[i].setStroke(Color.rgb(255, 255, 200, clampAlpha((1 - p) * alpha)));
            ripples[i].setStrokeWidth(strokeWidth);
        }
    }

    /**
     * 更新光环呼吸一帧。仅更新透明度与描边宽度，不更新圆心。
     */
    public static void updateHaloBreath(Circle halo, int frameCount,
                                         double freq, double minAlpha, double maxAlpha,
                                         double radius, double strokeWidth) {
        halo.setRadius(radius);
        halo.setStrokeWidth(strokeWidth);
        double breath = Math.sin(frameCount * freq) * 0.5 + 0.5;
        double alpha = minAlpha + (maxAlpha - minAlpha) * breath;
        halo.setStroke(Color.rgb(255, 255, 200, clampAlpha(alpha)));
    }

    private static double clampAlpha(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /**
     * 图标裁剪结果。
     */
    @ThreadSafe
    public record CropResult(WritableImage image, double aspectRatio) {}
}
