package com.luoke.app.hook;

public enum HookEventType {

    UI_NOTIFICATION,
    INIT_PROGRESS,
    CAPTURE_STATE,
    /**
     * 资源点位变更（增删、置灰状态切换等），UI 层监听以刷新图标缓存
     */
    RESOURCE_POINT_CHANGED,
    /**
     * 物资采集统计更新，UI 层监听以刷新计数面板
     */
    MATERIAL_COLLECTION_UPDATED
}
