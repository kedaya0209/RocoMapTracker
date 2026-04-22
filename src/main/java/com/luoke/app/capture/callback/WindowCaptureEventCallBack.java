package com.luoke.app.capture.callback;

@FunctionalInterface
public interface WindowCaptureEventCallBack<T> {

    void call(T t);
}
