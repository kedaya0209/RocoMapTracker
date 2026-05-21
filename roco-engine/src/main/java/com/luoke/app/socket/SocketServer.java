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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Socket Server — 全局单例, 程序启动时开启, 常驻后台
 * 职责:
 * 1. 监听端口, accept 客户端连接
 * 2. 每连接启动一条 recv 线程, 读取消息 → 按 type 分发到注册的 SocketHandler
 * 3. 管理 SocketHandler 注册/移除
 * 用法:
 * SocketServer server = SocketServer.instance();
 * int port = server.start();           // 程序启动时
 * server.register(captureHandler);     // 注册处理器
 * server.stop();                       // 程序退出时
 */
@ThreadSafe
@Slf4j
public class SocketServer {

    /**
     * 握手消息: C++ 连接后第一条消息，body 为 UTF-8 客户端类型 ("capture"/"sift")
     */
    static final int MSG_HELLO = 1;
    private static final SocketServer INSTANCE = new SocketServer();

    // ---- 常量 ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    // sessionId → session
    private final Map<Long, SocketSession> sessions = new ConcurrentHashMap<>();
    // msgType → handlers (CopyOnWrite 保证 dispatch 线程安全)
    private final Map<Integer, CopyOnWriteArrayList<SocketHandler>> dispatch = new ConcurrentHashMap<>();
    // 所有注册过的 handler 合集 (用于 register/unregister/stop)
    private final Set<SocketHandler> allHandlers = ConcurrentHashMap.newKeySet();
    // sessionId → 该连接绑定的 handler 集合 (由 HELLO 消息决定)
    private final Map<Long, Set<SocketHandler>> sessionHandlers = new ConcurrentHashMap<>();
    // clientType → handler (HELLO 握手路由)
    private final Map<String, SocketHandler> clientHandlers = new ConcurrentHashMap<>();
    // sessionId → 该连接支持的消息类型 (HELLO 中自报，供转发逻辑使用)
    private final Map<Long, Set<Integer>> sessionMsgTypes = new ConcurrentHashMap<>();
    // ---- 状态 ----
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public static SocketServer instance() {
        return INSTANCE;
    }

    // ---- 启动 / 停止 ----

    /**
     * 启动 ServerSocket, 返回实际监听端口
     */
    public int start() throws IOException {
        if (running.get()) return serverSocket.getLocalPort();

        serverSocket = new ServerSocket(0, SocketConfig.SOCKET_BACKLOG); // port=0 → OS 随机分配
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "socket-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();

        int port = serverSocket.getLocalPort();
        log.info("SocketServer started on port {}", port);
        return port;
    }

    /**
     * 关闭 ServerSocket 及所有会话
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) return;

        // 关闭 ServerSocket (中断 accept)
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        // 等待 accept 线程结束
        if (acceptThread != null) {
            try {
                acceptThread.join(2000);
            } catch (InterruptedException ignored) {
            }
        }

        // 关闭所有 session (各 recv 线程会自然退出)
        for (SocketSession s : sessions.values()) s.close();

        sessions.clear();
        dispatch.clear();
        allHandlers.clear();
        sessionHandlers.clear();
        sessionMsgTypes.clear();
        clientHandlers.clear();

        log.info("SocketServer stopped");
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 返回指定 session 支持的消息类型集合 (由 HELLO 握手上报)，供转发逻辑使用。
     */
    public Set<Integer> getSessionMsgTypes(long sessionId) {
        return sessionMsgTypes.getOrDefault(sessionId, Set.of());
    }

    // ---- Handler 注册 ----

    /**
     * 注册处理器: 它的 messageTypes() 会注册到 dispatch 表
     */
    public void register(SocketHandler handler) {
        allHandlers.add(handler);
        for (int type : handler.messageTypes()) {
            dispatch.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        }
        String ct = handler.clientType();
        if (ct != null && !ct.isBlank()) {
            clientHandlers.put(ct, handler);
        }
        log.debug("Registered handler {} clientType={} for types {}", handler.getClass().getSimpleName(),
                ct, handler.messageTypes());
    }

    /**
     * 移除处理器
     */
    public void unregister(SocketHandler handler) {
        allHandlers.remove(handler);
        for (int type : handler.messageTypes()) {
            List<SocketHandler> list = dispatch.get(type);
            if (list != null) list.remove(handler);
        }
        clientHandlers.values().removeIf(h -> h == handler);
    }

    // ---- Accept 循环 ----

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket sock = serverSocket.accept();
                SocketSession session = new SocketSession(sock);
                sessions.put(session.id(), session);

                log.info("Accepted connection #{} from {}", session.id(),
                        sock.getInetAddress());

