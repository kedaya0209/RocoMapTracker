package io.github.kedaya0209.roco.app.capture;

import lombok.Getter;
import lombok.Setter;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.socket.SocketSession;
import lombok.extern.slf4j.Slf4j;

/**
 * capture.exe Socket 会话管理器 — 单一职责：管理截图子进程的 Socket 会话状态。
 * <p>不持有任何 NativeProcess 引用，与进程管理完全解耦。</p>
 */
@NotThreadSafe
@Slf4j
public class CaptureSessionManager {

    private volatile SocketSession session;
    @Setter
    @Getter
    private volatile boolean handshakeDone;

    /**
     * 新会话绑定
     */
    public void onConnect(SocketSession session) {
        this.session = session;
        this.handshakeDone = false;
        log.debug("CaptureHandler 已连接 session#{}", session.id());
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

    /**
     * 判断给定的 session 是否为当前活跃的会话。
     * 用于避免旧 session 的断开回调误触发重启。
     */
    public boolean isCurrentSession(SocketSession s) {
        return session != null && s != null && session.id() == s.id();
    }

    /**
     * 完全重置会话状态
     */
    public void reset() {
        session = null;
        handshakeDone = false;
    }
}
