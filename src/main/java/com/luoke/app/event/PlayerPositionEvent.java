package com.luoke.app.event;

import lombok.Builder;

@Builder
public record PlayerPositionEvent(double x, double y) {
}