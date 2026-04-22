package com.luoke.app.capture;

@FunctionalInterface
public interface WindowCaptureEventCallBack<T> {

    void call(T t);
}
