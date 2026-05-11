package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 资源管理工具类，处理Native Image环境下的资源加载
 */
@Slf4j
public class ResourceUtils {

    private static final String RESOURCE_BASE_DIR = "resources";


    /**
     * 单个文件释放（Native Image安全模式），带 MD5 校验。
     * 如果外部文件已存在且 MD5 与内置资源一致则跳过，不一致则覆盖重写。
     * @param internalPath 内部资源路径
     */
    public static void extractSingleFile(String internalPath) {
        try {
            File externalFile = getExternalFile(internalPath);

            if (externalFile.exists()) {
                // MD5 校验：内置资源有更新时自动覆盖
                String internalMD5 = computeResourceMD5(internalPath);
                if (internalMD5 != null) {
                    String externalMD5 = computeFileMD5(externalFile);
                    if (internalMD5.equals(externalMD5)) {
                        log.debug("MD5 一致，跳过：{}", internalPath);
                        return;
                    }
                    log.info("MD5 不一致，覆盖更新：{}", internalPath);
                } else {
                    log.debug("无法计算内置资源 MD5，复用已有文件：{}", internalPath);
                    return;
                }
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

    // ==================== MD5 校验 ====================

    /**
     * 计算内置资源的 MD5 十六进制字符串，资源不存在时返回 null。
     */
    private static String computeResourceMD5(String internalPath) {
        try (InputStream in = ResourceUtils.class.getResourceAsStream(internalPath)) {
            if (in == null) return null;
            return computeMD5(in);
        } catch (IOException e) {
            log.warn("计算内置资源 MD5 失败：{}", internalPath, e);
            return null;
        }
    }

    /**
     * 计算文件的 MD5 十六进制字符串。
     */
    private static String computeFileMD5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return computeMD5(in);
        } catch (IOException e) {
            log.warn("计算文件 MD5 失败：{}", file.getAbsolutePath(), e);
            return "";
        }
    }

    /**
     * 从 InputStream 计算 MD5 十六进制字符串。
     */
    private static String computeMD5(InputStream in) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 算法不可用", e);
        }
    }

}
