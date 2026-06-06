package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 路线列表变更事件 — 仅作信号，无数据负载。
 * UI 层收到后直接从 {@code PathContext.getInstance().getSavedRoutes()} 读取。
 */
@ThreadSafe
public record RouteListEvent() {
    public static final RouteListEvent INSTANCE = new RouteListEvent();
}
