package com.luoke.app.hook.event;

import com.luoke.app.ui.component.NotificationToast;

public record StatusEvent(String message, NotificationToast.Type type) {
}