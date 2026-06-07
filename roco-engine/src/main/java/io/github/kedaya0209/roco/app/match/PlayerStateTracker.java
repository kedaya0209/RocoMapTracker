package io.github.kedaya0209.roco.app.match;

import lombok.Getter;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.PlayerConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.PlayerStateEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * 玩家状态追踪器：负责位置平滑、方向修正。
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
        // 事件传原始坐标（ViewportState 接收后自行 EMA + 置灰判定用原始值）
        AppEvents.publish(PlayerStateEvent.class, new PlayerStateEvent(x, y, finalAngle));
    }

    /**
     * 处理匹配失败 — 仅记录日志，不做状态变更。
     */
    public void onMatchFailure(String reason) {
    }

    public void reset() {
        hasSmoothedPosition = false;
        smoothedX = smoothedY = 0;
        hasSmoothedAngle = false;
        smoothedAngle = 0;
    }
}
