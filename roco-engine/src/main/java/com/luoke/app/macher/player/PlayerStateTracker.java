package com.luoke.app.macher.player;

import com.luoke.app.context.MapContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家状态追踪器：负责位置平滑、瞬移检测、地图丢失标志。
 */
@Slf4j
public class PlayerStateTracker {

    private static final double EMA_ALPHA = 0.35;
    private static final double TELEPORT_THRESHOLD = 150.0;
    private static final double VELOCITY_EMA_ALPHA = 0.5;

    private boolean hasSmoothedPosition = false;
    private double smoothedX, smoothedY;
    private int consecutiveFailureCount = 0;
    @Getter
    private boolean isMapLost = false;

    // ROI 预测：基于 EMA 平滑速度预测下一帧位置
    private double prevRawX, prevRawY;
    private boolean hasPreviousMatch;
    private double velocityX, velocityY;
    @Getter
    private Double predictedX, predictedY;

    /**
     * 更新匹配成功时的位置
     *
     * @param x 匹配得到的 X 坐标
     * @param y 匹配得到的 Y 坐标
     */
    public void onMatchSuccess(double x, double y) {
        consecutiveFailureCount = 0;
        isMapLost = false;

        if (!hasSmoothedPosition) {
            smoothedX = x;
            smoothedY = y;
            hasSmoothedPosition = true;
        } else {
            double dx = x - smoothedX;
            double dy = y - smoothedY;
            if (dx * dx + dy * dy > TELEPORT_THRESHOLD * TELEPORT_THRESHOLD) {
                // 瞬移，直接重置平滑值
                smoothedX = x;
                smoothedY = y;
            } else {
                smoothedX = EMA_ALPHA * x + (1 - EMA_ALPHA) * smoothedX;
                smoothedY = EMA_ALPHA * y + (1 - EMA_ALPHA) * smoothedY;
            }
        }

        MapContext.getInstance().updatePlayerState(smoothedX, smoothedY, null);

        // 速度预测：需要至少两帧有效数据，避免首帧 prevRawX=0 导致虚假位移
        if (hasPreviousMatch && consecutiveFailureCount == 0) {
            double frameDx = x - prevRawX;
            double frameDy = y - prevRawY;
            velocityX = VELOCITY_EMA_ALPHA * frameDx + (1 - VELOCITY_EMA_ALPHA) * velocityX;
            velocityY = VELOCITY_EMA_ALPHA * frameDy + (1 - VELOCITY_EMA_ALPHA) * velocityY;
            predictedX = smoothedX + velocityX;
            predictedY = smoothedY + velocityY;
        }
        prevRawX = x;
        prevRawY = y;
        hasPreviousMatch = true;
    }

    /**
     * 处理匹配失败
     *
     * @param reason 失败原因（仅用于日志）
     */
    public void onMatchFailure(String reason) {
        consecutiveFailureCount++;
        if (consecutiveFailureCount > 5) {
            isMapLost = true;
            hasSmoothedPosition = false;
            hasPreviousMatch = false;
            predictedX = null;
            predictedY = null;
            velocityX = velocityY = 0;
            MapContext.getInstance().updatePlayerState(-1, -1, null);
            log.debug("地图丢失，重置平滑状态，原因: {}", reason);
        }
    }

    public void reset() {
        hasSmoothedPosition = false;
        consecutiveFailureCount = 0;
        isMapLost = false;
        smoothedX = smoothedY = 0;
        hasPreviousMatch = false;
        predictedX = null;
        predictedY = null;
        velocityX = velocityY = 0;
    }
}