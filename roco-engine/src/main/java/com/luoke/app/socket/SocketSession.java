package com.luoke.app.socket;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP Socket 会话 — 纯传输层, 只负责 send/recv/close
 * 消息路由和 recv 循环由 SocketServer 统一管理
 */
@NotThreadSafe
@Slf4j
public class SocketSession implements AutoCloseable {

    private static final AtomicLong ID_GEN = new AtomicLong(0);

    private final long id;
    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    /**
     * 消息体缓冲池（3 槽轮转），避免每帧 ~9MB humongous 分配
     */
    private final byte[][] bodyPool = new byte[3][];
    private volatile boolean closed;
    private int poolIndex;

    SocketSession(Socket socket) throws IOException {
        this.id = ID_GEN.incrementAndGet();
        this.socket = socket;
        socket.setTcpNoDelay(true);
        this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public long id() {
        return id;
    }

    /**
     * 同步读取一条消息 (由 SocketServer 的 recv 线程调用)
     */
    public Message recv() throws IOException {
        int type = in.readInt();
        int len = in.readInt();
        byte[] body = null;
        if (len > 0) {
            byte[] buf = bodyPool[poolIndex];
            // 仅当 size 变化时重新分配（帧数据 size 通常恒定）
            if (buf == null || buf.length != len) {
                buf = new byte[len];
                bodyPool[poolIndex] = buf;
            }
            body = buf;
            in.readFully(body, 0, len);
            poolIndex = (poolIndex + 1) % bodyPool.length;
        }
        return new Message(type, body);
    }

    /**
     * 线程安全发送
     */
    public synchronized boolean send(int msgType, byte[] body) {
        if (closed) return false;
        try {
            out.writeInt(msgType);
            out.writeInt(body != null ? body.length : 0);
            if (body != null && body.length > 0) out.write(body);
            out.flush();
            return true;
        } catch (IOException e) {
            closed = true;
            log.error("发送失败 session#{}", id, e);
            return false;
        }
    }

    public boolean isClosed() {
        return closed || socket.isClosed();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 一条消息
     */
    @ThreadSafe
    public record Message(int type, byte[] body) {
    }
}
