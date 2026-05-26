package com.luoke.app.socket;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.config.SocketConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Socket Server — 全局单例，纯消息注册中心 + 路由器
 * <p>
 * 职责:
 * 1. 监听端口，accept 客户端连接
 * 2. 每连接启动一条 recv 线程，读取消息 → 按 serviceId 查订阅表转发
 * 3. 管理客户端注册/注销，发布系统事件
 * 4. 不理解业务，不做消息分发到 Handler
 * <p>
 * 用法:
 * SocketServer server = SocketServer.instance();
 * int port = server.start();
 * // 注册内部处理器（通过 HandlerSubscriber 包装）
 * // 外部客户端通过 TCP 连接后 HELLO 注册
 * server.stop();
 */
@ThreadSafe
@Slf4j
public class SocketServer {

    static final int MSG_HELLO = 1;
    static final int MSG_REGISTER = 2;
    static final int MSG_UNREGISTER = 3;
    static final int MSG_SUBSCRIBE = 4;
    static final int MSG_UNSUBSCRIBE = 5;
    static final int MSG_SERVICE_LIST = 6;

    private static final SocketServer INSTANCE = new SocketServer();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Long, SocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, SocketSubscriber> sessionSubscribers = new ConcurrentHashMap<>();
    private final ServiceRegistry registry = new ServiceRegistry();

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public static SocketServer instance() {
        return INSTANCE;
    }

    // ---- 启动 / 停止 ----

