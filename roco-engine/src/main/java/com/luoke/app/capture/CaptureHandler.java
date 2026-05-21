package com.luoke.app.capture;

import com.luoke.app.process.NativeProcessFactory;
import com.luoke.app.process.ProcessRestartHelper;
import com.luoke.app.config.SocketConfig;
import com.luoke.app.socket.SocketHandler;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.socket.SocketSession;
import lombok.extern.slf4j.Slf4j;

import net.jcip.annotations.NotThreadSafe;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 截图协调器 — 编排 {@link CaptureProcessManager}（子进程生命周期）和
 * {@link CaptureSessionManager}（Socket 会话管理），对外保持与旧版本完全兼容的 API。
 *
 * <p>协调器自身职责：
 * <ul>
 *   <li>消息路由（handlers 路由表）</li>
 *   <li>帧数据反序列化与并行分发（handleFrameData）</li>
 *   <li>回调管理（FrameCallback / StateCallback）</li>
 *   <li>性能统计（帧率/吞吐量）</li>
 * </ul>
 */
@NotThreadSafe
@Slf4j
public class CaptureHandler implements SocketHandler {

    // ==================== 协议常量 ====================

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

    // ==================== 子管理器 ====================

    private final CaptureProcessManager processManager;
    private final CaptureSessionManager sessionManager;
    private final ProcessRestartHelper restartHelper;

    // ==================== 协调器自身字段 ====================

    private final FrameDeserializer frameDeserializer = new FrameDeserializer();
    private final AtomicLong frameCount = new AtomicLong(0);

    private volatile FrameCallback frameCallback;
    private volatile StateCallback stateCallback;
    private volatile boolean handshakeDone;
    private ROIData[] pendingRois;
    /** 崩溃恢复用 — 保存启动参数 */
    private long launchHwnd;
    private int launchMaxFps;
    private String launchExePath;
    private long totalBytes;
    private long lastStatsTime;
    private volatile int fullFrameRoiIndex = -1;

    // ==================== 消息路由 ====================

    @FunctionalInterface
    private interface MessageHandler {
        void handle(byte[] body, SocketSession session);
    }

    private final Map<Integer, MessageHandler> handlers = Map.of(
            MSG_REQUEST_ROI, (b, s) -> handleRequestRoi(s),
            MSG_CAPTURE_READY, (b, s) -> handleCaptureReady(s),
            MSG_FRAME_DATA, this::handleFrameData,
            MSG_WINDOW_CLOSED, (b, s) -> handleWindowClosed(),
            MSG_WINDOW_STATE, (b, s) -> handleWindowState(b)
    );

    public CaptureHandler(SocketServer server, NativeProcessFactory processFactory) {
        this.processManager = new CaptureProcessManager(processFactory);
        this.sessionManager = new CaptureSessionManager();
        this.restartHelper = new ProcessRestartHelper("capture",
                SocketConfig.SIFT_RESTART_DELAY);
    }

    // ==================== SocketHandler ====================

    @Override
    public String clientType() {
        return "capture";
    }

    @Override
    public Set<Integer> messageTypes() {
        return TYPES;
    }

    @Override
    public void onConnect(SocketSession session) {
        sessionManager.onConnect(session);
    }

    @Override
    public void onMessage(int type, byte[] body, SocketSession session) {
        MessageHandler handler = handlers.get(type);
        if (handler != null) {
            handler.handle(body, session);
        } else {
            log.warn("Unknown capture message type: {}", type);
        }
    }

    @Override
    public void onDisconnect(SocketSession session, String reason) {
        log.warn("CaptureHandler disconnected: {}", reason);
        sessionManager.onDisconnect();
        if (stateCallback != null) {
            stateCallback.onStateChange(false, reason);
        }
        // 崩溃后自动重启
        restartHelper.restartAsync(SocketServer.instance(),
                svr -> processManager.restartProcess(svr, launchExePath, launchHwnd, launchMaxFps));
    }

    // ==================== 握手 ====================

    private void handleRequestRoi(SocketSession session) {
        log.info("Received REQUEST_ROI");
        ROIData[] rois = pendingRois != null ? pendingRois : new ROIData[0];
        session.send(MSG_RETURN_ROI, serializeRois(rois));
        log.debug("Sent ROI list: {} ROIs", rois.length);
    }

