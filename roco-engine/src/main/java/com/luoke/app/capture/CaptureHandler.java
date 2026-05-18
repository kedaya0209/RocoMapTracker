package com.luoke.app.capture;

import com.luoke.app.process.JobObjectManager;
import com.luoke.app.process.NativeProcess;
import com.luoke.app.socket.SocketHandler;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.socket.SocketSession;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 截图处理器 — 实现 SocketHandler, 管理 capture.exe 子进程
 * 注册到 SocketServer, 接收采集事件 (帧数据/窗口状态)
 * <p>
 * 协议消息:
 * msgType=100: C++ → Java, 请求 ROI    (握手)
 * msgType=101: Java → C++, 返回 ROI    (握手)
 * msgType=102: C++ → Java, 采集就绪    (握手)
 * msgType=103: C++ → Java, 帧数据
 * msgType=104: Java → C++, 处理完成    (背压)
 * msgType=105: C++ → Java, 窗口关闭
 * msgType=106: Java → C++, 停止请求
 * msgType=107: C++ → Java, 窗口状态
 */
@Slf4j
public class CaptureHandler implements SocketHandler {

    private static final int MSG_REQUEST_ROI = 100;
    private static final int MSG_RETURN_ROI = 101;
    private static final int MSG_CAPTURE_READY = 102;
    private static final int MSG_FRAME_DATA = 103;
    private static final int MSG_PROCESSING_DONE = 104;
    private static final int MSG_WINDOW_CLOSED = 105;
    private static final int MSG_STOP_REQUEST = 106;
    private static final int MSG_WINDOW_STATE = 107;
    private static final int MSG_SWITCH_MODE = 108;

    private static final Set<Integer> TYPES = Set.of(
            MSG_REQUEST_ROI, MSG_CAPTURE_READY, MSG_FRAME_DATA,
            MSG_WINDOW_CLOSED, MSG_WINDOW_STATE);
    private final AtomicLong frameCount = new AtomicLong(0);

    // ---- 回调接口 ----
    private NativeProcess process;
    private volatile SocketSession session;

    // ---- 状态 ----
    private volatile FrameCallback frameCallback;
    private volatile StateCallback stateCallback;
    private volatile boolean handshakeDone;
    private ROIData[] pendingRois;
    private long totalBytes;
    private long lastStatsTime;
    /**
     * 全帧 ROI 索引（-1 = 未启用全帧模式），由 CaptureService.setFullFrameMode 设置
     */
    private volatile int fullFrameRoiIndex = -1;
    /**
     * 全帧数据池化缓冲区，避免每帧 8MB humongous 分配
     */
    private byte[] fullFramePoolBuffer;

