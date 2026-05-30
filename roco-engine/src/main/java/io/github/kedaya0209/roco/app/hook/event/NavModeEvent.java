package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 导航模式切换事件
 *
 * @param enabled 是否启用导航模式
 */
@ThreadSafe
public record NavModeEvent(boolean enabled) {
}
