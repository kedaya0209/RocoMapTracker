package com.luoke.app.capture;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
public class WGCCapture {
    static {
        try {
            String libName = "capture.dll";
            InputStream in = WGCCapture.class.getResourceAsStream("/dll/" + libName);
            if (in == null) {
                System.loadLibrary("capture");
            } else {
                Path temp = Files.createTempDirectory("wgc_").resolve(libName);
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
                System.load(temp.toString());
            }
        } catch (Exception e) {
            log.error("动态链接库加载失败,e", e);
            System.exit(1);
        }
    }

    @Data
    public static class Frame {
        public final int width;
        public final int height;
        public final long timestamp;
        public final byte[] pixels;

        public Frame(byte[] fullPacket) {
            this.pixels = fullPacket;
            ByteBuffer buffer = ByteBuffer.wrap(fullPacket);
            this.width = buffer.getInt();
            this.height = buffer.getInt();
            this.timestamp = buffer.getLong();
        }
    }

    public interface FrameListener {
        void onFrame(Frame frame);
    }

    private volatile long nativePtr;
    private volatile boolean isRunning;

    public WGCCapture(long hwnd) {
        this.nativePtr = nativeInitWithGPU(hwnd, true);
        if (this.nativePtr == 0) throw new RuntimeException("Init Failed");
    }

    // ===================== 【关键】启动循环推送（等Java处理完再下一帧） =====================
    public void startLoop(FrameListener listener) {
        if (nativePtr == 0 || isRunning) return;

        // Rust 会阻塞等待 onFrame 执行完再继续
        nativeStartLoop(nativePtr, new Object() {
            // 这个方法会被 Rust 自动调用
            @SuppressWarnings("unused")
            long onRawFrame(byte[] data) {
                try {
                    if (data != null && data.length > 0) {
                        listener.onFrame(new Frame(data));
                    }
                } catch (Exception e) {
                    log.error("帧处理异常", e);
                }
                return 0;
            }
        });

        isRunning = true;
    }


    public Frame captureSingleFrame() {
        byte[] data = nativeCaptureFrame(nativePtr);
        if (data == null || data.length == 0) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            data = nativeCaptureFrame(nativePtr);
        }
        return (data != null && data.length > 0) ? new Frame(data) : null;
    }

    public void release() {
        if (nativePtr != 0) {
            nativeStopLoop(nativePtr);
            nativeRelease(nativePtr);
            nativePtr = 0;
        }
    }

    // ===================== Native 方法 =====================
    public native long nativeInit(long hwnd);

    // 🔥 新方法：Java 传入是否强制高性能显卡
    public native long nativeInitWithGPU(long hwnd, boolean preferHighPerformanceGPU);

    public native byte[] nativeCaptureFrame(long ptr);

    public native void nativeStartLoop(long ptr, Object callback);

    public native void nativeRelease(long ptr);

    public native void nativeStopLoop(long ptr);
}