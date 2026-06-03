package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 玩家状态更新事件 — PlayerStateTracker → EventBus → UI PlayerState。
 * <p>playerInitialized 由 x/y >= 0 推断，不在此记录中携带。</p>
 */
@ThreadSafe
public record PlayerStateEvent(double x, double y, Double angle) {
}
