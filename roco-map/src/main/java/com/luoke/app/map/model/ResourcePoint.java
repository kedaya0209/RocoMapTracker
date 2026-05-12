package com.luoke.app.map.model;

import lombok.Data;

/**
 * 地图资源点位 — 纯数据模型, 渲染逻辑由 UI 层负责
 */
@Data
public class ResourcePoint {

    private final ResourceConfig config;
    private final Point screenPosition;
    private boolean grayed;
    private boolean hovered;

    public ResourcePoint(ResourceConfig config, Point screenPosition) {
        this.config = config;
        this.screenPosition = screenPosition;
    }
}