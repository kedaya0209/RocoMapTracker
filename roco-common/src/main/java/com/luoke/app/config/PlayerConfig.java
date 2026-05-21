package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * 玩家状态追踪配置持久化 
 */
@NotThreadSafe
public final class PlayerConfig {

    // ============================================================
    // 玩家状态追踪参数
    // ============================================================
    /**
     * 位置平滑 EMA 衰减因子（越低越平滑但滞后）
     */
    public static double PLAYER_EMA_ALPHA = 0.35;
    /**
     * 瞬移检测阈值（世界坐标单位）
     */
    public static double PLAYER_TELEPORT_THRESHOLD = 150.0;
    /**
     * 速度估计 EMA 衰减因子
     */
    public static double PLAYER_VELOCITY_EMA_ALPHA = 0.5;
    /**
     * 地图丢失前连续失败次数
     */
    public static int PLAYER_MAP_LOST_THRESHOLD = 5;

    private PlayerConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        PLAYER_EMA_ALPHA = ConfigHelper.getDouble(prop, "player.ema.alpha", PLAYER_EMA_ALPHA);
        PLAYER_TELEPORT_THRESHOLD = ConfigHelper.getDouble(prop, "player.teleport.threshold", PLAYER_TELEPORT_THRESHOLD);
        PLAYER_VELOCITY_EMA_ALPHA = ConfigHelper.getDouble(prop, "player.velocity.ema.alpha", PLAYER_VELOCITY_EMA_ALPHA);
        PLAYER_MAP_LOST_THRESHOLD = ConfigHelper.getInt(prop, "player.map.lost.threshold", PLAYER_MAP_LOST_THRESHOLD);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 位置平滑 EMA 衰减因子（越低越平滑但滞后）\n");
        sb.append("player.ema.alpha=").append(PLAYER_EMA_ALPHA).append("\n");
        sb.append("# 瞬移检测阈值（世界坐标单位）\n");
        sb.append("player.teleport.threshold=").append(PLAYER_TELEPORT_THRESHOLD).append("\n");
        sb.append("# 速度估计 EMA 衰减因子\n");
        sb.append("player.velocity.ema.alpha=").append(PLAYER_VELOCITY_EMA_ALPHA).append("\n");
        sb.append("# 地图丢失前连续失败次数\n");
        sb.append("player.map.lost.threshold=").append(PLAYER_MAP_LOST_THRESHOLD).append("\n\n");
    }
}
