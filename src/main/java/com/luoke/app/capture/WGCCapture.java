package com.luoke.app.capture;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class WGCCapture {

    static {
        try {
            String libName = "capture.dll";
            InputStream in = WGCCapture.class.getResourceAsStream("/dll/" + libName);
            Path temp = Files.createTempDirectory("wgc").resolve(libName);
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            System.load(temp.toString());
            log.info("DLL 加载成功");
        } catch (Exception e) {
            log.error("DLL 加载失败", e);
            System.exit(1);
        }
    }

    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public WGCCapture(long hwnd) {
        this.nativePtr = nativeInit(hwnd, true);
        if (nativePtr == 0) {
            throw new IllegalStateException("WGC 初始化失败");
        }
        log.info("WGC 初始化成功，hwnd={}", hwnd);
    }

    private volatile long nativePtr;

    // 🔥 核心：只启动，不自动释放
    public void startLoop(FrameListener listener) {
        if (!isRunning.compareAndSet(false, true)) return;
        new Thread(() -> {
            nativeStartLoop(nativePtr, listener);
            isRunning.set(false);
            log.info("Rust 采集线程已退出");
        }).start();
    }

    public void close() {
        if (nativePtr == 0) return;
        isRunning.set(false);
        try {
            nativeStopLoop(nativePtr);
            nativeRelease(nativePtr);
        } catch (Exception ignored) {
        }
        nativePtr = 0;
        log.info("WGC 已释放");
    }

    // ===================== JNI 1:1 对应你的 Rust =====================
    private native long nativeInit(long hwnd, boolean preferHighPerf);

    private native void nativeStartLoop(long ptr, Object callback);

    private native void nativeStopLoop(long ptr);

    private native void nativeRelease(long ptr);

    // 完全匹配你的Rust：long onRawFrame(byte[])
    public interface FrameListener {
        long onRawFrame(byte[] data);
    }

    @Data
    public static class Frame {
        public int width;
        public int height;
        public long timestamp;
        public byte[] pixels;

        public Frame(byte[] data) {
            ByteBuffer bb = ByteBuffer.wrap(data).asReadOnlyBuffer();
            this.width = bb.getInt();
            this.height = bb.getInt();
            this.timestamp = bb.getLong();
            this.pixels = data;
        }
    }
}