package com.luoke.app.capture.common;

import lombok.Builder;

@Builder
public record CaptureFrameRecord(int width, int height, byte[] bytes) {
}
