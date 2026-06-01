package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 跟随模式切换事件
 *
 * @param followMode 是否启用跟随模式
 */
@ThreadSafe
public record FollowModeEvent(boolean followMode) {
}
