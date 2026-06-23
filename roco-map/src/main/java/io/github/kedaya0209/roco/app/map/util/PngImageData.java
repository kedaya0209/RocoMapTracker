package io.github.kedaya0209.roco.app.map.util;

import net.jcip.annotations.Immutable;

/**
 * PNG 解码结果 — 宽、高、ARGB int[] 像素。
 * pixels[y * w + x] = (A<<24)|(R<<16)|(G<<8)|B
 */
@Immutable
public record PngImageData(int w, int h, int[] pixels) {
}
