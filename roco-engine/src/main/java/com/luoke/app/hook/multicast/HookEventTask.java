package com.luoke.app.hook.multicast;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.hook.HookEventType;

@ThreadSafe
public record HookEventTask(HookEventType eventType, Object data) {
}
