package com.luoke.app.map.dto;

import net.jcip.annotations.ThreadSafe;

/**
 * 地图图层选项配置 — 瓦片地图的数据源 URL。
 *
 * @param tileUrl 瓦片URL模板，支持XYZ瓦片格式
 *                （如 "https://tile.openstreetmap.org/{z}/{x}/{y}.png"）
 */
@ThreadSafe
public record LayerOption(String tileUrl) {
}
