package com.luoke.app.capture;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.socket.SocketSession;
import lombok.extern.slf4j.Slf4j;

/**
 * capture.exe Socket 会话管理器 — 单一职责：管理截图子进程的 Socket 会话状态。
 * <p>不持有任何 NativeProcess 引用，与进程管理完全解耦。</p>
 */
@NotThreadSafe
@Slf4j
public class CaptureSessionManager {

    private volatile SocketSession session;
    private volatile boolean handshakeDone;

    /**
     * 新会话绑定
     */
    public void onConnect(SocketSession session) {
        this.session = session;
        this.handshakeDone = false;
        log.debug("CaptureHandler connected on session#{}", session.id());
    }

    /**
     * 会话断开
     */
    public void onDisconnect() {
        this.session = null;
        this.handshakeDone = false;
    }

    /**
     * 发送消息
     *
     * @return 发送是否成功
     */
    public boolean send(int type, byte[] body) {
        SocketSession s = session;
        return s != null && !s.isClosed() && s.send(type, body);
    }

    public boolean isConnected() {
        return session != null && !session.isClosed();
    }

    public boolean isHandshakeDone() {
        return handshakeDone;
    }

    public void setHandshakeDone(boolean done) {
        this.handshakeDone = done;
    }

    /**
     * 完全重置会话状态
     */
    public void reset() {
        session = null;
        handshakeDone = false;
    }
}
