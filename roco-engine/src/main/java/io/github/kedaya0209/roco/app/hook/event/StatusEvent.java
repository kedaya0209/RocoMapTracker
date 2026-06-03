package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public record StatusEvent(String message, NotificationType type, DisplayMode displayMode) {

    public enum DisplayMode {
        TOAST,
        CAROUSEL,
        BOTH
    }

    public StatusEvent(String message, NotificationType type) {
        this(message, type, DisplayMode.BOTH);
    }
}
