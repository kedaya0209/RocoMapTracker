package com.luoke.app.ui.service;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.pcap.PcapProcessManager;
import com.luoke.app.process.NativeProcess;
import com.luoke.app.socket.ExternalBridgeHandler;
import com.luoke.app.socket.ExternalBridgeProtocol;
import com.luoke.app.socket.HandlerSubscriber;
import com.luoke.app.socket.SocketServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * pcap 桥接器生命周期管理 — 协调 rmt_bridge.py 子进程与外部事件注册。
 * <p>
 * 职责：
 * <ul>
 *   <li>启动/停止 pcap.exe 子进程</li>
 *   <li>向 SocketServer 注册 ExternalBridgeHandler（匹配启停、区域变更、物资拾取）</li>
 *   <li>崩溃后根据 PcapProcessManager 策略自动重启</li>
 * </ul>
 * <p>
 * 与 {@link SiftClientManager} 和 {@link CaptureServiceManager} 模式对称。
 */
@NotThreadSafe
@Slf4j
public class PcapBridgeManager {

    @Getter
    private final PcapProcessManager processManager;
    private ExternalBridgeHandler bridgeHandler;
    private boolean initialized = false;

    public PcapBridgeManager() {
        this.processManager = new PcapProcessManager(NativeProcess::create);
    }

    /**
     * 初始化：注册事件处理器 + 启动 pcap.exe 子进程。
     * <p>
     * 支持重复调用 — 已初始化时先停止再重新初始化。
     *
     * @param serverPort SocketServer 端口（{@link SocketServer#getPort()}）
     * @param iface      网卡名，null 表示自动检测
     */
    public void init(int serverPort, String iface) {
        if (initialized) {
            log.info("PcapBridgeManager 重新初始化");
            stop();
        }

        bridgeHandler = new ExternalBridgeHandler(
                () -> SiftConfig.SIFT_MATCHING_ENABLED = false,
                () -> SiftConfig.SIFT_MATCHING_ENABLED = true);

        registerHandlers();

        boolean started = processManager.start(serverPort, iface);
        if (started) {
            initialized = true;
            log.info("PcapBridgeManager 初始化完成 (端口={})", serverPort);
        } else {
            log.error("PcapBridgeManager 初始化失败 — pcap.exe 未启动");
        }
    }

    /**
     * @return 是否已初始化
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 停止：反注册处理器 + 停止子进程。
     */
    public void stop() {
        if (!initialized && processManager.getActiveProcess() == null) {
            return;
        }
        processManager.stop();
        bridgeHandler = null;
        initialized = false;
        log.info("PcapBridgeManager 已停止");
    }

    private void registerHandlers() {
        HandlerSubscriber subscriber = new HandlerSubscriber(bridgeHandler, "pcap-bridge");
        SocketServer.instance().registerInternal(ExternalBridgeProtocol.MSG_STOP_MATCHING, subscriber);
        SocketServer.instance().registerInternal(ExternalBridgeProtocol.MSG_START_MATCHING, subscriber);
        SocketServer.instance().registerInternal(ExternalBridgeProtocol.MSG_AREA_CHANGE, subscriber);
        SocketServer.instance().registerInternal(ExternalBridgeProtocol.MSG_ITEM_PICKUP, subscriber);
    }
}