                // 不在此处广播 onConnect — 由 recvLoop 收到第一条消息后，
                // 按消息 type 精准路由 onConnect 到对应的 handler。

                // 为此 session 启动 recv 线程（平台线程，避免 Native Image 虚拟线程阻塞 I/O 崩溃）
                Thread.ofPlatform().daemon(true).name("socket-recv-" + session.id())
                        .start(() -> recvLoop(session));
            } catch (IOException e) {
                if (running.get()) log.error("Accept error", e);
                break;
            }
        }
    }

    // ---- Recv + Dispatch 循环 ----

    private void recvLoop(SocketSession session) {
        boolean firstMessage = true;

        while (running.get() && !session.isClosed()) {
            try {
                SocketSession.Message msg = session.recv();
                if (msg == null) break;

                // 第一条消息必须是 HELLO: [2B]clientTypeLen [NB]clientType [2B]msgTypeCount [N*4B]msgTypes
                if (firstMessage) {
                    firstMessage = false;

                    if (msg.type() != MSG_HELLO) {
                        log.warn("Session #{} first msg is not HELLO (type={}), closing",
                                session.id(), msg.type());
                        session.close();
                        break;
                    }

                    if (msg.body() == null || msg.body().length < 4) {
                        log.warn("Session #{} HELLO body too short, closing", session.id());
                        session.close();
                        break;
                    }

                    ByteBuffer helloBuf = ByteBuffer.wrap(msg.body()).order(ByteOrder.BIG_ENDIAN);

                    int nameLen = helloBuf.getShort() & 0xFFFF;
                    if (helloBuf.remaining() < nameLen + 2) {
                        log.warn("Session #{} HELLO truncated at name, closing", session.id());
                        session.close();
                        break;
                    }
                    byte[] nameBytes = new byte[nameLen];
                    helloBuf.get(nameBytes);
                    String clientType = new String(nameBytes, StandardCharsets.UTF_8);

                    int typeCount = helloBuf.getShort() & 0xFFFF;
                    if (helloBuf.remaining() < typeCount * 4) {
                        log.warn("Session #{} HELLO truncated at types, closing", session.id());
                        session.close();
                        break;
                    }
                    Set<Integer> supportedTypes = new HashSet<>();
                    for (int i = 0; i < typeCount; i++) {
                        supportedTypes.add(helloBuf.getInt());
                    }
                    sessionMsgTypes.put(session.id(), supportedTypes);

                    SocketHandler handler = clientHandlers.get(clientType);
                    if (handler == null) {
                        log.warn("Session #{} unknown clientType='{}', closing",
                                session.id(), clientType);
                        session.close();
                        break;
                    }

                    Set<SocketHandler> bound = ConcurrentHashMap.newKeySet();
                    bound.add(handler);
                    sessionHandlers.put(session.id(), bound);

                    try {
                        handler.onConnect(session);
                    } catch (Exception e) {
                        // handler 回调可能抛出多种异常，保留通用捕获
                        log.error("onConnect error in {}", handler.getClass().getSimpleName(), e);
                    }

                    log.info("Session #{} bound to {} (clientType='{}', types={})",
                            session.id(), handler.getClass().getSimpleName(), clientType, supportedTypes);
                    continue; // HELLO 被消费，不分发到 onMessage
                }

                // 后续消息：按 msgType 查 dispatch 表分发
                List<SocketHandler> handlers = dispatch.get(msg.type());

                if (handlers != null && !handlers.isEmpty()) {
                    for (SocketHandler h : handlers) {
                        try {
                            h.onMessage(msg.type(), msg.body(), session);
                        } catch (Exception e) {
                            // handler 回调可能抛出多种异常，保留通用捕获
                            log.error("onMessage error in {} for type={}",
                                    h.getClass().getSimpleName(), msg.type(), e);
                        }
                    }
                } else {
                    log.debug("No handler for msgType={}", msg.type());
                }

            } catch (IOException e) {
                break;
            }
        }

        // 清理
        session.close();
        sessions.remove(session.id());
        sessionMsgTypes.remove(session.id());

        // 只通知本 session 绑定的 handler 断连
        Set<SocketHandler> bound = sessionHandlers.remove(session.id());
        if (bound != null) {
            for (SocketHandler h : bound) {
                try {
                    h.onDisconnect(session, "Connection closed");
                } catch (Exception e) {
                    // handler 回调可能抛出多种异常，保留通用捕获
                    log.error("onDisconnect error in {}", h.getClass().getSimpleName(), e);
                }
            }
        }

        log.info("Session #{} closed", session.id());
    }
}
