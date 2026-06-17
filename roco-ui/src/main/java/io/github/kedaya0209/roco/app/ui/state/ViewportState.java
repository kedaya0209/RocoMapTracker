package io.github.kedaya0209.roco.app.ui.state;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;

/**
 * 视口 + 相机 + 玩家状态 — JavaFX Property，可观测。
 * <p>
 * 视口由 MapRenderer 在帧循环中从 MapContext 同步。
 * 相机/玩家由 StateBridge 从 EventBus 同步。
 * </p>
 */
@NotThreadSafe
public class ViewportState {

    private static final ViewportState INSTANCE = new ViewportState();

    // ==== 视口（来自 MapContext） ====

    private final SimpleDoubleProperty scale = new SimpleDoubleProperty(1.0);
    private final SimpleDoubleProperty offsetX = new SimpleDoubleProperty(0);
    private final SimpleDoubleProperty offsetY = new SimpleDoubleProperty(0);
    private final SimpleDoubleProperty viewWidth = new SimpleDoubleProperty(0);
    private final SimpleDoubleProperty viewHeight = new SimpleDoubleProperty(0);

    // ==== 相机模式（来自 CameraContext） ====

    private final SimpleBooleanProperty followMode = new SimpleBooleanProperty(ViewConfig.DEFAULT_FOLLOW_MODE);
    private final SimpleDoubleProperty followScale = new SimpleDoubleProperty(ViewConfig.DEFAULT_FOLLOW_SCALE);
    private final SimpleBooleanProperty navMode = new SimpleBooleanProperty(false);
    private final SimpleDoubleProperty navAngle = new SimpleDoubleProperty(0);

    // ==== 玩家位置/朝向（来自 PlayerStateTracker → EventBus → StateBridge） ====

    private final SimpleDoubleProperty playerX = new SimpleDoubleProperty(-1);
    private final SimpleDoubleProperty playerY = new SimpleDoubleProperty(-1);
    private final SimpleDoubleProperty smoothedPlayerX = new SimpleDoubleProperty(-1);
    private final SimpleDoubleProperty smoothedPlayerY = new SimpleDoubleProperty(-1);
    private final SimpleDoubleProperty playerAngle = new SimpleDoubleProperty(0);
    private final SimpleBooleanProperty hasAngle = new SimpleBooleanProperty(false);
    private final SimpleBooleanProperty playerInitialized = new SimpleBooleanProperty(false);

    public static ViewportState getInstance() {
        return INSTANCE;
    }

    // ==== 视口 Property / getter ====

    public DoubleProperty scaleProperty() {
        return scale;
    }

    public double getScale() {
        return scale.get();
    }

    public DoubleProperty offsetXProperty() {
        return offsetX;
    }

    public double getOffsetX() {
        return offsetX.get();
    }

    public DoubleProperty offsetYProperty() {
        return offsetY;
    }

    public double getOffsetY() {
        return offsetY.get();
    }

    public DoubleProperty viewWidthProperty() {
        return viewWidth;
    }

    public double getViewWidth() {
        return viewWidth.get();
    }

    public DoubleProperty viewHeightProperty() {
        return viewHeight;
    }

    public double getViewHeight() {
        return viewHeight.get();
    }

    // ==== 相机 Property / getter ====

    public BooleanProperty followModeProperty() {
        return followMode;
    }

    public boolean isFollowMode() {
        return followMode.get();
    }

    public DoubleProperty followScaleProperty() {
        return followScale;
    }

    public double getFollowScale() {
        return followScale.get();
    }

    public BooleanProperty navModeProperty() {
        return navMode;
    }

    public boolean isNavMode() {
        return navMode.get();
    }

    public DoubleProperty navAngleProperty() {
        return navAngle;
    }

    public double getNavAngle() {
        return navAngle.get();
    }

    // ==== 写入口（仅由 StateBridge 或帧同步在 FX 线程调用） ====

    public void setScale(double s) {
        scale.set(s);
    }

    public void setOffsetX(double x) {
        offsetX.set(x);
    }

    public void setOffsetY(double y) {
        offsetY.set(y);
    }

    public void setViewWidth(double w) {
        viewWidth.set(w);
    }

    public void setViewHeight(double h) {
        viewHeight.set(h);
    }

    public void setFollowMode(boolean f) {
        followMode.set(f);
    }

    public void setFollowScale(double s) {
        followScale.set(s);
    }

    public void setNavMode(boolean n) {
        navMode.set(n);
    }

    public void setNavAngle(double a) {
        navAngle.set(a);
    }

    // ==== 玩家 Property（只读，外部不可写） ====

    public ReadOnlyDoubleProperty playerXProperty() {
        return playerX;
    }

    public double getPlayerX() {
        return playerX.get();
    }

    public ReadOnlyDoubleProperty playerYProperty() {
        return playerY;
    }

    public double getPlayerY() {
        return playerY.get();
    }

    public ReadOnlyDoubleProperty playerAngleProperty() {
        return playerAngle;
    }

    public double getPlayerAngle() {
        return playerAngle.get();
    }

    public ReadOnlyBooleanProperty hasAngleProperty() {
        return hasAngle;
    }

    public boolean isHasAngle() {
        return hasAngle.get();
    }

    public ReadOnlyBooleanProperty playerInitializedProperty() {
        return playerInitialized;
    }

    public boolean isPlayerInitialized() {
        return playerInitialized.get();
    }

    // ==== 平滑玩家位置（EMA，供显示用） ====

    public double getSmoothedPlayerX() {
        return smoothedPlayerX.get();
    }

    public double getSmoothedPlayerY() {
        return smoothedPlayerY.get();
    }

    /** 玩家位置写入口（仅由 StateBridge 在 FX 线程调用）。
     *  <p>x/y 为原始匹配坐标（置灰/缩放判定用，传送时直接跳跃），
     *  smoothedX/smoothedY 已由 PlayerStateTracker EMA 平滑（渲染用）。</p> */
    public void updatePlayerPosition(double x, double y, double smoothedX, double smoothedY, Double angle) {
        playerX.set(x);
        playerY.set(y);
        smoothedPlayerX.set(smoothedX);
        smoothedPlayerY.set(smoothedY);
        if (angle != null) {
            playerAngle.set(angle);
            hasAngle.set(true);
        }
        playerInitialized.set(true);
    }

    public void resetPlayer() {
        playerX.set(-1);
        playerY.set(-1);
        smoothedPlayerX.set(-1);
        smoothedPlayerY.set(-1);
        playerAngle.set(0);
        hasAngle.set(false);
        playerInitialized.set(false);
    }

    /** 初始化时从 MapContext 复制当前值。应在 FX 线程调用。 */
    public void syncFromMapContext() {
        MapContext mc = MapContext.getInstance();
        scale.set(mc.getScale());
        offsetX.set(mc.getOffsetX());
        offsetY.set(mc.getOffsetY());
        viewWidth.set(mc.getViewWidth());
        viewHeight.set(mc.getViewHeight());
    }
}
