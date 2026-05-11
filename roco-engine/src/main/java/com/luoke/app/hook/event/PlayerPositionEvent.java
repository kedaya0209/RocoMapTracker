package com.luoke.app.hook.event;

import lombok.Builder;

@Builder
public record PlayerPositionEvent(double x, double y) {
}
