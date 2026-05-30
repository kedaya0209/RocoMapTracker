package io.github.kedaya0209.roco.app.ui.service.lifecycle;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.match.SiftMatchHandler;
import io.github.kedaya0209.roco.app.match.SiftVariant;
import io.github.kedaya0209.roco.app.process.NativeProcess;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * SIFT 客户端生命周期管理。
 */
@Slf4j
@ThreadSafe
public class SiftClientManager {

    @Getter
    private SiftMatchHandler client;

    /**
     * 初始化并启动 SIFT 客户端
     */
    public void init() {
        client = new SiftMatchHandler(SocketServer.instance(), NativeProcess::create,
                HookRegistry.INSTANCE::publish);
        client.registerToServer(SocketServer.instance());

        client.start((ready, detail) -> {
            if (ready) {
                log.info("SIFT 匹配引擎就绪: {}", detail);
            } else {
                log.warn("SIFT 匹配引擎未就绪: {}", detail);
            }
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent(ready ? "SIFT引擎已就绪" : "SIFT引擎未连接: " + detail,
                            ready ? NotificationType.SUCCESS : NotificationType.ERROR));
        });
    }

    /**
     * 重启客户端（变体切换时调用）
     */
    public void restartClient(String variantName) {
        if (client != null) {
            client.restart(SiftVariant.variantOrdinal(variantName));
        }
    }

    /**
     * 停止客户端
     */
    public void stop() {
        if (client != null) {
            client.stop();
            client = null;
        }
    }
}
