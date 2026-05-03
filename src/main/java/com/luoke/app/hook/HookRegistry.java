package com.luoke.app.hook;

import com.luoke.app.hook.container.HookContainer;
import com.luoke.app.hook.multicast.HookMulticaster;

/**
 * 钩子注册中心
 * 提供钩子系统的全局访问点，负责钩子注册和事件发布
 */
public enum HookRegistry {
    /**
     * 单例实例
     */
    INSTANCE;

    /**
     * 钩子容器
     */
    private final HookContainer container = HookContainer.getInstance();

    /**
     * 注册单个钩子
     * @param hook 要注册的钩子实例，不能为null
     */
    public void register(AbstractGenericHook<?> hook) {
        //防御型编程，启动事件多播器
        HookMulticaster.getInstance();
        for (HookEventType eventType : hook.supportedEvents()) {
            container.registerHook(eventType, hook);
        }
    }

    /**
     * 批量注册多个钩子
     * @param hooks 要注册的钩子实例数组，不能为null
     */
    public void registers(AbstractGenericHook<?>... hooks) {
        for (AbstractGenericHook<?> hook : hooks) {
            for (HookEventType eventType : hook.supportedEvents()) {
                container.registerHook(eventType, hook);
            }
        }
    }

    /**
     * 发布事件到钩子系统
     * @param eventType 事件类型
     * @param data 事件数据，可以是任意对象
     */
    public void publish(HookEventType eventType, Object data) {
        HookMulticaster.getInstance().enqueue(eventType, data);
    }

    /**
     * 销毁钩子系统
     * 清理所有资源，停止事件分发
     */
    public void destroy() {
        HookMulticaster.getInstance().shutdown();
        container.clear();
    }

    public void unregister(AbstractGenericHook<?> hook) {
        container.unregisterHook(hook);
    }
}
