package com.luoke.app.hook.event;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public record CaptureStateEvent(int id, boolean connected, String windowTitle) {
}