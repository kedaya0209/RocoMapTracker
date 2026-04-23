package com.luoke.app.hook;

import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 复刻 Jackson TypeReference 泛型捕获方案
 *
 * @param <T> 事件数据类型
 */
@Getter
public abstract class AbstractGenericHook<T> implements IHook<T> {

    protected final Type dataGenericType;

    protected AbstractGenericHook() {
        // 捕获子类真实泛型类型，规避泛型擦除
        ParameterizedType pt = (ParameterizedType) this.getClass().getGenericSuperclass();
        this.dataGenericType = pt.getActualTypeArguments()[0];
    }

    /**
     * 泛型事件回调
     */
    public abstract void onEvent(HookEventType eventType, T data);

}