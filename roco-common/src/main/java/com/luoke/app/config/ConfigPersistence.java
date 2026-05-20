package com.luoke.app.config;

import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 配置持久化管理
 *
 * <p>负责将各领域 Config 类的内存值写入磁盘 properties 文件，
 * 并在启动时从磁盘加载覆盖默认值。
 */
@Slf4j
public final class ConfigPersistence {

    private static final String CONFIG_FILE_NAME = "app_config.properties";

    private ConfigPersistence() {
        throw new AssertionError("禁止实例化配置类");
    }

    /**
     * 初始化配置：从磁盘加载，若文件不存在则创建默认配置。
     */
    public static void init() {
        loadConfig();
    }

    /**
     * 将当前内存中的配置持久化到磁盘
     */
    public static void save() {
        try {
            File configFile = ResourceUtils.getExternalFile(CONFIG_FILE_NAME);
            StringBuilder sb = new StringBuilder();
            sb.append("# ==============================================\n");
            sb.append("# 洛克导航 - 用户配置文件 (UTF-8)\n");
            sb.append("# 注：路径等常量在代码中固定，此处仅存储可调参数\n");
            sb.append("# ==============================================\n\n");

            CaptureConfig.save(sb);
            DownloadConfig.save(sb);
            SocketConfig.save(sb);
            UiConfig.save(sb);
            ViewConfig.save(sb);
            RenderConfig.save(sb);
            SiftConfig.save(sb);
            PlayerConfig.save(sb);
            OcrConfig.save(sb);
            MiniMapConfig.save(sb);
            StatsConfig.save(sb);
            NavigConfig.save(sb);
            UpdateConfig.save(sb);

            try (FileOutputStream fos = new FileOutputStream(configFile);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                fos.write(0xEF);
                fos.write(0xBB);
                fos.write(0xBF); // UTF-8 BOM
                osw.write(sb.toString());
            }
            log.info("配置文件已保存至: {}", configFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("配置文件保存失败", e);
        }
    }

    private static void loadConfig() {
        try {
            File configFile = ResourceUtils.getExternalFile(CONFIG_FILE_NAME);
            if (configFile.exists()) {
                Properties prop = new Properties();
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                    prop.load(reader);
                }
                overrideFromProperties(prop);
                log.info("已从本地文件加载用户配置");
            } else {
                save();
            }
        } catch (Exception e) {
            log.error("加载配置异常，使用默认值", e);
        }
    }

    static void overrideFromProperties(Properties prop) {
        CaptureConfig.load(prop);
        DownloadConfig.load(prop);
        SocketConfig.load(prop);
        UiConfig.load(prop);
        ViewConfig.load(prop);
        RenderConfig.load(prop);
        SiftConfig.load(prop);
        PlayerConfig.load(prop);
        OcrConfig.load(prop);
        MiniMapConfig.load(prop);
        StatsConfig.load(prop);
        NavigConfig.load(prop);
        UpdateConfig.load(prop);
    }
}
