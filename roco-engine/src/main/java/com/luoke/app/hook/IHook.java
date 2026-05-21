package com.luoke.app.hook;

import net.jcip.annotations.ThreadSafe;
import java.util.Set;

@ThreadSafe
public interface IHook<T> {


    Set<HookEventType> supportedEvents();

    void onEvent(HookEventType type, T data);
}
