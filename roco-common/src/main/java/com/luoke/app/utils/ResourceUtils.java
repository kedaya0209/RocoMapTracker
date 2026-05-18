package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源管理工具类，处理Native Image环境下的资源加载
 */
@Slf4j
public class ResourceUtils {

    private static final String RESOURCE_BASE_DIR = "resources";

    /**
     * 获取资源输入流，优先使用外部资源
     *
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
     * 获取外部资源的文件路径（相对于 appRoot/resources）
     *
     * @see FileUtil#getExternalFile(String, String)
     */
    public static File getExternalFile(String internalPath) {
        return FileUtil.getExternalFile(RESOURCE_BASE_DIR, internalPath);
    }

    /**
     * 获取资源路径（优先物理路径，相对于 appRoot/resources）
     *
     * @see FileUtil#getExternalPath(String, String, boolean)
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        return FileUtil.getExternalPath(RESOURCE_BASE_DIR, internalPath, isExtract);
    }

    /**
     * 读取资源文件的每一行（优先外部物理文件）
     *
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
