package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class FileUtil {

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
}
