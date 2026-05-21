package com.luoke.app.hook.event;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
public record ProgressEvent(double value, String text) {
}