package com.luoke.app.hook.container;

import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class HookContainer {

    private static final HookContainer INSTANCE = new HookContainer();
    // 事件类型 -> 对应钩子列表
    private final Map<HookEventType, List<AbstractGenericHook<?>>> eventHookMap;

    private HookContainer() {
        this.eventHookMap = new ConcurrentHashMap<>();
    }

    public static HookContainer getInstance() {
        return INSTANCE;
    }

    public void registerHook(HookEventType eventType, AbstractGenericHook<?> hook) {
        eventHookMap.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(hook);
    }

    public List<AbstractGenericHook<?>> getHookList(HookEventType eventType) {
        return eventHookMap.getOrDefault(eventType, Collections.emptyList());
    }

    public void clear() {
        eventHookMap.clear();
    }
}