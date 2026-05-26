package com.luoke.app.socket;

import net.jcip.annotations.ThreadSafe;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Java 内部处理器的订阅者实现 — 通过方法回调接收消息
 * <p>
 * 桥接 CaptureHandler/SiftMatchHandler 到 MessageSubscriber，保留 onConnect/onDisconnect 生命周期。
 */
@ThreadSafe
public class HandlerSubscriber implements MessageSubscriber {

    @FunctionalInterface
    public interface MessageHandler {
        void onMessage(int serviceId, byte[] body, SocketSession sender);
    }

    private final MessageHandler messageHandler;
    private final Consumer<SocketSession> connectHandler;
    private final BiConsumer<SocketSession, String> disconnectHandler;
    private final String clientId;

    public HandlerSubscriber(MessageHandler messageHandler,
                             Consumer<SocketSession> connectHandler,
                             BiConsumer<SocketSession, String> disconnectHandler,
                             String clientId) {
        this.messageHandler = messageHandler;
        this.connectHandler = connectHandler;
        this.disconnectHandler = disconnectHandler;
        this.clientId = clientId;
    }

    /**
     * 简化构造：只处理消息，不需要 connect/disconnect 回调
     */
    public HandlerSubscriber(MessageHandler messageHandler, String clientId) {
        this(messageHandler, s -> {}, (s, r) -> {}, clientId);
    }

    @Override
    public void onMessage(int serviceId, byte[] body, SocketSession sender) {
        messageHandler.onMessage(serviceId, body, sender);
    }

    @Override
    public String clientId() {
        return clientId;
    }

    @Override
    public void onConnect(SocketSession session) {
        connectHandler.accept(session);
    }

    @Override
    public void onDisconnect(SocketSession session, int reason) {
        disconnectHandler.accept(session, reasonToString(reason));
    }

    private static String reasonToString(int reason) {
        return switch (reason) {
            case SystemEvents.REASON_NORMAL -> "Normal disconnect";
            case SystemEvents.REASON_IO_ERROR -> "IO error";
            case SystemEvents.REASON_HELLO_ERROR -> "HELLO error";
            case SystemEvents.REASON_SERVER_STOP -> "Server stop";
            default -> "Unknown (" + reason + ")";
        };
    }
}
