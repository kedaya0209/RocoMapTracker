package io.github.kedaya0209.roco.app.hook;

import net.jcip.annotations.ThreadSafe;
import java.util.function.Consumer;

/**
 * 应用事件静态 facade — 委托给 {@link EventBus} 单例。
 * <p>
 * 替代 {@link HookRegistry} 的类型安全事件发布/订阅入口。
 * 迁移期间与 HookRegistry 双写共存。
 */
@ThreadSafe
public final class AppEvents {

    private static final EventBus BUS = new EventBus();

    private AppEvents() {
    }

    /** 订阅事件 */
    public static <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        BUS.subscribe(eventType, handler);
    }

    /** 取消订阅 */
    public static <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        BUS.unsubscribe(eventType, handler);
    }

    /** 同步发布事件 */
    public static <T> void publish(Class<T> eventType, T data) {
        BUS.publish(eventType, data);
    }

    /** 异步发布事件（虚拟线程） */
    public static <T> void publishAsync(Class<T> eventType, T data) {
        BUS.publishAsync(eventType, data);
    }

    /** 关闭异步分发器 */
    public static void shutdown() {
        BUS.shutdown();
    }
}
