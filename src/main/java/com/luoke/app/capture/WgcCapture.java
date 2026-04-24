package com.luoke.app.capture;

import com.luoke.app.capture.jna.Frame;
import com.luoke.app.capture.jna.FrameCallback;
import com.luoke.app.capture.jna.WgcLibrary;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import lombok.Setter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.function.Consumer;

public class WgcCapture {

    public static final WgcLibrary LIB = loadLibrary();
    private final long hwnd;
    private volatile boolean running;
    private final WindowCaptureHook frameCallback = new WindowCaptureHook();


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

    public void startLoop(Consumer<Frame> callback, boolean showBorder) {
        if (running) return;
        running = true;
        frameCallback.setCallback(callback);
        LIB.init_capturer(hwnd, showBorder ? 1 : 0, frameCallback);
    }

    @Setter
    public class WindowCaptureHook implements FrameCallback {

        private Consumer<Frame> callback;

        @Override
        public void onFrame(Pointer data, long len, int w, int h, int pitch, int code) {
            if (!running || code != 0 || data == null) return;
            callback.accept(new Frame(data, w, h, pitch));
        }
    }

    public void close() {
        if (!running) return;
        running = false;
        LIB.destroy_capturer();
    }
}