package io.github.kedaya0209.roco.app.map.dto;

import net.jcip.annotations.ThreadSafe;

/**
 * 地图瓦片数据。
 *
 * @param x    列号
 * @param y    行号
 * @param data 瓦片图像字节
 */
@ThreadSafe
public record Tile(int x, int y, byte[] data) {
}