package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.ThreadSafe;

/**
 * Sniffer 插件配置常量 — 抓包子进程插件参数。
 */
@ThreadSafe
public final class SnifferConfig {

    public static final String NPCAP_LINK = "https://npcap.com/";

    private SnifferConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    /** sniffer exe 路径（相对于 app root） */
    public static final String SNIFFER_EXE = "/plugins/sniffer/RocoMapTracker-sniffer.exe";

    public static final String SNIFFER_REPO = "kedaya0209/RocoMapTracker-sniffer";

    /** 崩溃后最大连续重启次数 */
    public static final int MAX_RESTART_ATTEMPTS = 5;

    /** 崩溃后重启延迟（秒） */
    public static final int RESTART_DELAY_SEC = 3;
}
