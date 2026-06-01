package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public record CaptureStateEvent(int id, boolean connected, String windowTitle) {
}