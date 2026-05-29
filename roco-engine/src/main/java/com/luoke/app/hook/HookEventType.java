package com.luoke.app.hook;

import net.jcip.annotations.ThreadSafe;

@ThreadSafe
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
    MATERIAL_COLLECTION_UPDATED,
    /**
     * 状态轮播消息 — 用于 StatusCarousel 底部状态栏展示系统运行状态
     */
    STATUS_CAROUSEL,
    /**
     * 跟随模式切换事件 — CameraContext 跟随状态变更时发布，FloatToolbox 监听以更新按钮颜色
     */
    FOLLOW_MODE_CHANGED,
    /**
     * 路线列表变更事件 — PathContext 路线增删时发布，RouteManagerStage 监听以刷新列表
     */
    ROUTE_LIST_CHANGED,
    /**
     * 导航模式切换事件 — Sidebar/SettingDefinitions 切换导航模式时发布，TitleBar 监听以同步按钮图标
     */
    NAV_MODE_CHANGED
}
