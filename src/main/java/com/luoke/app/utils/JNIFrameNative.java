package com.luoke.app.utils;  // 保持与你的实际包名一致

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JNIFrameNative {
    private static volatile boolean loaded = false;

    static {
        loadLibrary();
    }

    private static synchronized void loadLibrary() {
        if (loaded) return;
        String externalPath = ResourceUtils.getExternalPath("/dll/jniframe.dll", true);

        try {
            System.load(externalPath);
        } catch (Exception ex) {
            log.error("[JNIFrameNative] Fatal error loading DLL:", ex);
            throw new RuntimeException("Cannot load jniframe.dll", ex);
        }
        loaded = true;
    }

    public static native int push(int capacity);

    public static native int pop();
}