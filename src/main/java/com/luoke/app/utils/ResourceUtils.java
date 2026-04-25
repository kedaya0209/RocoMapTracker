package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    /**
     * 获取资源路径（优先物理路径）
     * 逻辑：
     * 1. 检查外部 resources 目录下是否存在该文件。
     * 2. 若存在，直接返回外部绝对路径。
     * 3. 若不存在，尝试从内置资源中“实时释放”到外部，再返回路径。
     * * 修改影响：确保了 OCR 模型加载等需要物理 File Path 的场景在 Native 模式下依然可用。
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        // 1. 获取外部对应的 File 对象
        File externalFile = getExternalFile(internalPath);

        // 2. 如果外部物理文件不存在，则尝试释放它
        if (!externalFile.exists() && isExtract) {
            log.info("外部路径不存在，尝试从内置资源释放：{}", internalPath);
            extractSingleFile(internalPath);
        }

        // 3. 再次检查是否释放成功
        if (externalFile.exists()) {
            return externalFile.getAbsolutePath();
        }

        // 4. 如果内置也没有（extractSingleFile 失败），抛出异常或返回原始路径
        // 在 Native 模式下，此处如果不报错，后续加载模型时会因为找不到文件直接 Crash
        log.error("无法获取有效的物理资源路径：{}", internalPath);
        return externalFile.getAbsolutePath();
    }


    public static String getExternalPath(String internalPath) {
        return getExternalPath(internalPath, false);
    }

    // 在 ResourceUtils 中增加
    public static byte[] readResourceBytes(String internalPath) throws IOException {
        try (InputStream in = getResourceStream(internalPath)) {
            return in.readAllBytes();
        }
    }

    /**
     * 读取资源文件的每一行（优先外部物理文件）
     * 修改影响：
     * 1. 显式指定 UTF-8，防止在不同系统（如 Windows 中文版）下读取字典文件出现乱码。
     * 2. 使用 BufferedReader 替代 Scanner，在大文件（如几千行的识别字典）下效率更高。
     */
    public static List<String> readResourceLines(String internalPath) throws IOException {
        List<String> result = new ArrayList<>();
        // getResourceStream 内部已经处理了“外部优先”逻辑
        try (InputStream in = getResourceStream(internalPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 过滤掉空行，OCR 字典通常不包含空行
                if (!line.isBlank()) {
                    result.add(line);
                }
            }
        }
        return result;
    }

}