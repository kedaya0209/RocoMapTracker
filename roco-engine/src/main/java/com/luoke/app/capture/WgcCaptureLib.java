package com.luoke.app.capture;

import com.luoke.app.config.AppConfig;
import com.luoke.app.utils.ResourceUtils;
import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

public interface WgcCaptureLib extends Library {
    WgcCaptureLib INSTANCE = loadLibrary();

    private static WgcCaptureLib loadLibrary() {
        try {
            // 使用JNA加载DLL
            return Native.load(ResourceUtils.getExternalPath(AppConfig.CAPTURE_DLL, true), WgcCaptureLib.class);
        } catch (Exception e) {
            throw new RuntimeException("加载 capture.dll 失败", e);
        }
    }

    // 注意：Rust 的 create 现在接收回调
    int create(long hwnd, int maxFps, JniCallback cb);

    void set_rois(int id, ROIData[] ptr, int len);

    void stop(int id);

    interface JniCallback extends Callback {
        void invoke(int id, int index, Pointer data, long len, int w, int h, int stride);
    }
}