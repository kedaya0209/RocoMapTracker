package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径解析工具 — 应用根目录定位、相对路径拼接、外部文件路径映射。
 *
 * <p>路径层次：{@code <appRoot> / [baseDir] / <safePath>}</p>
 */
@Slf4j
@ThreadSafe
public final class FilePathUtil {

    private FilePathUtil() {
    }

    // ==================== 应用根目录 ====================

    /**
     * 获取应用根目录。
     * <ol>
     *   <li>Native Image 环境：可执行文件所在目录</li>
     *   <li>JAR 环境：JAR 文件所在目录</li>
     *   <li>IDE 开发环境：user.dir（项目根目录）</li>
     * </ol>
     */
    public static Path getAppRootDir() {
        String nativeImagePath = null;
        if (EnvironmentUtil.isNative()) {
            nativeImagePath = org.graalvm.nativeimage.ProcessProperties.getExecutableName();
        }
        if (nativeImagePath != null && !nativeImagePath.isEmpty()) {
            return Paths.get(nativeImagePath).getParent().toAbsolutePath();
        }

        try {
            URI uri = FilePathUtil.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            String path = uri.getPath();
            if (path != null && path.toLowerCase().endsWith(".jar")) {
                return new File(uri).getParentFile().toPath().toAbsolutePath();
            }
        } catch (URISyntaxException e) {
            log.warn("无法通过 CodeSource 识别环境路径: {}", e.getMessage());
        }

        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    // ==================== 相对路径解析 ====================

    /**
     * 获取相对于应用根目录的文件。
     */
    public static File getRelativeFile(String... subPaths) {
        Path path = getAppRootDir();
        for (String sub : subPaths) {
            if (sub == null || sub.isBlank()) continue;
            String safePath = sub.replaceFirst("^[/\\\\]+", "");
            path = path.resolve(safePath);
        }
        File file = path.toFile();
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }
        return file;
    }

    // ==================== 外部文件路径映射 ====================

    /**
     * 将 classpath 资源路径映射为外部物理文件（相对于 app root）。
     * <br>例: {@code getExternalFile("/dll/sift/sift_match.exe") → &lt;appRoot&gt;/dll/sift/sift_match.exe}
     */
    public static File getExternalFile(String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return getRelativeFile(safePath);
    }

    /**
     * 将 classpath 资源路径映射为外部物理文件（相对于 appRoot/baseDir）。
     * <br>例: {@code getExternalFile("resources", "/dll/foo.dll") → &lt;appRoot&gt;/resources/dll/foo.dll}
     */
    public static File getExternalFile(String baseDir, String internalPath) {
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return getRelativeFile(baseDir, safePath);
    }

    /**
     * 获取外部绝对路径，可选自动提取内置资源。
     *
     * @see ResourceExtractor#ensureExternalFile(String, File, boolean)
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        File externalFile = getExternalFile(internalPath);
        ResourceExtractor.ensureExternalFile(internalPath, externalFile, isExtract);
        return externalFile.getAbsolutePath();
    }

    /**
     * 获取外部绝对路径（带 baseDir），可选自动提取内置资源。
     *
     * @see ResourceExtractor#ensureExternalFile(String, File, boolean)
     */
    public static String getExternalPath(String baseDir, String internalPath, boolean isExtract) {
        File externalFile = getExternalFile(baseDir, internalPath);
        ResourceExtractor.ensureExternalFile(internalPath, externalFile, isExtract);
        return externalFile.getAbsolutePath();
    }
}
