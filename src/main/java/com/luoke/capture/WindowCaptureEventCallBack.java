package com.luoke.capture;

@FunctionalInterface
public interface WindowCaptureEventCallBack<T> {

    void call(T t);
}
