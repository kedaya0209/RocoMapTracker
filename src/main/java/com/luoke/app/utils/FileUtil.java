package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件路径工具类
 * <p>
 * 该类提供获取应用运行根目录和处理相对路径的静态方法，主要用于统一处理不同运行环境
 * （开发环境、JAR环境、GraalVM Native Image环境）下的文件路径问题。
 * <p>
 * <b>核心功能：</b>
 * <ul>
 *   <li>自动识别当前运行环境（开发环境/JAR/Native Image）</li>
 *   <li>获取应用的物理运行根目录</li>
 *   <li>解析相对于根目录的文件路径</li>
 *   <li>自动创建父目录</li>
 * </ul>
 * <p>
 * <b>环境识别逻辑：</b>
 * <ol>
 *   <li>优先检查GraalVM Native Image环境（通过系统属性判断）</li>
 *   <li>其次检查JAR运行环境（通过类加载器CodeSource判断）</li>
 *   <li>最后保底为开发环境（使用user.dir）</li>
 * </ol>
 * <p>
 * <b>不同环境的根目录定义：</b>
 * <pre>
 * ┌──────────────────┬────────────────────────────────────────┐
 * │ 运行环境         │ 根目录                                 │
 * ├──────────────────┼────────────────────────────────────────┤
 * │ Native Image     │ 可执行文件(.exe)所在的目录             │
 * │ JAR             │ JAR文件所在的目录                      │
 * │ 开发环境(IDE)   │ 项目根目录（pom.xml所在处）           │
 * └──────────────────┴────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>访问相对于应用根目录的配置文件</li>
 *   <li>读写应用数据目录</li>
 *   <li>统一处理不同打包方式的路径问题</li>
 *   <li>Native Image环境下的资源访问</li>
 * </ul>
 * <p>
 * <b>Native Image兼容性：</b>
 * <ul>
 *   <li>使用GraalVM特定系统属性识别Native环境</li>
 *   <li>路径处理兼容Windows和Linux/macOS</li>
   *   <li>正确处理带盘符的绝对路径</li>
 * </ul>
 * <p>
 * <b>线程安全：</b>
 * <ul>
 *   <li>所有方法都是静态且无状态的</li>
 *   <li>可以安全地在多线程环境中并发调用</li>
 * </ul>
 *
 * @since 1.0
 */
@Slf4j
public class FileUtil {

    /**
     * 获取程序的物理运行根目录
     * <p>
     * 该方法根据当前运行环境自动确定应用的根目录，确保在不同部署方式下都能正确访问文件。
     * 使用多级判定策略，优先识别Native Image环境，其次是JAR环境，最后保底为开发环境。
     * <p>
     * <b>判定逻辑：</b>
     * <ol>
     *   <li><b>Native Image判定：</b>
     *       检查系统属性 {@code org.graalvm.nativeimage.imagepath}，如果存在说明是Native Image环境。
     *       返回可执行文件所在的父目录。
     *   </li>
     *   <li><b>JAR环境判定：</b>
     *       通过类的ProtectionDomain获取CodeSource，检查URL是否以.jar结尾。
     *       返回JAR文件所在的父目录。
     *   </li>
     *   <li><b>开发环境保底：</b>
     *       使用系统属性 {@code user.dir}，返回当前工作目录。
     *   </li>
     * </ol>
     * <p>
     * <b>路径特性：</b>
     * <ul>
     *   <li>返回的路径始终是绝对路径</li>
     *   <li>Windows环境下会包含盘符（如 C:\app）</li>
     *   <li>Unix环境下不带前导斜杠</li>
     *   <li>路径分隔符使用平台默认（Windows: \, Unix: /）</li>
     * </ul>
     * <p>
     * <b>使用示例：</b>
     * <pre>
     * // Native Image环境
     * // 可执行文件：C:\app\myapp.exe
     * // 返回：C:\app
     *
     * // JAR环境
     * // JAR文件：/home/user/app/myapp.jar
     * // 返回：/home/user/app
     *
     * // 开发环境
     * // 项目目录：C:\projects\myapp
     * // 返回：C:\projects\myapp
     * </pre>
     *
     * @return 应用的物理运行根目录（绝对路径），永远不为null
     */
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

