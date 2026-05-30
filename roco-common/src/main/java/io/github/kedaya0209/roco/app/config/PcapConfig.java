package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.ThreadSafe;

/**
 * Pcap 桥接器配置常量 — rmt_bridge.py 子进程参数。
 */
@ThreadSafe
public final class PcapConfig {

    public static final String NPCAP_LINK = "https://npcap.com/";

    private PcapConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    /** pcap exe 内嵌路径 */
    public static final String PCAP_EXE = "/plugins/RocoMapTracker-pcap.exe";
    
    public static final String PCAP_REPO = "kedaya0209/RocoMapTracker-pacp";

    /** 崩溃后最大连续重启次数 */
    public static final int MAX_RESTART_ATTEMPTS = 5;

    /** 崩溃后重启延迟（秒） */
    public static final int RESTART_DELAY_SEC = 3;
}
