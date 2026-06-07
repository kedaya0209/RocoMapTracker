package io.github.kedaya0209.roco.app.utils;

import io.github.kedaya0209.roco.app.config.DownloadConfig;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;


/**
 * 资源管理工具类，处理Native Image环境下的资源加载
 */
@Slf4j
@ThreadSafe
public class ResourceUtils {

    private static final String RESOURCE_BASE_DIR = "resources";

    /**
     * 获取资源输入流。
     * <p>
     * INTERNAL 模式仅从 classpath 加载；EXTERNAL 模式优先使用外部文件，回退到 classpath。
     *
     * @param internalPath 内部资源路径
     * @return 资源的输入流
     * @throws IOException 如果资源读取失败
     */
    public static InputStream getResourceStream(String internalPath) throws IOException {
        // INTERNAL 模式：仅从 classpath 加载，不读取外部文件
        if (DownloadConfig.INTERNAL_RESOURCE) {
            InputStream internal = ResourceUtils.class.getResourceAsStream(internalPath);
            if (internal != null) {
                return internal;
            }
            throw new FileNotFoundException("内置资源不存在：" + internalPath);
        }

        // EXTERNAL 模式：外部文件优先，回退 classpath
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
    }

    /**
     * 获取外部资源的文件路径（相对于 appRoot/resources）
     *
     * @see FilePathUtil#getExternalFile(String, String)
     */
    public static File getExternalFile(String internalPath) {
        return FilePathUtil.getExternalFile(RESOURCE_BASE_DIR, internalPath);
    }

    /**
     * 获取资源路径（优先物理路径，相对于 appRoot/resources）
     *
     * @see FilePathUtil#getExternalPath(String, String, boolean)
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        return FilePathUtil.getExternalPath(RESOURCE_BASE_DIR, internalPath, isExtract);
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
