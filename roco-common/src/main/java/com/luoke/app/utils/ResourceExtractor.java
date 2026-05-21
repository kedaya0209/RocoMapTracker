package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Scanner;

/**
 * JAR 内嵌资源释放工具 — 根据 extract-list.txt 将内置资源提取到外部文件系统。
 */
@Slf4j
@ThreadSafe
public final class ResourceExtractor {

    private static final String EXTRACT_LIST = "/extract-list.txt";

    private ResourceExtractor() {
    }

    /**
     * 解析 extract-list.txt 并将所有内嵌资源释放到外部路径。
     */
    public static void extractAll() {
        try (InputStream in = ResourceExtractor.class.getResourceAsStream(EXTRACT_LIST)) {
            if (in == null) {
                log.info("未找到【{}】文件,不释放资源", EXTRACT_LIST);
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
                    File destFile = FilePathUtil.getRelativeFile(formatDestPath);
                    if ("native".equalsIgnoreCase(operator)) {
                        if (EnvironmentUtil.isNative()) {
                            extractSingleFile(sourcePath, destFile);
                        }
                        return;
                    }
                    extractSingleFile(sourcePath, destFile);
                });
            }
        } catch (IOException e) {
            log.error("释放资源失败", e);
        }
    }

    /**
     * 检查外部文件是否存在，可选从内置资源提取。
     */
    static void ensureExternalFile(String internalPath, File externalFile, boolean isExtract) {
        if (!externalFile.exists() && isExtract) {
            log.info("外部路径不存在，尝试从内置资源释放：{}", internalPath);
            extractSingleFile(internalPath, externalFile);
        }
        if (!externalFile.exists()) {
            log.error("无法获取有效的物理资源路径：{} → {}", internalPath, externalFile.getAbsolutePath());
        }
    }

    /**
     * 将单个内置资源提取到外部路径。已存在时比较 MD5，不匹配则覆盖。
     */
    private static void extractSingleFile(String internalPath, File externalFile) {
        try {
            if (externalFile.exists()) {
                String internalMD5 = HashUtil.computeResourceMD5(internalPath);
                if (internalMD5 != null) {
                    String externalMD5 = HashUtil.computeFileMD5(externalFile);
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

            try (InputStream in = ResourceExtractor.class.getResourceAsStream(internalPath)) {
                if (in == null) {
                    log.warn("资源不存在：{}", internalPath);
                    return;
                }
                externalFile.getParentFile().mkdirs();
                Files.copy(in, externalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("释放：{} → {}", internalPath, externalFile.getAbsolutePath());
            }
        } catch (IOException e) {
            log.error("释放失败：{}", internalPath, e);
        }
    }
}
