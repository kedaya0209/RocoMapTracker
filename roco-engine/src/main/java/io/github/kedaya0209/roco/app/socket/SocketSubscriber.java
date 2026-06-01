package io.github.kedaya0209.roco.app.socket;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

/**
 * TCP 外部客户端的订阅者实现 — 通过 SocketSession 转发消息
 */
@ThreadSafe
@Slf4j
public class SocketSubscriber implements MessageSubscriber {

    private final SocketSession session;
    private final String clientId;

    public SocketSubscriber(SocketSession session, String clientId) {
        this.session = session;
        this.clientId = clientId;
    }

    @Override
    public void onMessage(int serviceId, byte[] body, SocketSession sender) {
        if (!session.send(serviceId, body)) {
            log.warn("转发失败: serviceId={} → {} (session#{})", serviceId, clientId, session.id());
        }
    }

    @Override
    public String clientId() {
        return clientId;
    }

    public SocketSession session() {
        return session;
    }
}
