package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

/**
 * PNG 图片工具 — 轻量级头部解析，无 AWT/ImageIO 依赖。
 */
@Slf4j
@ThreadSafe
public final class PngUtil {

    private PngUtil() {
    }

    /**
     * 从 PNG 原始字节解析图片尺寸（IHDR chunk）。
     *
     * @param data PNG 字节数据，至少前 24 字节有效
     * @return int[2] = {width, height}；不是有效 PNG 时返回 null
     */
    public static int[] parseSize(byte[] data) {
        if (data == null || data.length < 24) {
            return null;
        }
        // PNG 签名: 89 50 4E 47 ...
        if (data[0] != (byte) 0x89 || data[1] != 0x50) {
            return null;
        }
        int w = ((data[16] & 0xFF) << 24) | ((data[17] & 0xFF) << 16)
                | ((data[18] & 0xFF) << 8) | (data[19] & 0xFF);
        int h = ((data[20] & 0xFF) << 24) | ((data[21] & 0xFF) << 16)
                | ((data[22] & 0xFF) << 8) | (data[23] & 0xFF);
        return new int[]{w, h};
    }
}
