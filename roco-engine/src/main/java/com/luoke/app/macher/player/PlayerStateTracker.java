package com.luoke.app.macher.player;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家状态追踪器：负责位置平滑、瞬移检测、地图丢失标志。
 */
@Slf4j
public class PlayerStateTracker {

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
     * @param x     匹配得到的 X 坐标
     * @param y     匹配得到的 Y 坐标
     * @param angle 箭头检测得到的朝向角度 (可为 null)
     */
    public void onMatchSuccess(double x, double y, Double angle) {
        consecutiveFailureCount = 0;
        isMapLost = false;

        if (!hasSmoothedPosition) {
            smoothedX = x;
            smoothedY = y;
            hasSmoothedPosition = true;
        } else {
            double dx = x - smoothedX;
            double dy = y - smoothedY;
            double threshold = AppConfig.PLAYER_TELEPORT_THRESHOLD;
            if (dx * dx + dy * dy > threshold * threshold) {
                // 瞬移，直接重置平滑值
                smoothedX = x;
                smoothedY = y;
            } else {
                double alpha = AppConfig.PLAYER_EMA_ALPHA;
                smoothedX = alpha * x + (1 - alpha) * smoothedX;
                smoothedY = alpha * y + (1 - alpha) * smoothedY;
            }
        }

        MapContext.getInstance().updatePlayerState(smoothedX, smoothedY, angle);

        // 速度预测：需要至少两帧有效数据，避免首帧 prevRawX=0 导致虚假位移
        if (hasPreviousMatch && consecutiveFailureCount == 0) {
            double frameDx = x - prevRawX;
            double frameDy = y - prevRawY;
            double vAlpha = AppConfig.PLAYER_VELOCITY_EMA_ALPHA;
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
     * 处理匹配失败
     *
     * @param reason 失败原因（仅用于日志）
     */
    public void onMatchFailure(String reason) {
        consecutiveFailureCount++;
        if (consecutiveFailureCount > AppConfig.PLAYER_MAP_LOST_THRESHOLD) {
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