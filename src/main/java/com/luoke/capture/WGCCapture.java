package com.luoke.capture;

import lombok.extern.slf4j.Slf4j;

import java.io.File;

/**
 * 与WGC捕获DLL的Java接口。
 * 必须先加载本地库，才能使用任何方法。
 * 库名称：“wgc_capture_rs”（Windows上称为“wgc_capture_rs.dll”）。
 * <p>
 * 示例用法：
 * <pre>
 * static {
 *     System.loadLibrary("wgc_capture_rs");
 * }
 * long handle = WGCCapture.createCapture(hwnd);
 * WGCCapture.startCapture(handle);
 * int[] wh = new int[2];
 * byte[] frame = WGCCapture.grabFrame(handle, 100, wh);
 * if (frame != null) {
 *     int width = wh[0];
 *     int height = wh[1];
 *     // process BGRA pixels...
 * }
 * WGCCapture.closeCapture(handle);
 * </pre>
 */
@Slf4j
public class WGCCapture {
    static {
        try {
            String dllPath = WGCCapture.class.getResource("/dll/wgc_capture_rs.dll").getFile();
            File dllFile = new File(dllPath);
            if (dllFile.exists()) {
                // 2. 直接加载绝对路径（最稳，不会报错）
                System.load(dllFile.getAbsolutePath());
                log.info("✅ 成功加载 DLL：{}", dllFile.getAbsolutePath());
            } else {
                throw new RuntimeException("❌ DLL 不存在：" + dllPath);
            }
        } catch (Exception e) {
            log.error(String.format("❌ 加载 DLL 失败：%S", e.getMessage()), e);
        }
    }

    /**
     * 为给定窗口句柄（HWND）创建一个新的捕获会话。
     *
     * @param hwnd The window handle as a long (e.g., from JNA's Pointer.nativeValue).
     * @return A native handle to the capture session, or 0 on error.
     */
    public static native long createCapture(long hwnd);

    /**
     * 开始捕捉会话。
     *
     * @param handle The native handle returned by createCapture.
     * @return true if started successfully, false on error.
     */
    public static native boolean startCapture(long handle);

    /**
     * 检查捕获会话是否已开始。
     *
     * @param handle The native handle.
     * @return true if started.
     */
    public static native boolean isStarted(long handle);

    /**
     * 检查捕获会话是否关闭。
     *
     * @param handle The native handle.
     * @return true if closed.
     */
    public static native boolean isClosed(long handle);

    /**
     * 从捕获会话中取一帧。
     *
     * @param handle      The native handle.
     * @param timeoutMs   Maximum time to wait for a frame, in milliseconds.
     * @param widthHeight An int array of length 2. On success, widthHeight[0] will be set to the frame width,
     *                    and widthHeight[1] to the frame height. Can be null if dimensions are not needed.
     * @return A byte array containing BGRA pixel data (4 bytes per pixel, row-major),
     * or null if timeout or error.
     */
    public static native byte[] grabFrame(long handle, int timeoutMs, int[] widthHeight);

    /**
     * 关闭捕获会话并释放本地资源。
     * 此调用后，该句号失效。
     *
     * @param handle The native handle.
     */
    public static native void closeCapture(long handle);

    /**
     * 释放本地句柄而不关闭会话（很危险）。
     * 只有在你已经调用过 closeCapture 时才使用。
     *
     * @param handle The native handle.
     */
    public static native void freeHandle(long handle);

    // Exception classes corresponding to native errors
    public static class InvalidHandleException extends RuntimeException {
        public InvalidHandleException(String message) {
            super(message);
        }
    }

    public static class NotStartedException extends RuntimeException {
        public NotStartedException(String message) {
            super(message);
        }
    }

    public static class ClosedException extends RuntimeException {
        public ClosedException(String message) {
            super(message);
        }
    }

    public static class CaptureFailedException extends RuntimeException {
        public CaptureFailedException(String message) {
            super(message);
        }
    }
}