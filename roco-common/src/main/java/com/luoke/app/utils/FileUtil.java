package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Scanner;

@Slf4j
public class FileUtil {

    private static final String extractName = "/extract-list.txt";

    public static Path getAppRootDir() {
        // 1. 判定是否为 GraalVM Native Image
        // Native Image环境会设置此系统属性指向可执行文件的完整路径
        String nativeImagePath = System.getProperty("org.graalvm.nativeimage.imagepath");
        if (nativeImagePath != null && !nativeImagePath.isEmpty()) {
            // 返回可执行文件的父目录（即应用根目录）
            return Paths.get(nativeImagePath).getParent().toAbsolutePath();
        }

        // 2. 判定是否为 JAR 运行环境
        try {
            // 通过类的ProtectionDomain获取CodeSource
            // CodeSource包含类文件的来源位置（URL或文件路径）
            URI uri = FileUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String path = uri.getPath();

            // 检查路径是否以.jar结尾，不区分大小写
            if (path != null && path.toLowerCase().endsWith(".jar")) {
                // 返回JAR文件的父目录
                return new File(uri).getParentFile().toPath().toAbsolutePath();
            }
        } catch (Exception e) {
            // 获取CodeSource可能失败（如在某些特殊环境或SecurityManager限制）
            // 记录警告但不中断，继续尝试其他方法
            log.warn("无法通过 CodeSource 识别环境路径: {}", e.getMessage());
        }

        // 3. 保底方案：开发环境 (IDE)
        // 使用系统属性user.dir，即JVM启动时的工作目录
        // 在IDE中通常是项目根目录（pom.xml所在处）
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    public static boolean isNative() {
        // 检查GraalVM Native Image特有系统属性
        // 此属性仅在Native Image环境中存在
        return System.getProperty("org.graalvm.nativeimage.kind") != null;
    }

    public static File getRelativeFile(String... subPaths) {
        // 从应用根目录开始
        Path path = getAppRootDir();

        // 依次拼接每个路径片段
        for (String sub : subPaths) {
            // 跳过null或空白字符串
            if (sub == null || sub.isBlank()) continue;

            // 核心修复：强制清除开头的斜杠和反斜杠
            // 这样可以确保无论输入是否以/或\开头，都会被当作相对路径拼接
            // 防止路径被替换为绝对路径（如 /data 会替换根路径）
            String safePath = sub.replaceFirst("^[/\\\\]+", "");

            // 拼接到当前路径
            path = path.resolve(safePath);
        }

        // 转换为File对象
        File file = path.toFile();

        // 确保父目录存在
        // 如果父目录不存在，递归创建所有必要的父目录
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        // 返回File对象
        return file;
    }

    public static void extractAll() {
        try (InputStream in = FileUtil.class.getResourceAsStream(extractName)) {
            if (in == null) {
                log.info("未找到【{}】文件,不释放资源", extractName);
                return;
            }
            try (Scanner sc = new Scanner(in)) {

                sc.tokens().forEach(line -> {
                    String[] split = line.split(":");
                    String sourcePath, destPath, operator = null;
                    if (split.length == 2) {
                        sourcePath = split[0];
                        destPath = split[1];
                    } else if (split.length == 3) {
                        sourcePath = split[0];
                        destPath = split[1];
                        operator = split[2];
                    } else {
                        sourcePath = split[0];
                        destPath = split[0];
                    }
                    String formatDestPath = destPath.replaceFirst("^[/\\\\]+", "");
                    File destFile = getRelativeFile(formatDestPath);
                    //如果是native环境才解压的
                    if ("native".equalsIgnoreCase(operator) && !isNative()) return;
                    extractSingleFile(sourcePath, destFile);
            });

        } catch (Exception e) {
            //资源释放失败
            log.error("释放资源失败，e:", e);
        }
        } catch (Exception e) {
            log.error("释放资源失败，e:", e);
        }
    }

    // ==================== MD5 校验 ====================

    public static String computeResourceMD5(String internalPath) {
        try (InputStream in = FileUtil.class.getResourceAsStream(internalPath)) {
            if (in == null) return null;
            return computeMD5(in);
        } catch (IOException e) {
            log.warn("计算内置资源 MD5 失败：{}", internalPath, e);
            return null;
        }
    }

    public static String computeFileMD5(File file) {
        try (InputStream in = new FileInputStream(file)) {
            return computeMD5(in);
        } catch (IOException e) {
            log.warn("计算文件 MD5 失败：{}", file.getAbsolutePath(), e);
            return "";
        }
    }

    public static String computeMD5(InputStream in) throws IOException {
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

    // ==================== 外部文件路径解析 ====================

    /**
     * 将 classpath 资源路径映射为外部物理文件（相对于 app root）。
     * 例: {@code getExternalFile("/dll/sift/sift_match.exe") → <appRoot>/dll/sift/sift_match.exe}
     */
    public static File getExternalFile(String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return getRelativeFile(safePath);
    }

    /**
     * 将 classpath 资源路径映射为外部物理文件（相对于 appRoot/baseDir）。
     * 例: {@code getExternalFile("resources", "/dll/foo.dll") → <appRoot>/resources/dll/foo.dll}
     */
    public static File getExternalFile(String baseDir, String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return getRelativeFile(baseDir, safePath);
    }

    /**
     * 获取外部绝对路径，可选自动提取内置资源。
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        File externalFile = getExternalFile(internalPath);
        ensureExternalFile(internalPath, externalFile, isExtract);
        return externalFile.getAbsolutePath();
    }

    /**
     * 获取外部绝对路径（带 baseDir），可选自动提取内置资源。
     */
    public static String getExternalPath(String baseDir, String internalPath, boolean isExtract) {
        File externalFile = getExternalFile(baseDir, internalPath);
        ensureExternalFile(internalPath, externalFile, isExtract);
        return externalFile.getAbsolutePath();
    }

    /**
     * 单个文件释放（从 classpath 到外部物理路径），带 MD5 校验。
     */
    public static void extractSingleFile(String internalPath) {
        extractSingleFile(internalPath, getExternalFile(internalPath));
    }

    /**
     * 单个文件释放（带 baseDir），带 MD5 校验。
     */
    public static void extractSingleFile(String baseDir, String internalPath) {
        extractSingleFile(internalPath, getExternalFile(baseDir, internalPath));
    }

    private static void ensureExternalFile(String internalPath, File externalFile, boolean isExtract) {
        if (!externalFile.exists() && isExtract) {
            log.info("外部路径不存在，尝试从内置资源释放：{}", internalPath);
            extractSingleFile(internalPath, externalFile);
        }
        if (!externalFile.exists()) {
            log.error("无法获取有效的物理资源路径：{} → {}", internalPath, externalFile.getAbsolutePath());
        }
    }

    private static void extractSingleFile(String internalPath, File externalFile) {
        try {
            if (externalFile.exists()) {
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

            try (InputStream in = FileUtil.class.getResourceAsStream(internalPath)) {
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

}
