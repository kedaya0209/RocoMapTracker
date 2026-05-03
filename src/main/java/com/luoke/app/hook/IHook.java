package com.luoke.app.hook;

import java.util.Set;

public interface IHook<T> {


    Set<HookEventType> supportedEvents();

    void onEvent(HookEventType type, T data);
}
