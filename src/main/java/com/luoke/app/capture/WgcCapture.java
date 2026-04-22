package com.luoke.app.capture;

import com.luoke.app.capture.callback.WindowCaptureEventCallBack;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.capture.jna.WgcLibrary;
import com.sun.jna.Native;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class WgcCapture {

    public static final WgcLibrary LIB = loadLibrary();
    private final long hwnd;
    private volatile boolean running;

    public WgcCapture(long hwnd) {
        this.hwnd = hwnd;
    }

    private static WgcLibrary loadLibrary() {
        String dllResourcePath = "/dll/capture.dll";
        try (InputStream inputStream = WgcCapture.class.getResourceAsStream(dllResourcePath)) {
            if (inputStream == null) {
                throw new RuntimeException("DLL 不存在: " + dllResourcePath);
            }

            File tempDll = File.createTempFile("capture", ".dll");
            tempDll.deleteOnExit();

            try (FileOutputStream out = new FileOutputStream(tempDll)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, len);
                }
            }

            return Native.load(tempDll.getAbsolutePath(), WgcLibrary.class);
        } catch (Exception e) {
            throw new RuntimeException("加载 capture.dll 失败", e);
        }
    }

    public void startLoop(WindowCaptureEventCallBack<Frame> callback, boolean showBorder) {
        if (running) return;
        running = true;
        LIB.init_capturer(hwnd, showBorder ? 1 : 0, (data, len, w, h, pitch, code) -> {
            if (!running || code != 0 || data == null) return;
            callback.call(new Frame(data, w, h, pitch));
        });
    }

    public void close() {
        if (!running) return;
        running = false;
        LIB.destroy_capturer();
    }
}