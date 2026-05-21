package com.luoke.app.hook.event;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public record StatusEvent(String message, NotificationType type) {
}