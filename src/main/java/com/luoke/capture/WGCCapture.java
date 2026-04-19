package com.luoke.capture;

import lombok.Data;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;

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
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * 每一帧的解包对象
     */
    @Data
    public static class Frame {
        public final int width;
        public final int height;
        public final long timestamp;
        public final byte[] pixels;

        public Frame(byte[] fullPacket) {
            ByteBuffer buffer = ByteBuffer.wrap(fullPacket);
            this.width = buffer.getInt();
            this.height = buffer.getInt();
            this.timestamp = buffer.getLong();
            this.pixels = new byte[fullPacket.length - 16];
            buffer.get(this.pixels);
        }
    }

    public interface FrameListener {
        void onFrame(Frame frame);
    }

    private long nativePtr;

    public WGCCapture(long hwnd) {
        this.nativePtr = nativeInit(hwnd);
        if (this.nativePtr == 0) throw new RuntimeException("Init Failed");
    }

    public Frame captureSingleFrame() {
        byte[] data = nativeCaptureFrame(nativePtr);
        // 如果同步获取瞬间为空，尝试极短时间重试一次
        if (data == null || data.length == 0) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            data = nativeCaptureFrame(nativePtr);
        }
        return (data != null && data.length > 0) ? new Frame(data) : null;
    }

    public void startPushThread(int delayMs, FrameListener listener) {
        // 使用虚拟线程启动，减少内核线程占用
        Thread.ofVirtual()
                .name("WGC-Virtual-Thread")
                .start(() -> nativeStartLoop(nativePtr, delayMs, new Object() {
                    // 这个方法名必须和 Rust 里的 call_method 对应
                    public void onRawFrame(byte[] raw) {
                        if (raw != null && raw.length > 0) {
                            listener.onFrame(new Frame(raw));
                        }
                    }
                }));
    }

    public void release() {
        if (nativePtr != 0) {
            nativeRelease(nativePtr);
            nativePtr = 0;
        }
    }

    private native long nativeInit(long hwnd);
    private native byte[] nativeCaptureFrame(long ptr);
    private native void nativeStartLoop(long ptr, int delayMs, Object callback);
    private native void nativeRelease(long ptr);
}