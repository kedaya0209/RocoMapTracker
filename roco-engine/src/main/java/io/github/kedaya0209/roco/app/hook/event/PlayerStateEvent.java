package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 玩家状态更新事件 — PlayerStateTracker → EventBus → UI PlayerState。
 * <p>x/y 为原始匹配坐标（置灰/缩放等瞬态判定用），smoothedX/smoothedY 为 EMA 平滑后坐标（渲染用）。
 * 传送时原始坐标直接跳跃，避免平滑过渡扫过沿途资源点导致误置灰。</p>
 */
@ThreadSafe
public record PlayerStateEvent(double x, double y, double smoothedX, double smoothedY, Double angle) {
}
