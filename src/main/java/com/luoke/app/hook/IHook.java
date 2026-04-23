package com.luoke.app.hook;

import java.util.Set;

/**
 * 泛型钩子接口
 */
public interface IHook<T> {

    /**
     * 支持的事件类型
     */
    Set<HookEventType> supportedEvents();

    /**
     * 事件回调（使用泛型 T，不再用 Object）
     */
    void onEvent(HookEventType type, T data);
}