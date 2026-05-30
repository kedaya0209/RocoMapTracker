package com.luoke.app.hook;

import net.jcip.annotations.ThreadSafe;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 类型安全事件总线 — Class&lt;T&gt; 键，替代枚举 {@link HookEventType}。
 * <p>
 * 同步分发：{@link #publish(Class, Object)}。
 * 异步分发：{@link #publishAsync(Class, Object)}（虚拟线程 + LinkedBlockingQueue）。
 */
@ThreadSafe
public final class EventBus {

    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Consumer<?>>> subscribers
            = new ConcurrentHashMap<>();

    private final LinkedBlockingQueue<AsyncEvent<?>> asyncQueue = new LinkedBlockingQueue<>();
    private volatile boolean asyncRunning;

    public EventBus() {
    }

    /** 订阅事件 */
    public <T> void subscribe(Class<T> eventType, Consumer<T> handler) {
        subscribers.computeIfAbsent(eventType, _ -> new CopyOnWriteArrayList<>()).add(handler);
    }

    /** 取消订阅 */
    public <T> void unsubscribe(Class<T> eventType, Consumer<T> handler) {
        var list = subscribers.get(eventType);
        if (list != null) list.remove(handler);
    }

    /** 同步分发事件（调用线程上执行所有 handler） */
    @SuppressWarnings("unchecked")
    public <T> void publish(Class<T> eventType, T data) {
        var list = subscribers.get(eventType);
        if (list == null) return;
        for (var handler : list) {
            ((Consumer<T>) handler).accept(data);
        }
    }

    /** 异步分发事件（虚拟线程 + LinkedBlockingQueue） */
    public <T> void publishAsync(Class<T> eventType, T data) {
        asyncQueue.offer(new AsyncEvent<>(eventType, data));
        ensureAsyncWorker();
    }

    private void ensureAsyncWorker() {
        if (asyncRunning) return;
        synchronized (this) {
            if (asyncRunning) return;
            asyncRunning = true;
            Thread.ofVirtual().name("eventbus-async").start(() -> {
                while (asyncRunning) {
                    try {
                        AsyncEvent<?> evt = asyncQueue.take();
                        dispatch(evt);
                    } catch (InterruptedException _) {
                        break;
                    }
                }
            });
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void dispatch(AsyncEvent<T> evt) {
        var list = subscribers.get(evt.type);
        if (list == null) return;
        for (var handler : list) {
            ((Consumer<T>) handler).accept(evt.data);
        }
    }

    public void shutdown() {
        asyncRunning = false;
    }

    private record AsyncEvent<T>(Class<T> type, T data) {
    }
}
