package io.github.kedaya0209.roco.app.hook.multicast;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.hook.HookEventType;

@ThreadSafe
public record HookEventTask(HookEventType eventType, Object data) {
}
