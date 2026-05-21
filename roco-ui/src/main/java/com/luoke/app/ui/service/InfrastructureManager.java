package com.luoke.app.ui.service;

import net.jcip.annotations.ThreadSafe;
import com.luoke.app.process.JobObjectManager;
import com.luoke.app.socket.SocketServer;
import lombok.extern.slf4j.Slf4j;

/**
 * 基础设施生命周期管理：JobObject + SocketServer。
 * 静态工具类，与应用同生命周期。
 */
@Slf4j
@ThreadSafe
public class InfrastructureManager {

    private InfrastructureManager() {
    }

    /**
     * 初始化 JobObject + SocketServer
     */
    public static void init() {
        JobObjectManager.init();
        try {
            int port = SocketServer.instance().start();
            log.info("SocketServer 已启动, 端口: {}", port);
        } catch (Exception e) {
            log.error("SocketServer 启动失败", e);
        }
    }

    /**
     * 销毁 SocketServer
     */
    public static void destroy() {
        SocketServer.instance().stop();
    }
}
