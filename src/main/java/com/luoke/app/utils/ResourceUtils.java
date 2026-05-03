package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源管理工具类，处理Native Image环境下的资源加载
 */
@Slf4j
public class ResourceUtils {

    private static final String RESOURCE_BASE_DIR = "resources";
    private static final String RESOURCE_LIST_FILE = "/resource-list.txt";

    /**
     * 释放所有内置资源到外部文件系统
     */
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

    /**
     * 从内置资源文件中加载资源路径列表
     * @return 资源路径列表
     */
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

    /**
     * 单个文件释放（Native Image安全模式）
     * @param internalPath 内部资源路径
     */
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

    /**
     * 获取资源输入流，优先使用外部资源
     * @param internalPath 内部资源路径
     * @return 资源的输入流
     * @throws RuntimeException 如果资源读取失败
     */
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

    /**
     * 获取外部资源的文件路径
     * @param internalPath 内部资源路径
     * @return 外部文件对象
     */
    public static File getExternalFile(String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return FileUtil.getRelativeFile(RESOURCE_BASE_DIR, safePath);
    }

    /**
     * 获取资源路径（优先物理路径）
     * @param internalPath 内部资源路径
     * @param isExtract 是否提取
     * @return 外部绝对路径
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        File externalFile = getExternalFile(internalPath);

        if (!externalFile.exists() && isExtract) {
            log.info("外部路径不存在，尝试从内置资源释放：{}", internalPath);
            extractSingleFile(internalPath);
        }

        if (externalFile.exists()) {
            return externalFile.getAbsolutePath();
        }

        log.error("无法获取有效的物理资源路径：{}", internalPath);
        return externalFile.getAbsolutePath();
    }

    public static String getExternalPath(String internalPath) {
        return getExternalPath(internalPath, false);
    }

    /**
     * 读取资源文件的所有字节
     * @param internalPath 内部资源路径
     * @return 资源文件的字节数组
     * @throws IOException 如果资源读取失败
     */
    public static byte[] readResourceBytes(String internalPath) throws IOException {
        try (InputStream in = getResourceStream(internalPath)) {
            return in.readAllBytes();
        }
    }

    /**
     * 读取资源文件的每一行（优先外部物理文件）
     * @param internalPath 内部资源路径
     * @return 包含所有非空行的列表
     * @throws IOException 如果资源读取失败
     */
    public static List<String> readResourceLines(String internalPath) throws IOException {
        List<String> result = new ArrayList<>();

        try (InputStream in = getResourceStream(internalPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    result.add(line);
                }
            }
        }

        return result;
    }

}
