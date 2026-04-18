package com.luoke.capture;

import lombok.Builder;

@Builder
public record CaptureFrameRecord(int width, int height, byte[] bytes) {
}
