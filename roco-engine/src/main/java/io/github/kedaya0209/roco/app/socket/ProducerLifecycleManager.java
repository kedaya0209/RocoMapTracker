package io.github.kedaya0209.roco.app.socket;

import io.github.kedaya0209.roco.app.process.NativeProcess;
import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 进程生命周期管理器 — 普通消费者，订阅 CLIENT_DISCONNECTED 系统事件
 * <p>
 * 只管 Java 自己启动的内部生产者，外部生产者（如 pcap-app）自己负责生命周期。
 * 与其他消费者完全平级，通过订阅系统事件感知断开并决定是否重启。
 */
@ThreadSafe
@Slf4j
public class ProducerLifecycleManager implements MessageSubscriber {

    /** clientId → 进程实例（只管理 Java 启动的进程） */
    private final Map<String, NativeProcess> managedProcesses = new ConcurrentHashMap<>();

    /** 断开回调 */
    private BiConsumer<String, NativeProcess> restartHandler;

    /**
     * 注册到 SocketServer，订阅 CLIENT_DISCONNECTED 事件
     */
    public void registerToServer(SocketServer server) {
        server.registerInternal(SystemEvents.CLIENT_DISCONNECTED,
                new HandlerSubscriber(
                        (serviceId, body, sender) -> onDisconnectEvent(body),
                        "lifecycle-manager"
                ));
    }

    /**
     * 设置断开时的重启回调
     */
    public void onRestartNeeded(BiConsumer<String, NativeProcess> handler) {
        this.restartHandler = handler;
    }

    /**
     * 注册内部生产者（Java 启动的进程）
     */
    public void manage(String clientId, NativeProcess process) {
        managedProcesses.put(clientId, process);
        log.info("管理生产者进程: clientId={}", clientId);
    }

    /**
     * 取消管理
     */
    public void unmanage(String clientId) {
        managedProcesses.remove(clientId);
    }

    /**
     * 停止所有管理的进程
     */
    public void stopAll() {
        for (Map.Entry<String, NativeProcess> entry : managedProcesses.entrySet()) {
            try {
                entry.getValue().destroy();
            } catch (Exception e) {
                log.error("停止进程失败: clientId={}", entry.getKey(), e);
            }
        }
        managedProcesses.clear();
    }

    // ==================== MessageSubscriber ====================

    @Override
    public void onMessage(int serviceId, byte[] body, SocketSession sender) {
        onDisconnectEvent(body);
    }

    @Override
    public String clientId() {
        return "lifecycle-manager";
    }

    // ==================== 内部逻辑 ====================

    private void onDisconnectEvent(byte[] body) {
        if (body == null || body.length < 6) return;

        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        int nameLen = buf.getShort() & 0xFFFF;
        if (buf.remaining() < nameLen + 4) return;

        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String clientId = new String(nameBytes, StandardCharsets.UTF_8);
        int reason = buf.getInt();

        NativeProcess process = managedProcesses.get(clientId);
        if (process != null) {
            log.info("内部生产者断开: clientId={} reason={}，准备重启", clientId, reason);
            if (restartHandler != null) {
                restartHandler.accept(clientId, process);
            }
        }
    }
}