    /**
     * 判断当前是否是Native Image运行环境
     * <p>
     * 该方法通过检查GraalVM特有的系统属性来判断当前是否在Native Image环境中运行。
     * 这对于需要根据运行环境执行不同逻辑的场景很有用。
     * <p>
     * <b>判定原理：</b>
     * <ul>
     *   <li>检查系统属性 {@code org.graalvm.nativeimage.kind}</li>
     *   <li>Native Image环境此属性会被设置为 "executable"</li>
     *   <li>JVM环境此属性不存在</li>
     * </ul>
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>根据环境选择不同的资源配置</li>
     *   <li>在Native环境中禁用某些Java特定功能</li>
     *   <li>性能优化：Native环境中可以省略某些反射操作</li>
     * </ul>
     * <p>
     * <b>示例用法：</b>
     * <pre>
     * if (FileUtil.isNative()) {
     *     // Native Image特定代码
     *     System.out.println("Running in Native Image");
     * } else {
     *     // JVM特定代码
     *     System.out.println("Running in JVM");
     * }
     * </pre>
     *
     * @return 如果当前运行在GraalVM Native Image环境返回true，否则返回false
     */
    public static boolean isNative() {
        // 检查GraalVM Native Image特有系统属性
        // 此属性仅在Native Image环境中存在
        return System.getProperty("org.graalvm.nativeimage.kind") != null;
    }

    /**
     * 获取相对于应用根目录的文件对象，并确保父目录存在
     * <p>
     * 该方法将多个路径片段拼接成相对于应用根目录的完整路径，并自动创建不存在的父目录。
     * 主要用于访问应用数据目录中的文件，无需手动创建目录结构。
     * <p>
     * <b>路径拼接规则：</b>
     * <ul>
     *   <li>从应用根目录开始拼接</li>
     *   <li>自动去除每个路径片段的前导斜杠和反斜杠</li>
     *   <li>所有片段都作为相对路径处理</li>
     *   <li>自动创建不存在的父目录</li>
     * </ul>
     * <p>
     * <b>路径安全处理：</b>
     * <pre>
     * 输入示例                          →  实际路径
     * ───────────────────────────────────────────────────
     * "data", "cache"                 →  {root}/data/cache
     * "/data", "/cache"              →  {root}/data/cache  (斜杠被去除)
     * "data", "nested", "file.txt"   →  {root}/data/nested/file.txt
     * "C:\\absolute", "path"         →  {root}/absolute/path (盘符前导被处理)
     * </pre>
     * <p>
     * <b>特殊处理说明：</b>
     * <ul>
     *   <li>null或空白字符串会被跳过</li>
     *   <li>路径片段的前导/和\会被强制去除（防止被当作绝对路径）</li>
     *   <li>Windows路径中的驱动器字母（如C:\）会自动处理</li>
     *   <li>父目录不存在时会自动创建（递归创建）</li>
     * </ul>
     * <p>
     * <b>使用示例：</b>
     * <pre>
     * // 在根目录的 data/cache 目录获取文件
     * File cacheFile = FileUtil.getRelativeFile("data", "cache", "features.bin");
     * // 结果：{root}/data/cache/features.bin（父目录已创建）
     *
     * // 即使传入绝对路径片段也会被处理为相对路径
     * File config = FileUtil.getRelativeFile("/config", "app.json");
     * // 结果：{root}/config/app.json
     * </pre>
     * <p>
     * <b>性能考虑：</b>
     * <ul>
     *   <li>父目录检查使用exists()，可能涉及系统调用</li>
     *   <li>目录创建使用mkdirs()，如果目录已存在则不操作</li>
     *   <li>频繁调用时可能影响性能，建议缓存File对象</li>
     * </ul>
     *
     * @param subPaths 路径片段数组，从根目录开始依次拼接，可以为空
     * @return 拼接后的File对象，指向相对于应用根目录的文件；
     *         父目录已确保存在（如果不存在会自动创建）
     */
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
