package com.luoke.app.socket;

import net.jcip.annotations.ThreadSafe;

/**
 * 统一消息订阅者接口 — 不区分内部/外部，所有订阅者平级
 * <p>
 * 实现方式:
 * - {@link SocketSubscriber}: TCP 外部客户端（pcap-app 等独立进程）
 * - {@link HandlerSubscriber}: Java 内部处理器（CaptureHandler、SiftMatchHandler）
 */
@ThreadSafe
public interface MessageSubscriber {

    /**
     * 收到订阅的消息
     *
     * @param serviceId 服务标识（即消息类型）
     * @param body      消息体
     * @param sender    发送方的会话（可能为 null，如内部处理器触发）
     */
    void onMessage(int serviceId, byte[] body, SocketSession sender);

    /**
     * 关联的客户端标识（用于日志和调试）
     */
    String clientId();

    /**
     * 生产者连接建立（内部处理器用于绑定 session，外部客户端通常为空操作）
     */
    default void onConnect(SocketSession session) {}

    /**
     * 生产者连接断开（内部处理器用于清理 session，外部客户端通常为空操作）
     */
    default void onDisconnect(SocketSession session, int reason) {}
}