    public int start() throws IOException {
        if (running.get()) return serverSocket.getLocalPort();

        serverSocket = new ServerSocket(0, SocketConfig.SOCKET_BACKLOG);
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "socket-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        int port = serverSocket.getLocalPort();
        log.info("SocketServer 已启动，端口: {}", port);
        return port;
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        if (acceptThread != null) {
            try {
                acceptThread.join(2000);
            } catch (InterruptedException ignored) {
            }
        }

        for (SocketSession s : sessions.values()) s.close();

        sessions.clear();
        sessionSubscribers.clear();
        registry.clear();

        log.info("SocketServer 已停止");
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public boolean isRunning() {
        return running.get();
    }

    public ServiceRegistry registry() {
        return registry;
    }

    // ---- 内部处理器注册 ----

    /**
     * 注册内部消息处理器（Java 进程内的 Handler，不走 TCP）
     */
    public void registerInternal(int serviceId, HandlerSubscriber subscriber) {
        registry.subscribe(serviceId, subscriber);
        log.debug("注册内部处理器: clientId={} serviceId={}", subscriber.clientId(), serviceId);
    }

    /**
     * 注册内部处理器（批量订阅多个 serviceId）
     */
    public void registerInternal(Set<Integer> serviceIds, HandlerSubscriber subscriber) {
        for (int serviceId : serviceIds) {
            registry.subscribe(serviceId, subscriber);
        }
        log.debug("注册内部处理器: clientId={} serviceIds={}", subscriber.clientId(), serviceIds);
    }

    /**
     * 移除内部处理器
     */
    public void unregisterInternal(HandlerSubscriber subscriber) {
        registry.unregister(subscriber);
    }

    // ---- 消息路由 ----

    /**
     * 路由消息给所有订阅者（排除 sender）
     */
    public void routeMessage(int serviceId, byte[] body, MessageSubscriber sender) {
        Set<MessageSubscriber> targets = registry.getSubscribers(serviceId);
        if (targets.isEmpty()) return;

        SocketSession senderSession = findSession(sender);
        for (MessageSubscriber sub : targets) {
            if (sub == sender) continue;  // 不回环
            try {
                sub.onMessage(serviceId, body, senderSession);
            } catch (Exception e) {
                log.error("路由消息异常: serviceId={} → clientId={}", serviceId, sub.clientId(), e);
            }
        }
    }

    private SocketSession findSession(MessageSubscriber subscriber) {
        if (subscriber instanceof SocketSubscriber ss) {
            return ss.session();
        }
        return null;
    }

    /**
     * 通知订阅了此客户端所提供服务的其他订阅者：生产者已连接
     */
    private void notifyProviderConnect(ClientInfo providerInfo, SocketSession providerSession) {
        for (int serviceId : providerInfo.provides()) {
            for (MessageSubscriber sub : registry.getSubscribers(serviceId)) {
                if (!sub.clientId().equals(providerInfo.clientId())) {
                    try {
                        sub.onConnect(providerSession);
                    } catch (Exception e) {
                        log.error("onConnect 回调异常: {} serviceId={}", sub.clientId(), serviceId, e);
                    }
                }
            }
        }
    }

    /**
     * 通知订阅了此客户端所提供服务的其他订阅者：生产者已断开
     */
    private void notifyProviderDisconnect(ClientInfo providerInfo, SocketSession providerSession, int reason) {
        for (int serviceId : providerInfo.provides()) {
            for (MessageSubscriber sub : registry.getSubscribers(serviceId)) {
                if (!sub.clientId().equals(providerInfo.clientId())) {
                    try {
                        sub.onDisconnect(providerSession, reason);
                    } catch (Exception e) {
                        log.error("onDisconnect 回调异常: {} serviceId={}", sub.clientId(), serviceId, e);
                    }
                }
            }
        }
    }

    // ---- Accept 循环 ----

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket sock = serverSocket.accept();
                SocketSession session = new SocketSession(sock);
                sessions.put(session.id(), session);

                log.info("接受连接 #{} 来自 {}", session.id(), sock.getInetAddress());

                Thread.ofPlatform().daemon(true).name("socket-recv-" + session.id())
                        .start(() -> recvLoop(session));
            } catch (IOException e) {
                if (running.get()) log.error("接受连接异常", e);
                break;
            }
        }
    }

    // ---- Recv + 路由循环 ----

    private void recvLoop(SocketSession session) {
        boolean firstMessage = true;

        while (running.get() && !session.isClosed()) {
            try {
                SocketSession.Message msg = session.recv();
                if (msg == null) break;

                if (firstMessage) {
                    firstMessage = false;

                    if (msg.type() != MSG_HELLO) {
                        log.warn("会话 #{} 首条消息不是 HELLO (type={})，关闭连接",
                                session.id(), msg.type());
                        session.close();
                        break;
                    }

                    ClientInfo info = parseHello(msg.body(), session);
                    if (info == null) {
                        session.close();
                        break;
                    }

                    // 创建 SocketSubscriber 并注册
                    SocketSubscriber subscriber = new SocketSubscriber(session, info.clientId());
                    sessionSubscribers.put(session.id(), subscriber);
                    registry.register(info, subscriber);

                    // 通知订阅了此客户端所提供服务的其他订阅者（onConnect）
                    notifyProviderConnect(info, session);

                    // 发布 CLIENT_CONNECTED 系统事件
                    byte[] eventBody = SystemEvents.encodeConnected(info.clientId(), info.provides(), info.subscribes());
                    routeMessage(SystemEvents.CLIENT_CONNECTED, eventBody, subscriber);

                    log.info("会话 #{} 注册完成: clientId={} provides={} subscribes={}",
                            session.id(), info.clientId(), info.provides(), info.subscribes());
                    continue;
                }

                // 控制消息处理
                if (msg.type() >= MSG_REGISTER && msg.type() <= MSG_SERVICE_LIST) {
                    handleControlMessage(msg.type(), msg.body(), session);
                    continue;
                }

                // 业务消息：按 serviceId 路由
                SocketSubscriber sender = sessionSubscribers.get(session.id());
                routeMessage(msg.type(), msg.body(), sender);

            } catch (IOException e) {
                break;
            }
        }

        // 清理
        SocketSubscriber subscriber = sessionSubscribers.remove(session.id());
        if (subscriber != null) {
            // 通知订阅了此客户端所提供服务的其他订阅者（onDisconnect）
            ClientInfo info = registry.getClient(subscriber.clientId());
            if (info != null) {
                notifyProviderDisconnect(info, session, SystemEvents.REASON_IO_ERROR);
            }

            registry.unregister(subscriber);

            // 发布 CLIENT_DISCONNECTED 系统事件（ProducerLifecycleManager 也是订阅者，会自动收到）
            byte[] eventBody = SystemEvents.encodeDisconnected(subscriber.clientId(), SystemEvents.REASON_IO_ERROR);
            routeMessage(SystemEvents.CLIENT_DISCONNECTED, eventBody, subscriber);
        }

        session.close();
        sessions.remove(session.id());

        log.info("会话 #{} 已关闭", session.id());
    }

    // ---- HELLO 解析 ----

    private ClientInfo parseHello(byte[] body, SocketSession session) {
        if (body == null || body.length < 6) {
            log.warn("会话 #{} HELLO 包体过短，关闭连接", session.id());
            return null;
        }

        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);

        // clientId
        int nameLen = buf.getShort() & 0xFFFF;
        if (buf.remaining() < nameLen + 4) {
            log.warn("会话 #{} HELLO 名称截断，关闭连接", session.id());
            return null;
        }
        byte[] nameBytes = new byte[nameLen];
        buf.get(nameBytes);
        String clientId = new String(nameBytes, StandardCharsets.UTF_8);

        // provides
        int providesCount = buf.getShort() & 0xFFFF;
        if (buf.remaining() < providesCount * 4 + 2) {
            log.warn("会话 #{} HELLO provides 截断，关闭连接", session.id());
            return null;
        }
        Set<Integer> provides = new HashSet<>();
        for (int i = 0; i < providesCount; i++) {
            provides.add(buf.getInt());
        }

        // subscribes
        int subscribesCount = buf.getShort() & 0xFFFF;
        if (buf.remaining() < subscribesCount * 4) {
            log.warn("会话 #{} HELLO subscribes 截断，关闭连接", session.id());
            return null;
        }
        Set<Integer> subscribes = new HashSet<>();
        for (int i = 0; i < subscribesCount; i++) {
            subscribes.add(buf.getInt());
        }

        return new ClientInfo(clientId, provides, subscribes);
    }

    // ---- 控制消息处理 ----

    private void handleControlMessage(int type, byte[] body, SocketSession session) {
        SocketSubscriber subscriber = sessionSubscribers.get(session.id());
        if (subscriber == null) {
            log.warn("会话 #{} 未注册就发送控制消息，忽略", session.id());
            return;
        }

        switch (type) {
            case MSG_SUBSCRIBE -> {
                if (body != null && body.length >= 4) {
                    int serviceId = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt();
                    registry.subscribe(serviceId, subscriber);
                    log.debug("动态订阅: clientId={} serviceId={}", subscriber.clientId(), serviceId);
                }
            }
            case MSG_UNSUBSCRIBE -> {
                if (body != null && body.length >= 4) {
                    int serviceId = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt();
                    registry.unsubscribe(serviceId, subscriber);
                    log.debug("取消订阅: clientId={} serviceId={}", subscriber.clientId(), serviceId);
                }
            }
            case MSG_SERVICE_LIST -> {
                // 回复当前所有已注册服务
                Map<String, ClientInfo> clients = registry.getAllClients();
                ByteBuffer buf = ByteBuffer.allocate(clients.size() * 64).order(ByteOrder.BIG_ENDIAN);
                buf.putInt(clients.size());
                for (ClientInfo info : clients.values()) {
                    byte[] nameBytes = info.clientId().getBytes(StandardCharsets.UTF_8);
                    buf.putShort((short) nameBytes.length);
                    buf.put(nameBytes);
                    buf.putShort((short) info.provides().size());
                    for (int id : info.provides()) buf.putInt(id);
                }
                buf.flip();
                byte[] response = new byte[buf.remaining()];
                buf.get(response);
                session.send(MSG_SERVICE_LIST, response);
            }
            default -> log.debug("未知控制消息 type={}", type);
        }
    }
}
