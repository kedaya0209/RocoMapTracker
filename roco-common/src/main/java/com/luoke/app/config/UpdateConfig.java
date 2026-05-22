package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;

import java.util.Properties;

/**
 * 自动更新配置持久化
 */
@NotThreadSafe
public final class UpdateConfig {

    /**
     * 自动检查更新开关
     */
    public static boolean CHECK_ENABLED = true;
    /**
     * 检查间隔（小时）
     */
    public static int CHECK_INTERVAL_HOURS = 24;
    /**
     * 发现更新时自动下载
     */
    public static boolean AUTO_DOWNLOAD;
    /**
     * 下载源：gh-proxy（默认）/ jsdelivr / github
     */
    public static String DOWNLOAD_SOURCE = "gh-proxy";

    private UpdateConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        CHECK_ENABLED = ConfigHelper.getBool(prop, "update.check.enabled", true);
        CHECK_INTERVAL_HOURS = ConfigHelper.getInt(prop, "update.check.interval.hours", 24);
        AUTO_DOWNLOAD = ConfigHelper.getBool(prop, "update.auto.download", false);
        DOWNLOAD_SOURCE = ConfigHelper.getStr(prop, "update.download.source", "gh-proxy");
    }

    public static void save(StringBuilder sb) {
        sb.append("# 自动检查更新开关\n");
        sb.append("update.check.enabled=").append(CHECK_ENABLED).append("\n");
        sb.append("# 检查间隔（小时）\n");
        sb.append("update.check.interval.hours=").append(CHECK_INTERVAL_HOURS).append("\n");
        sb.append("# 发现更新时自动下载\n");
        sb.append("update.auto.download=").append(AUTO_DOWNLOAD).append("\n");
        sb.append("# 下载源：gh-proxy / jsdelivr / github\n");
        sb.append("update.download.source=").append(DOWNLOAD_SOURCE).append("\n\n");
    }
}
