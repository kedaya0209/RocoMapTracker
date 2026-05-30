package io.github.kedaya0209.roco.app.capture;

import io.github.kedaya0209.roco.app.capture.frame.CaptureFrameBuffer;
import io.github.kedaya0209.roco.app.capture.frame.FrameDeserializer;
import io.github.kedaya0209.roco.app.capture.frame.ROIData;
import io.github.kedaya0209.roco.app.capture.pipeline.RoiProcessor;
import io.github.kedaya0209.roco.app.capture.pipeline.ThroughputStats;
import io.github.kedaya0209.roco.app.process.NativeProcessFactory;
import io.github.kedaya0209.roco.app.process.ProcessRestartHelper;
import io.github.kedaya0209.roco.app.config.SocketConfig;
import io.github.kedaya0209.roco.app.socket.HandlerSubscriber;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.socket.SocketSession;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import net.jcip.annotations.NotThreadSafe;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.CountDownLatch;

import static io.github.kedaya0209.roco.app.capture.CaptureProtocol.*;

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
 *
 * <p>通过 {@link HandlerSubscriber} 注册到 {@link SocketServer}，订阅 capture.exe 提供的服务。
 */
@NotThreadSafe
@Slf4j
public class CaptureHandler {

    /** Java 端需要订阅的消息类型（capture.exe 发出的） */
    private static final Set<Integer> SUBSCRIBE_TYPES = Set.of(
            MSG_REQUEST_ROI, MSG_CAPTURE_READY, MSG_FRAME_DATA,
            MSG_WINDOW_CLOSED, MSG_WINDOW_STATE);

    // ==================== 子管理器 ====================

    private final CaptureProcessManager processManager;
    private final CaptureSessionManager sessionManager;
    private final ProcessRestartHelper restartHelper;

    // ==================== 协调器自身字段 ====================

    private final FrameDeserializer frameDeserializer = new FrameDeserializer();
    private final ThroughputStats throughputStats = new ThroughputStats();

    private volatile FrameCallback frameCallback;
    private volatile StateCallback stateCallback;
    /** 有意停止标记 — true 时 onDisconnect 不触发自动重启 */
    private volatile boolean intentionalStop;
    private ROIData[] pendingRois;
    /** 崩溃恢复用 — 保存启动参数 */
    private CaptureLaunchParams launchParams;
    /**
     * -- SETTER --
     *  设置全帧 ROI 索引
     */
    @Setter
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

    private final SocketServer server;

    public CaptureHandler(SocketServer server, NativeProcessFactory processFactory) {
        this.server = server;
        this.processManager = new CaptureProcessManager(processFactory);
        this.sessionManager = new CaptureSessionManager();
        this.restartHelper = new ProcessRestartHelper("capture",
                SocketConfig.SIFT_RESTART_DELAY);
    }

    // ==================== HandlerSubscriber 适配 ====================

    /**
     * 创建 HandlerSubscriber 并注册到 SocketServer，订阅 capture.exe 提供的服务
     */
    public void registerToServer(SocketServer server) {
        HandlerSubscriber subscriber = new HandlerSubscriber(
                this::onMessage,
                this::onConnect,
                this::onDisconnect,
                "capture-handler"
        );
        server.registerInternal(SUBSCRIBE_TYPES, subscriber);
    }

    // ==================== 消息处理 ====================

    private void onConnect(SocketSession session) {
        sessionManager.onConnect(session);
    }

    private void onMessage(int type, byte[] body, SocketSession session) {
        MessageHandler handler = handlers.get(type);
        if (handler != null) {
            handler.handle(body, session);
        } else {
            log.warn("未知截图消息类型: {}", type);
        }
    }

    private void onDisconnect(SocketSession session, String reason) {
        log.warn("CaptureHandler 断开连接: {}", reason);
        sessionManager.onDisconnect();
        if (stateCallback != null) {
            stateCallback.onStateChange(false, reason);
        }
        // 有意停止（黑帧检测/用户手动停止）不触发自动重启，由 watchdog 以 5s 间隔兜底
        if (!intentionalStop) {
            CaptureLaunchParams params = launchParams;
            if (params != null) {
                restartHelper.restartAsync(server,
                        svr -> processManager.restartProcess(svr, params.exePath(), params.hwnd(), params.maxFps()));
            }
        }
    }

    // ==================== 握手 ====================

    private void handleRequestRoi(SocketSession session) {
        log.info("收到 REQUEST_ROI");
        ROIData[] rois = pendingRois != null ? pendingRois : new ROIData[0];
        session.send(MSG_RETURN_ROI, CaptureProtocol.serializeRois(rois));
        log.debug("已发送 ROI 列表: {} 个 ROI", rois.length);
    }

    private void handleCaptureReady(SocketSession session) {
        log.info("收到 CAPTURE_READY");
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
     * 帧内 ROI 使用虚拟线程并行处理, 全部完成后回发 PROCESSING_DONE (背压)
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
            throughputStats.recordFrame(slot.pixels().length);
        }

        // 第二步: 虚拟线程并行处理各 ROI, CountDownLatch 等待全部完成
        CountDownLatch latch = new CountDownLatch(slots.size());
        for (FrameDeserializer.FrameSlot slot : slots) {
            Thread.ofVirtual().start(() -> {
                try {
                    cb.onFrame(slot.index(), slot.pixels(), slot.w(), slot.h(), slot.stride());
                } catch (Exception e) {
                    log.error("帧回调异常 ROI[{}]", slot.index(), e);
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
    }

    // ==================== 窗口事件 ====================

    private void handleWindowClosed() {
        log.warn("capture.exe 报告窗口已关闭");
        if (stateCallback != null) {
            stateCallback.onStateChange(false, "Window closed");
        }
    }

    private void handleWindowState(byte[] body) {
        if (body != null && body.length >= 1) {
            log.info("窗口 {}", body[0] == 0 ? "已最小化" : "已恢复");
        }
    }

    // ==================== 公开 API ====================

    /**
     * 启动 capture.exe 子进程 (SocketServer 必须已启动)
     */
    public boolean start(long hwnd, int maxFps, String exePath,
                         ROIData[] rois, FrameCallback frameCb, StateCallback stateCb) {
        this.intentionalStop = false;
        this.pendingRois = rois;
        this.frameCallback = frameCb;
        this.stateCallback = stateCb;
        this.launchParams = new CaptureLaunchParams(hwnd, maxFps, exePath);
        this.restartHelper.reset();

        if (!processManager.launchProcess(server, exePath, hwnd, maxFps)) {
            return false;
        }

        log.info("capture.exe 已启动 (pid={}), hwnd=0x{}",
                processManager.getProcess().pid(), Long.toHexString(hwnd));
        return true;
    }

    /**
     * 停止截图
     */
    public void stop() {
        intentionalStop = true;

        // 发送停止请求
        sessionManager.send(MSG_STOP_REQUEST, null);

        // 销毁子进程
        processManager.stopProcess();
        sessionManager.reset();

        log.info("CaptureHandler 已停止");
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
        log.info("已发送 SWITCH_MODE: {}", fullFrame ? "全帧模式" : "ROI 模式");
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
}