    /**
     * msgType=2 body: [2] count + per-ROI [2]x,y,w,h (BE int16)
     */
    private static byte[] serializeRois(ROIData[] rois) {
        int count = (rois != null) ? rois.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(2 + count * 8).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) count);
        if (rois != null) {
            for (ROIData r : rois) {
                buf.putShort((short) r.x);
                buf.putShort((short) r.y);
                buf.putShort((short) r.w);
                buf.putShort((short) r.h);
            }
        }
        return buf.array();
    }

    @Override
    public String clientType() {
        return "capture";
    }

    @Override
    public Set<Integer> messageTypes() {
        return TYPES;
    }

    // ---- SocketHandler 实现 ----

    @Override
    public void onConnect(SocketSession session) {
        this.session = session;
        this.handshakeDone = false;
        log.debug("CaptureHandler connected on session#{}", session.id());
    }

    @Override
    public void onMessage(int type, byte[] body, SocketSession session) {
        switch (type) {
            case MSG_REQUEST_ROI -> handleRequestRoi(session);
            case MSG_CAPTURE_READY -> handleCaptureReady(session);
            case MSG_FRAME_DATA -> handleFrameData(body, session);
            case MSG_WINDOW_CLOSED -> handleWindowClosed();
            case MSG_WINDOW_STATE -> handleWindowState(body);
        }
    }

    @Override
    public void onDisconnect(SocketSession session, String reason) {
        log.warn("CaptureHandler disconnected: {}", reason);
        this.session = null;
        this.handshakeDone = false;
        if (stateCallback != null) {
            stateCallback.onStateChange(false, reason);
        }
    }

    private void handleRequestRoi(SocketSession session) {
        log.info("Received REQUEST_ROI");
        ROIData[] rois = pendingRois != null ? pendingRois : new ROIData[0];
        session.send(MSG_RETURN_ROI, serializeRois(rois));
        log.debug("Sent ROI list: {} ROIs", rois.length);
    }

    // ---- 握手 ----

    private void handleCaptureReady(SocketSession session) {
        log.info("Received CAPTURE_READY");
        handshakeDone = true;

        if (stateCallback != null) {
            stateCallback.onStateChange(true, "Connected");
        }

        // 发送起搏信号, 开始接收帧
        session.send(MSG_PROCESSING_DONE, null);
    }

    /**
     * msgType=4 body:
     * [2] roi_count (BE uint16)
     * Per ROI: [1] index, [2] w, [2] h, [2] stride, [4] dataLen, [dataLen] BGRA
     * <p>
     * 帧内 ROI 使用 CountDownLatch 并行处理, 全部完成后回发 PROCESSING_DONE (背压)
     */
    private void handleFrameData(byte[] body, SocketSession session) {
        if (body == null || body.length < 2) return;
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);

        int roiCount = buf.getShort() & 0xFFFF;
        FrameCallback cb = frameCallback;
        if (cb == null) {
            session.send(MSG_PROCESSING_DONE, null);
            return;
        }

        // 第一步: 解析所有 ROI 数据 (ByteBuffer 非线程安全, 在主线程解析)
        record RoiSlot(int index, byte[] pixels, int w, int h, int stride) {
        }
        List<RoiSlot> slots = new ArrayList<>(roiCount);

        for (int i = 0; i < roiCount; i++) {
            if (buf.remaining() < 11) break;

            int index = buf.get() & 0xFF;
            int w = buf.getShort() & 0xFFFF;
            int h = buf.getShort() & 0xFFFF;
            int stride = buf.getShort() & 0xFFFF;
            int dataLen = buf.getInt();

            if (dataLen <= 0 || buf.remaining() < dataLen) break;

            // 全帧使用池化缓冲区，避免每帧 8MB humongous 分配 → 老年代堆积
            byte[] pixels;
            if (index == fullFrameRoiIndex && dataLen > 0) {
                if (fullFramePoolBuffer == null || fullFramePoolBuffer.length != dataLen) {
                    fullFramePoolBuffer = new byte[dataLen];
                }
                pixels = fullFramePoolBuffer;
            } else {
                pixels = new byte[dataLen];
            }
            buf.get(pixels);
            slots.add(new RoiSlot(index, pixels, w, h, stride));

            frameCount.incrementAndGet();
            totalBytes += dataLen;
        }

        // 第二步: 虚拟线程并行处理各 ROI, CountDownLatch 等待全部完成
        CountDownLatch latch = new CountDownLatch(slots.size());
        for (RoiSlot slot : slots) {
            Thread.ofVirtual().start(() -> {
                try {
                    cb.onFrame(slot.index, slot.pixels, slot.w, slot.h, slot.stride);
                } catch (Exception e) {
                    log.error("Frame callback error ROI[{}]", slot.index, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 全部 ROI 处理完成, 通知 C++ 发送下一帧
        session.send(MSG_PROCESSING_DONE, null);

        // 每 10s 诊断
        long now = System.currentTimeMillis();
        if (lastStatsTime == 0) lastStatsTime = now;
        if (now - lastStatsTime > 10000) {
            double mbps = totalBytes / (1024.0 * 1024.0) / ((now - lastStatsTime) / 1000.0);
            log.debug("Frames: {}, Rate: {} MB/s", frameCount.get(),
                    String.format("%.1f", mbps));
            totalBytes = 0;
            lastStatsTime = now;
        }
    }

    // ---- 帧数据 ----

    private void handleWindowClosed() {
        log.warn("capture.exe reports window closed");
        if (stateCallback != null) {
            stateCallback.onStateChange(false, "Window closed");
        }
    }

    // ---- 窗口事件 ----

    private void handleWindowState(byte[] body) {
        if (body != null && body.length >= 1) {
            log.info("Window {}", body[0] == 0 ? "minimized" : "restored");
        }
    }

    /**
     * 启动 capture.exe 子进程 (SocketServer 必须已启动)
     * capture.exe 将连接到 SocketServer 的端口, 握手自动完成
     */
    public boolean start(long hwnd, int maxFps, String exePath,
                         ROIData[] rois, FrameCallback frameCb, StateCallback stateCb) {
        this.pendingRois = rois;
        this.frameCallback = frameCb;
        this.stateCallback = stateCb;

        // 先清理旧进程，防止孤儿进程累积
        if (process != null && process.isAlive()) {
            log.warn("旧 capture.exe 进程仍存活，强制终止");
            process.destroyForcibly();
        }

        int port = SocketServer.instance().getPort();
        if (port <= 0) {
            log.error("SocketServer is not running");
            return false;
        }

        // 通过 FFM CreateProcessW + PROC_THREAD_ATTRIBUTE_JOB_LIST 启动，
        // 使 capture.exe 在任务管理器"进程"页签下归入 Java 父进程
        String cmdLine = "\"" + exePath + "\" " + hwnd + " " + port + " " + maxFps;
        process = NativeProcess.create(cmdLine, JobObjectManager.getJobHandle(), true);
        if (process == null) {
            log.error("Failed to launch capture.exe via NativeProcess");
            return false;
        }

        // 消费 stdout
        startReaderThread();

        log.info("capture.exe launched (pid={}), hwnd=0x{}", process.pid(), Long.toHexString(hwnd));
        return true;
    }

    // ---- 公开 API ----

    private void startReaderThread() {
        Thread.ofVirtual()
                .name("capture-stdout")
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.debug("[capture.exe] {}", line);
                        }
                    } catch (Exception ignored) {
                    }
                });
    }

    /**
     * 停止截图
     */
    public void stop() {
        // 发送停止请求
        SocketSession s = session;
        if (s != null && !s.isClosed()) {
            s.send(MSG_STOP_REQUEST, null);
        }

        // 销毁子进程
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }

        session = null;
        handshakeDone = false;
        log.info("CaptureHandler stopped");
    }

    public boolean isRunning() {
        return session != null && !session.isClosed()
                && handshakeDone
                && process != null && process.isAlive();
    }

    /**
     * 发送模式切换命令给 C++ capture.exe
     *
     * @param fullFrame true = 全帧模式, false = ROI 模式
     */
    public void sendSwitchMode(boolean fullFrame) {
        SocketSession s = session;
        if (s != null && !s.isClosed()) {
            s.send(MSG_SWITCH_MODE, new byte[]{(byte) (fullFrame ? 1 : 0)});
            log.info("Sent SWITCH_MODE: {}", fullFrame ? "FULL_FRAME" : "ROI");
        }
    }

    /**
     * 设置全帧 ROI 索引。当 handleFrameData 遇到该索引的 ROI 时，使用池化缓冲区
     * 避免每帧 8MB humongous 分配。由 CaptureService.setFullFrameMode 调用。
     */
    public void setFullFrameRoiIndex(int index) {
        this.fullFrameRoiIndex = index;
    }

    /**
     * 释放全帧池化缓冲，让堆可收缩。在 setFullFrameMode(false) 时调用。
     */
    public void releaseFullFrameBuffer() {
        fullFramePoolBuffer = null;
    }

    @FunctionalInterface
    public interface FrameCallback {
        void onFrame(int index, byte[] data, int w, int h, int stride);
    }

    // ---- ROI 序列化 ----

    @FunctionalInterface
    public interface StateCallback {
        void onStateChange(boolean connected, String detail);
    }
}
