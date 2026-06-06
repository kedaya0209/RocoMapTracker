package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 资源点位变更事件（增删、置灰切换等） — 仅作信号，无数据负载。
 * UI 层监听以刷新图标缓存。
 */
@ThreadSafe
public record ResourcePointChangedEvent() {
    public static final ResourcePointChangedEvent INSTANCE = new ResourcePointChangedEvent();
}
