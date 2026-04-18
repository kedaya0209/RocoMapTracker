package com.luoke.capture;

import java.io.IOException;

@FunctionalInterface
public interface WindowCaptureEventCallBack<T> {

    void call(T t) throws IOException;
}
