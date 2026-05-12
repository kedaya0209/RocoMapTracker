package com.luoke.app.socket;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Socket Server — 全局单例, 程序启动时开启, 常驻后台
 *
 * 职责:
 *   1. 监听端口, accept 客户端连接
 *   2. 每连接启动一条 recv 线程, 读取消息 → 按 type 分发到注册的 SocketHandler
 *   3. 管理 SocketHandler 注册/移除
 *
 * 用法:
 *   SocketServer server = SocketServer.instance();
 *   int port = server.start();           // 程序启动时
 *   server.register(captureHandler);     // 注册处理器
 *   server.stop();                       // 程序退出时
 */
@Slf4j
public class SocketServer {

    private static final SocketServer INSTANCE = new SocketServer();

    public static SocketServer instance() { return INSTANCE; }

    // ---- 状态 ----
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    // sessionId → session
    private final Map<Long, SocketSession> sessions = new ConcurrentHashMap<>();

    // msgType → handlers (CopyOnWrite 保证 dispatch 线程安全)
    private final Map<Integer, CopyOnWriteArrayList<SocketHandler>> dispatch = new ConcurrentHashMap<>();

    // 所有注册过的 handler 合集 (用于 onConnect/onDisconnect 通知)
    private final Set<SocketHandler> allHandlers = ConcurrentHashMap.newKeySet();

    // ---- 启动 / 停止 ----

    /**
     * 启动 ServerSocket, 返回实际监听端口
     */
    public int start() throws IOException {
        if (running.get()) return serverSocket.getLocalPort();

        serverSocket = new ServerSocket(0, 1); // port=0 → OS 随机分配
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
        try { serverSocket.close(); } catch (IOException ignored) {}

        // 等待 accept 线程结束
        if (acceptThread != null) {
            try { acceptThread.join(2000); } catch (InterruptedException ignored) {}
        }

        // 关闭所有 session (各 recv 线程会自然退出)
        for (SocketSession s : sessions.values()) s.close();

        sessions.clear();
        dispatch.clear();
        allHandlers.clear();

        log.info("SocketServer stopped");
    }

    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    public boolean isRunning() { return running.get(); }

    // ---- Handler 注册 ----

    /**
     * 注册处理器: 它的 messageTypes() 会注册到 dispatch 表
     */
    public void register(SocketHandler handler) {
        allHandlers.add(handler);
        for (int type : handler.messageTypes()) {
            dispatch.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        }
        log.debug("Registered handler {} for types {}", handler.getClass().getSimpleName(),
                handler.messageTypes());
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
    }

    // ---- Accept 循环 ----

    private void acceptLoop() {
        while (running.get()) {
            try {
                @SuppressWarnings("resource")
                Socket sock = serverSocket.accept();
                SocketSession session = new SocketSession(sock);
                sessions.put(session.id(), session);

                log.info("Accepted connection #{} from {}", session.id(),
                        sock.getInetAddress());

                // 通知所有 handler: 新连接
                for (SocketHandler h : allHandlers) {
                    try { h.onConnect(session); } catch (Exception e) {
                        log.error("onConnect error in {}", h.getClass().getSimpleName(), e);
                    }
                }

                // 为此 session 启动 recv 线程
                Thread recvThread = new Thread(() -> recvLoop(session),
                        "socket-recv-" + session.id());
                recvThread.setDaemon(true);
                recvThread.start();

            } catch (IOException e) {
                if (running.get()) log.error("Accept error", e);
                break;
            }
        }
    }

    // ---- Recv + Dispatch 循环 ----

    private void recvLoop(SocketSession session) {
        while (running.get() && !session.isClosed()) {
            try {
                SocketSession.Message msg = session.recv();
                if (msg == null) break;

                // 异步分发: recv 线程不等待 handler, 立即返回读取下一条消息
                List<SocketHandler> handlers = dispatch.get(msg.type());
                if (handlers != null && !handlers.isEmpty()) {
                    Thread.ofVirtual().start(() -> {
                        for (SocketHandler h : handlers) {
                            try {
                                h.onMessage(msg.type(), msg.body(), session);
                            } catch (Exception e) {
                                log.error("onMessage error in {} for type={}",
                                        h.getClass().getSimpleName(), msg.type(), e);
                            }
                        }
                    });
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

        // 通知所有 handler: 断连
        for (SocketHandler h : allHandlers) {
            try { h.onDisconnect(session, "Connection closed"); } catch (Exception e) {
                log.error("onDisconnect error in {}", h.getClass().getSimpleName(), e);
            }
        }

        log.info("Session #{} closed", session.id());
    }
}