    private void handleCaptureReady(SocketSession session) {
        log.info("Received CAPTURE_READY");
        sessionManager.setHandshakeDone(true);

        if (stateCallback != null) {
            stateCallback.onStateChange(true, "Connected");
        }

        // 发送起搏信号, 开始接收帧
        session.send(MSG_PROCESSING_DONE, null);
    }

    // ==================== 帧处理 ====================

    /**
     * 帧数据解析与并行分发。
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

        // 第一步: 反序列化帧数据
        List<FrameDeserializer.FrameSlot> slots = frameDeserializer.deserialize(buf, roiCount, fullFrameRoiIndex);

        // 更新统计
        for (FrameDeserializer.FrameSlot slot : slots) {
            frameCount.incrementAndGet();
            totalBytes += slot.pixels().length;
        }

        // 第二步: 虚拟线程并行处理各 ROI, CountDownLatch 等待全部完成
        CountDownLatch latch = new CountDownLatch(slots.size());
        for (FrameDeserializer.FrameSlot slot : slots) {
            Thread.ofVirtual().start(() -> {
                try {
                    cb.onFrame(slot.index(), slot.pixels(), slot.w(), slot.h(), slot.stride());
                } catch (Exception e) {
                    // 回调接口可能抛出多种异常，保留通用捕获以防 latch 死锁
                    log.error("Frame callback error ROI[{}]", slot.index(), e);
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

    // ==================== 窗口事件 ====================

    private void handleWindowClosed() {
        log.warn("capture.exe reports window closed");
        if (stateCallback != null) {
            stateCallback.onStateChange(false, "Window closed");
        }
    }

    private void handleWindowState(byte[] body) {
        if (body != null && body.length >= 1) {
            log.info("Window {}", body[0] == 0 ? "minimized" : "restored");
        }
    }

    // ==================== 公开 API ====================

    /**
     * 启动 capture.exe 子进程 (SocketServer 必须已启动)
     */
    public boolean start(long hwnd, int maxFps, String exePath,
                         ROIData[] rois, FrameCallback frameCb, StateCallback stateCb) {
        this.pendingRois = rois;
        this.frameCallback = frameCb;
        this.stateCallback = stateCb;
        this.launchHwnd = hwnd;
        this.launchMaxFps = maxFps;
        this.launchExePath = exePath;
        this.restartHelper.reset();

        if (!processManager.launchProcess(SocketServer.instance(), exePath, hwnd, maxFps)) {
            return false;
        }

        log.info("capture.exe launched (pid={}), hwnd=0x{}",
                processManager.getProcess().pid(), Long.toHexString(hwnd));
        return true;
    }

    /**
     * 停止截图
     */
    public void stop() {
        // 发送停止请求
        sessionManager.send(MSG_STOP_REQUEST, null);

        // 销毁子进程
        processManager.stopProcess();
        sessionManager.reset();

        log.info("CaptureHandler stopped");
    }

    public boolean isRunning() {
        return sessionManager.isConnected()
                && sessionManager.isHandshakeDone()
                && processManager.isAlive();
    }

    /**
     * 发送模式切换命令给 C++ capture.exe
     */
    public void sendSwitchMode(boolean fullFrame) {
        sessionManager.send(MSG_SWITCH_MODE, new byte[]{(byte) (fullFrame ? 1 : 0)});
        log.info("Sent SWITCH_MODE: {}", fullFrame ? "FULL_FRAME" : "ROI");
    }

    /**
     * 设置全帧 ROI 索引
     */
    public void setFullFrameRoiIndex(int index) {
        this.fullFrameRoiIndex = index;
    }

    /**
     * 释放全帧池化缓冲
     */
    public void releaseFullFrameBuffer() {
        frameDeserializer.clearPool();
    }

    // ==================== 内嵌类型（向后兼容） ====================

    @FunctionalInterface
    public interface FrameCallback {
        void onFrame(int index, byte[] data, int w, int h, int stride);
    }

    @FunctionalInterface
    public interface StateCallback {
        void onStateChange(boolean connected, String detail);
    }

    // ==================== 序列化 ====================

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
}
