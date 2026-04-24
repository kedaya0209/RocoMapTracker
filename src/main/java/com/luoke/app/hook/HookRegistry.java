package com.luoke.app.hook;

import com.luoke.app.hook.container.HookContainer;
import com.luoke.app.hook.multicast.HookMulticaster;

public enum HookRegistry {
    INSTANCE;

    private final HookContainer container = HookContainer.getInstance();

    /**
     * 注册钩子
     */
    public void register(AbstractGenericHook<?> hook) {
        for (HookEventType eventType : hook.supportedEvents()) {
            container.registerHook(eventType, hook);
        }
    }

    public void registers(AbstractGenericHook<?>... hooks) {
        for (AbstractGenericHook<?> hook : hooks) {
            for (HookEventType eventType : hook.supportedEvents()) {
                container.registerHook(eventType, hook);
            }
        }
    }

    /**
     * 发布事件 入队
     */
    public void publish(HookEventType eventType, Object data) {
        HookMulticaster.getInstance().enqueue(eventType, data);
    }

    /**
     * 销毁
     */
    public void destroy() {
        HookMulticaster.getInstance().shutdown();
        container.clear();
    }
}