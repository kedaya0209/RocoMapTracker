package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;

@Slf4j
public class AppEnvUtil {

    /**
     * 获取程序的物理根目录 (EXE/JAR/IDE 所在位置)
     */
    public static Path getRootDir() {
        // 1. 判定是否为 GraalVM Native Image 环境
        // 这是 Native 模式下获取路径最稳健的方式
        String imagePath = System.getProperty("org.graalvm.nativeimage.imagepath");
        if (imagePath != null && !imagePath.isEmpty()) {
            log.debug("检测到 Native Image 环境");
            return Paths.get(imagePath).getParent().toAbsolutePath();
        }

        // 2. 判定是否为 JAR 运行环境
        try {
            URL location = AppEnvUtil.class.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                String protocol = location.getProtocol();
                String path = location.getPath();

                // 如果协议是 jar 或者路径以 .jar 结尾
                if ("jar".equals(protocol) || (path != null && path.toLowerCase().endsWith(".jar"))) {
                    log.debug("检测到 JAR 运行环境");
                    // 注意：这里用 new File().toPath() 自动处理 URL 编码转义问题
                    return new File(location.toURI()).getParentFile().toPath().toAbsolutePath();
                }
            }
        } catch (Exception e) {
            log.warn("尝试识别 JAR 路径失败: {}", e.getMessage());
        }

        // 3. 开发环境 (IDE) 保底方案
        log.debug("检测到开发环境 (IDE)");
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    /**
     * 判断当前是否为 Native 模式 (用于某些特殊逻辑分支)
     */
    public static boolean isNative() {
        return System.getProperty("org.graalvm.nativeimage.kind") != null;
    }
}