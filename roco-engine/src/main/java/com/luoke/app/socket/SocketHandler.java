package com.luoke.app.socket;

import java.util.Set;

/**
 * Socket 消息处理器 — 注册到 SocketServer, 接收连接生命周期和消息事件
 *
 * 生命周期:
 *   onConnect(session)  →  新客户端连接
 *   onMessage(type, body, session)  →  收到注册类型的消息
 *   onDisconnect(session, reason)  →  客户端断开
 */
public interface SocketHandler {

    /** 返回此处理器关心的消息类型集合 */
    Set<Integer> messageTypes();

    /** 客户端类型标识 (用于 HELLO 握手路由)，如 "capture" / "sift" */
    String clientType();

    /** 新客户端连接 */
    default void onConnect(SocketSession session) {}

    /** 收到消息 */
    void onMessage(int type, byte[] body, SocketSession session);

    /** 客户端断开 */
    default void onDisconnect(SocketSession session, String reason) {}
}
