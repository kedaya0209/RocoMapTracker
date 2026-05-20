package com.luoke.app.config;

import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
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

    static {
        String v = "unknown";
        String n = "RocoMapTracker";
        try (InputStream is = BuildConfig.class.getClassLoader()
                .getResourceAsStream("version.properties")) {
            if (is != null) {
                Properties prop = new Properties();
                prop.load(is);
                v = prop.getProperty("app.version", v);
                n = prop.getProperty("app.name", n);
            } else {
                log.warn("version.properties not found on classpath");
            }
        } catch (Exception e) {
            log.error("Failed to load version.properties", e);
        }
        APP_VERSION = v;
        APP_NAME = n;
    }

    private BuildConfig() {
        throw new AssertionError("禁止实例化配置类");
    }
}
