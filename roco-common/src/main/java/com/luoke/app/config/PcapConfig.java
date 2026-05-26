package com.luoke.app.config;

import net.jcip.annotations.ThreadSafe;

/**
 * Pcap 桥接器配置常量 — rmt_bridge.py 子进程参数。
 */
@ThreadSafe
public final class PcapConfig {

    private PcapConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    /** pcap exe 内嵌路径 */
    public static final String PCAP_EXE = "/plugins/RocoMapTracker-pcap.exe";

    /** 游戏通信端口（默认 8195） */
    public static final int GAME_PORT = 8195;

    /** 崩溃后最大连续重启次数 */
    public static final int MAX_RESTART_ATTEMPTS = 5;

    /** 崩溃后重启延迟（秒） */
    public static final int RESTART_DELAY_SEC = 3;

    /** 连接 SocketServer 超时（秒） */
    public static final int CONNECT_TIMEOUT_SEC = 5;
}
