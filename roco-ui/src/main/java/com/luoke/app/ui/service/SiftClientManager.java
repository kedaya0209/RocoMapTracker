package com.luoke.app.ui.service;

import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.macher.SiftVariant;
import com.luoke.app.socket.SocketServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * SIFT 客户端生命周期管理。
 */
@Slf4j
public class SiftClientManager {

    @Getter
    private SiftMatchHandler client;

    /**
     * 初始化并启动 SIFT 客户端
     */
    public void init() {
        client = new SiftMatchHandler();
        SocketServer.instance().register(client);

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
