package com.luoke.app.hook;

import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 抽象泛型钩子基类
 * 通过反射捕获泛型类型，解决Java泛型擦除问题
 *
 * @param <T> 事件数据类型
 */
@Getter
public abstract class AbstractGenericHook<T> implements IHook<T> {

    /**
     * 数据泛型的真实类型
     */
    protected final Type dataGenericType;

    /**
     * 构造函数：通过反射捕获泛型类型参数
     */
    protected AbstractGenericHook() {
        ParameterizedType pt = (ParameterizedType) this.getClass().getGenericSuperclass();
        this.dataGenericType = pt.getActualTypeArguments()[0];
    }

    /**
     * 泛型事件回调方法，子类必须实现
     *
     * @param eventType 事件类型
     * @param data      事件数据
     */
    public abstract void onEvent(HookEventType eventType, T data);

}
