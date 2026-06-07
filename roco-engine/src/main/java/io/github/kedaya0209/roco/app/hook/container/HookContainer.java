package io.github.kedaya0209.roco.app.hook.container;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.IHook;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@ThreadSafe
public class HookContainer {

    private static final HookContainer INSTANCE = new HookContainer();

    // 事件类型 -> 对应钩子列表
    private final Map<HookEventType, List<IHook<?>>> eventHookMap;


    private HookContainer() {
        // 创建线程安全的ConcurrentHashMap实例
        // 用于存储事件类型到钩子列表的映射
        this.eventHookMap = new ConcurrentHashMap<>();
    }


    public static HookContainer getInstance() {
        return INSTANCE;
    }

    public void registerHook(HookEventType eventType, IHook<?> hook) {
        // 使用computeIfAbsent()原子地获取或创建钩子列表
        // 如果Map中不存在该事件类型的列表，则创建新的CopyOnWriteArrayList
        // computeIfAbsent()是线程安全的，避免了"检查-创建"的竞态条件
        eventHookMap.computeIfAbsent(eventType, _ -> new CopyOnWriteArrayList<>()).add(hook);
    }

    public List<IHook<?>> getHookList(HookEventType eventType) {
        // 使用getOrDefault()获取钩子列表，如果不存在则返回不可变的空列表
        // Collections.EMPTY_LIST是一个共享的不可变空列表，避免创建多个空列表实例
        // 返回空列表而不是null，避免了调用方进行null检查
        return eventHookMap.getOrDefault(eventType, Collections.emptyList());
    }


    public void clear() {
        // 清空Map中的所有映射，释放所有钩子引用
        // 这会帮助垃圾回收器回收不再使用的钩子实例
        // 注意：这不会影响钩子实例本身，只是释放容器中的引用
        eventHookMap.clear();
    }

    public void unregisterHook(IHook<?> hook) {
        for (List<IHook<?>> value : eventHookMap.values()) {
            value.remove(hook);
        }
    }
}
