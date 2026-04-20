package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class FileUtil {

    /**
     * 获取程序的物理运行根目录
     * - 开发环境: 返回项目根目录 (pom.xml 所在处)
     * - JAR/Native: 返回可执行文件所在的磁盘目录
     */
    public static Path getAppRootDir() {
        // 1. 判定是否为 GraalVM Native Image
        String nativeImagePath = System.getProperty("org.graalvm.nativeimage.imagepath");
        if (nativeImagePath != null && !nativeImagePath.isEmpty()) {
            return Paths.get(nativeImagePath).getParent().toAbsolutePath();
        }

        // 2. 判定是否为 JAR 运行环境
        try {
            URI uri = FileUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String path = uri.getPath();

            if (path != null && path.toLowerCase().endsWith(".jar")) {
                return new File(uri).getParentFile().toPath().toAbsolutePath();
            }
        } catch (Exception e) {
            log.warn("无法通过 CodeSource 识别环境路径: {}", e.getMessage());
        }

        // 3. 保底方案：开发环境 (IDE)
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    /**
     * 工具方法：判断当前是否是 Native 模式
     */
    public static boolean isNative() {
        return System.getProperty("org.graalvm.nativeimage.kind") != null;
    }

    /**
     * 获取相对于根目录的物理路径，并确保父目录存在
     * 【修复】自动去掉开头的 / \，防止路径被替换
     */
    public static File getRelativeFile(String... subPaths) {
        Path path = getAppRootDir();

        for (String sub : subPaths) {
            if (sub == null || sub.isBlank()) continue;

            // 核心修复：强制清除开头的斜杠，永远作为相对路径拼接
            String safePath = sub.replaceFirst("^[/\\\\]+", "");
            path = path.resolve(safePath);
        }

        File file = path.toFile();
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }
}