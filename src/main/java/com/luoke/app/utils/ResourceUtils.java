package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class ResourceUtils {

    private static final String RESOURCE_BASE_DIR = "resources";
    private static final String RESOURCE_LIST_FILE = "/resource-list.txt";

    // ============================
    // 程序启动只调用这一个方法
    // ============================
    public static void extractAll() {
        log.info("=====================================");
        log.info("开始释放内置资源（Native 兼容模式）");

        List<String> resourceFiles = loadResourceList();
        for (String path : resourceFiles) {
            extractSingleFile(path);
        }

        log.info("✅ 资源释放完成！外部目录：{}", FileUtil.getRelativeFile(RESOURCE_BASE_DIR));
        log.info("=====================================\n");
    }

    // ============================
    // 从内置资源文件读取路径列表
    // ============================
    public static List<String> loadResourceList() {
        List<String> list = new ArrayList<>();
        try (InputStream in = ResourceUtils.class.getResourceAsStream(RESOURCE_LIST_FILE)) {
            if (in == null) {
                log.warn("未找到资源列表文件：{}", RESOURCE_LIST_FILE);
                return list;
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;
            while ((line = br.readLine()) != null) {
                String trim = line.trim();
                if (!trim.isEmpty() && !trim.startsWith("#")) {
                    list.add(trim);
                }
            }
            log.info("✅ 加载资源列表成功，共 {} 个文件", list.size());
        } catch (Exception e) {
            log.error("❌ 加载资源列表失败", e);
        }
        return list;
    }

    // ============================
    // 单个文件释放（Native 安全）
    // ============================
    public static void extractSingleFile(String internalPath) {
        try {
            File externalFile = getExternalFile(internalPath);
            if (externalFile.exists()) {
                log.debug("已存在，跳过：{}", internalPath);
                return;
            }

            try (InputStream in = ResourceUtils.class.getResourceAsStream(internalPath)) {
                if (in == null) {
                    log.warn("资源不存在：{}", internalPath);
                    return;
                }

                externalFile.getParentFile().mkdirs();
                Files.copy(in, externalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("释放：{} → {}", internalPath, externalFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("释放失败：{}", internalPath, e);
        }
    }

    // ============================
    // 【核心】优先读取外部资源
    // ============================
    public static InputStream getResourceStream(String internalPath) {
        try {
            File external = getExternalFile(internalPath);
            if (external.exists()) {
                log.debug("使用外部资源：{}", external.getPath());
                return new FileInputStream(external);
            }

            InputStream internal = ResourceUtils.class.getResourceAsStream(internalPath);
            if (internal != null) {
                log.debug("使用内置资源：{}", internalPath);
                return internal;
            }

            throw new FileNotFoundException("资源不存在：" + internalPath);
        } catch (Exception e) {
            throw new RuntimeException("读取资源失败：" + internalPath, e);
        }
    }

    // ============================
    // 获取外部资源路径
    // ============================
    public static File getExternalFile(String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return FileUtil.getRelativeFile(RESOURCE_BASE_DIR, safePath);
    }

    public static String getExternalPath(String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return FileUtil.getAppRootDir().resolve(Path.of(RESOURCE_BASE_DIR, safePath)).toAbsolutePath().toString();
    }
}