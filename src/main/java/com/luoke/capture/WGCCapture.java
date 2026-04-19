package com.luoke.capture;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;

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

    private long nativePtr;

    public WGCCapture(long hwnd) {
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

    private native long nativeInit(long hwnd);
    private native byte[] nativeCaptureFrame(long ptr);
    private native void nativeStartLoop(long ptr, int delayMs, Object callback);
    private native void nativeRelease(long ptr);
}