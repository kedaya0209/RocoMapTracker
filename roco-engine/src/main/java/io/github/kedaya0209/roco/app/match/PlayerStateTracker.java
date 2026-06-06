package io.github.kedaya0209.roco.app.match;

import lombok.Getter;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.PlayerConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.PlayerStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家状态追踪器：负责位置平滑、方向修正、速度预测（用于 SIFT hint）。
 * <p>不再包含瞬移检测/地图切换/丢失恢复逻辑。</p>
 */
@NotThreadSafe
@Slf4j
public class PlayerStateTracker {

    private boolean hasSmoothedPosition = false;
    private double smoothedX, smoothedY;

    // 角度 EMA 平滑
    private boolean hasSmoothedAngle = false;
    private double smoothedAngle;

    // ROI 预测：基于 EMA 平滑速度预测下一帧位置
    private double prevRawX, prevRawY;
    private boolean hasPreviousMatch;
    private double velocityX, velocityY;

    /** 预测位置（用于 SIFT hint） */
    @Getter
    private Double predictedX;
    @Getter
    private Double predictedY;

    /**
     * 将角度差归一化到 [-180, 180] 范围
     */
    private static double normalizeAngleDiff(double diff) {
        while (diff > 180) diff -= 360;
        while (diff < -180) diff += 360;
        return diff;
    }

    /**
     * 将角度归一化到 [0, 360) 范围
     */
    private static double normalizeAngle(double angle) {
        double a = angle % 360;
        if (a < 0) a += 360;
        return a;
    }

    /**
     * 更新匹配成功时的位置
     *
     * @param x     匹配得到的 X 坐标
     * @param y     匹配得到的 Y 坐标
     * @param angle 箭头检测得到的朝向角度 (可为 null)
     */
    public void onMatchSuccess(double x, double y, Double angle) {
        // 首次定位 / 重置后：直接使用原始值
        if (!hasSmoothedPosition) {
            smoothedX = x;
            smoothedY = y;
            hasSmoothedPosition = true;
        } else {
            // EMA 平滑
            double alpha = PlayerConfig.PLAYER_EMA_ALPHA;
            smoothedX = alpha * x + (1 - alpha) * smoothedX;
            smoothedY = alpha * y + (1 - alpha) * smoothedY;
        }

        // 角度 EMA 平滑（处理 0/360 环绕）
        Double finalAngle = angle;
        if (angle != null) {
            if (!hasSmoothedAngle) {
                smoothedAngle = angle;
                hasSmoothedAngle = true;
            } else {
                double diff = normalizeAngleDiff(angle - smoothedAngle);
                double aAlpha = PlayerConfig.PLAYER_ANGLE_EMA_ALPHA;
                smoothedAngle = normalizeAngle(smoothedAngle + aAlpha * diff);
            }
            finalAngle = smoothedAngle;
        }

        MapContext.getInstance().updatePlayerState(smoothedX, smoothedY, finalAngle);
        AppEvents.publish(PlayerStateEvent.class, new PlayerStateEvent(smoothedX, smoothedY, finalAngle));

        // 速度预测：为 SIFT 匹配提供 hint（EMA 平滑稳定 hint）
        if (hasPreviousMatch) {
            double frameDx = x - prevRawX;
            double frameDy = y - prevRawY;
            double vAlpha = PlayerConfig.PLAYER_VELOCITY_EMA_ALPHA;
            velocityX = vAlpha * frameDx + (1 - vAlpha) * velocityX;
            velocityY = vAlpha * frameDy + (1 - vAlpha) * velocityY;
            predictedX = smoothedX + velocityX;
            predictedY = smoothedY + velocityY;
        }
        prevRawX = x;
        prevRawY = y;
        hasPreviousMatch = true;
    }

    /**
     * 处理匹配失败 — 仅记录日志，不做状态变更。
     */
    public void onMatchFailure(String reason) {
        // 预测位置可能变陈旧，SIFT hint 本就可以为 NaN，无需特殊处理
    }

    public void reset() {
        hasSmoothedPosition = false;
        smoothedX = smoothedY = 0;
        hasSmoothedAngle = false;
        smoothedAngle = 0;
        hasPreviousMatch = false;
        predictedX = null;
        predictedY = null;
        velocityX = velocityY = 0;
    }
}
