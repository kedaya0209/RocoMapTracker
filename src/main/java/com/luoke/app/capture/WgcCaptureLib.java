package com.luoke.app.capture;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public interface WgcCaptureLib extends Library {
    WgcCaptureLib INSTANCE = loadLibrary();

    private static WgcCaptureLib loadLibrary() {
        // DLL资源路径，位于resources/dll/capture.dll
        String dllResourcePath = "/dll/wgc_capture.dll";
        try (InputStream inputStream = WgcCaptureLib.class.getResourceAsStream(dllResourcePath)) {
            if (inputStream == null) {
                throw new RuntimeException("DLL 不存在: " + dllResourcePath);
            }

            // 创建临时DLL文件
            // 注意：在native image环境下，需要特别处理文件系统访问
            File tempDll = File.createTempFile("capture", ".dll");
            // JVM退出时自动删除临时文件
            tempDll.deleteOnExit();

            // 将资源DLL写入临时文件
            try (FileOutputStream out = new FileOutputStream(tempDll)) {
                byte[] buffer = new byte[8192]; // 8KB缓冲区
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            // 使用JNA加载DLL
            return Native.load(tempDll.getAbsolutePath(), WgcCaptureLib.class);
        } catch (Exception e) {
            throw new RuntimeException("加载 capture.dll 失败", e);
        }
    }

    // 注意：Rust 的 create 现在接收回调
    int create(long hwnd, JniCallback cb);

    void set_rois(int id, ROIData[] ptr, int len);

    void stop(int id);

    interface JniCallback extends Callback {
        void invoke(int id, int index, Pointer data, long len, int w, int h, int code);
    }
}