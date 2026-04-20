package com.luoke.capture;

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

    private final long hwnd;
    // ===================== 修复：使用 volatile 保证多线程可见性 =====================
    private volatile long nativePtr;

    public WGCCapture(long hwnd) {
        this.hwnd = hwnd;
        this.nativePtr = nativeInit(hwnd);
        if (this.nativePtr == 0) throw new RuntimeException("Init Failed");
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
            nativeRelease(nativePtr);
            nativePtr = 0;
        }
    }

    // ===================== 核心修复：Rust 重建后会调用此方法更新指针 =====================
    private void setNativePtr(long newPtr) {
        this.nativePtr = newPtr;
        log.debug("捕获器已重建，新native指针: {}", newPtr);
    }

    private native long nativeInit(long hwnd);
    private native byte[] nativeCaptureFrame(long ptr);
    private native void nativeStartLoop(long ptr, int delayMs, Object callback);
    private native void nativeRelease(long ptr);
}