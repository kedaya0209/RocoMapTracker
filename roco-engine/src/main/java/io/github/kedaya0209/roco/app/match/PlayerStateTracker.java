package io.github.kedaya0209.roco.app.match;

import lombok.Getter;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.PlayerStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家状态追踪器：负责方向修正、速度预测（用于 SIFT hint）。
 * <p>坐标直接使用原始匹配结果，不做 EMA 平滑（避免资源置灰判定滞后）。</p>
 */
@NotThreadSafe
@Slf4j
public class PlayerStateTracker {

    /** 速度模平方阈值 — 低于此值时不进行反方向修正 */
    private static final double SPEED_THRESHOLD_SQ = 9.0;  // 3 px/frame
    /** 反方向修正角度阈值 — 速度方向与箭头朝向相差超过此值时翻转 180° */
    private static final double DIRECTION_ANGLE_THRESHOLD = 120.0;
    // ROI 预测：基于原始帧差预测下一帧位置
    private double prevRawX, prevRawY;
    private boolean hasPreviousMatch;
    private double velocityX, velocityY;

    /** 预测位置（用于 SIFT hint） */
    @Getter
    private Double predictedX;
    @Getter
    private Double predictedY;

    /**
     * 更新匹配成功时的位置
     *
     * @param x     匹配得到的 X 坐标
     * @param y     匹配得到的 Y 坐标
     * @param angle 箭头检测得到的朝向角度 (可为 null)
     */
    public void onMatchSuccess(double x, double y, Double angle) {
        // 直接使用原始坐标，不做 EMA 平滑（避免资源置灰判定滞后）

        // 反方向修正（后备）：速度方向与箭头朝向明显相反（>120°）时翻转 180°
        if (angle != null && hasPreviousMatch) {
            double speedSq = velocityX * velocityX + velocityY * velocityY;
            if (speedSq > SPEED_THRESHOLD_SQ) {
                double moveAngle = Math.toDegrees(Math.atan2(velocityY, velocityX));
                if (moveAngle < 0) moveAngle += 360;
                moveAngle = (moveAngle + 90) % 360; // 统一到游戏坐标系 (0°=上)
                double diff = Math.abs(angle - moveAngle);
                diff = Math.min(diff, 360 - diff);
                if (diff > DIRECTION_ANGLE_THRESHOLD) {
                    angle = (angle + 180) % 360;
                }
            }
        }

        MapContext.getInstance().updatePlayerState(x, y, angle);
        AppEvents.publish(PlayerStateEvent.class, new PlayerStateEvent(x, y, angle));

        // 速度预测始终基于原始帧差
        if (hasPreviousMatch) {
            velocityX = x - prevRawX;
            velocityY = y - prevRawY;
            predictedX = x + velocityX;
            predictedY = y + velocityY;
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
        hasPreviousMatch = false;
        predictedX = null;
        predictedY = null;
        velocityX = velocityY = 0;
    }
}
