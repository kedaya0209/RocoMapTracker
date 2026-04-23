package com.luoke.app.hook.multicast;

import com.luoke.app.hook.HookEventType;

public record HookEventTask(HookEventType eventType, Object data) {
}