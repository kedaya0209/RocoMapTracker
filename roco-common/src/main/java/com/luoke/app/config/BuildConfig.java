package com.luoke.app.config;

import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * 构建时版本信息 — 读取 Maven 过滤后的 version.properties。
 */
@Slf4j
public final class BuildConfig {

    /** 应用版本号，如 "1.1.1" */
    public static final String APP_VERSION;
    /** 应用名 */
    public static final String APP_NAME;
    /**
     * 构建时间
     */
    public static final String BUILD_TIMESTAMP;

    static {
        String v = "unknown";
        String n = "RocoMapTracker";
        String t = "unknown";
        try (InputStream is = ResourceUtils.getResourceStream("version.properties")) {
            Properties prop = new Properties();
            prop.load(is);
            v = prop.getProperty("app.version", v);
            n = prop.getProperty("app.name", n);
            t = prop.getProperty("app.buildTimestamp", t);
        } catch (Exception e) {
            log.error("Failed to load version.properties", e);
        }
        APP_VERSION = v;
        APP_NAME = n;
        // 格式化 ISO 时间戳为可读格式 (如 "2026-05-20T19:57:18Z" → "2026-05-20 19:57:18")
        if (t != null && t.contains("T")) {
            try {
                t = OffsetDateTime.parse(t)
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                // 保留原始值
            }
        }
        BUILD_TIMESTAMP = t;
    }

    private BuildConfig() {
        throw new AssertionError("禁止实例化配置类");
    }
}
