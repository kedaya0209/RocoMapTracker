package com.luoke.app.map.dto;

import lombok.Data;

/**
 * 地图图层选项配置类
 * 用于配置瓦片地图的数据源URL
 */
@Data
public class LayerOption {
    /**
     * 地图瓦片服务的URL模板，支持XYZ瓦片格式
     * 占位符：{z}=缩放级别, {x}=列号, {y}=行号
     */
    private String tileUrl;

    /**
     * 构造方法
     *
     * @param tileUrl 瓦片URL模板，如 "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
     */
    public LayerOption(String tileUrl) {
        this.tileUrl = tileUrl;
    }
}
