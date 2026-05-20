package com.luoke.app.macher.player;

import com.luoke.app.config.PlayerConfig;
import com.luoke.app.context.MapContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家状态追踪器：负责位置平滑、方向修正、速度预测（用于 SIFT hint）。
 * <p>不再包含瞬移检测/地图切换/丢失恢复逻辑。</p>
 */
@Slf4j
public class PlayerStateTracker {

    /** 速度模平方阈值 — 低于此值时不进行反方向修正 */
    private static final double SPEED_THRESHOLD_SQ = 9.0;  // 3 px/frame
    /** 反方向修正角度阈值 — 速度方向与箭头朝向相差超过此值时翻转 180° */
    private static final double DIRECTION_ANGLE_THRESHOLD = 120.0;

    private boolean hasSmoothedPosition = false;
    private double smoothedX, smoothedY;

    // ROI 预测：基于 EMA 平滑速度预测下一帧位置
    private double prevRawX, prevRawY;
    private boolean hasPreviousMatch;
    private double velocityX, velocityY;

    /** 预测位置（用于 SIFT hint） */
    private Double predictedX, predictedY;

    public Double getPredictedX() { return predictedX; }
    public Double getPredictedY() { return predictedY; }

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

        // 反方向修正：速度方向与箭头朝向明显相反（>120°）时翻转 180°
        // 偶发误判场景：箭头凸包最小内角顶点选到了尾部而非尖端
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

        MapContext.getInstance().updatePlayerState(smoothedX, smoothedY, angle);

        // 速度预测：为 SIFT 匹配提供 hint
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
        hasPreviousMatch = false;
        predictedX = null;
        predictedY = null;
        velocityX = velocityY = 0;
    }
}
