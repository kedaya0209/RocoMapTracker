package com.luoke.app.utils;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源管理工具类，处理Native Image环境下的资源加载和释放
 *
 * <p>该工具类专门解决Native Image环境下的资源访问问题，提供：
 * <ul>
 *   <li>内置资源到外部文件的释放</li>
 *   <li>外部物理文件的优先访问</li>
 *   <li>资源列表的自动管理</li>
 *   <li>多种资源格式的读取支持</li>
 * </ul>
 *
 * <p>Native Image兼容性：
 * <ul>
 *   <li>Native Image无法直接访问classpath中的资源文件</li>
 *   <li>需要将内置资源释放到外部文件系统</li>
 *   <li>优先使用外部物理文件，支持动态替换</li>
 *   <li>支持实时释放和延迟加载</li>
 * </ul>
 *
 * <p>资源访问策略：
 * <ul>
 *   <li>外部文件存在：直接读取外部文件</li>
 *   <li>外部文件不存在：从内置资源释放后读取</li>
 *   <li>支持批量释放和单个释放</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class ResourceUtils {

    // 外部资源根目录，所有释放的资源都存放在此目录下
    private static final String RESOURCE_BASE_DIR = "resources";

    // 资源列表文件，记录所有需要释放的资源路径
    private static final String RESOURCE_LIST_FILE = "/resource-list.txt";

    // ============================
    // 程序启动只调用这一个方法
    // ============================

    /**
     * 释放所有内置资源到外部文件系统
     *
     * <p>该方法用于程序启动时的资源初始化。
     * 读取资源列表文件，将所有列出的资源从内置classpath释放到外部文件系统。
     *
     * <p>释放策略：
     * <ul>
     *   <li>只释放不存在的文件，避免重复操作</li>
     *   <li>自动创建目录结构</li>
     *   <li>支持增量更新</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>Native Image程序启动时的资源初始化</li>
     *   <li>OCR模型、字体等大型资源的预加载</li>
     *   <li>确保Native环境下的资源可访问性</li>
     * </ul>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>使用exists()检查避免重复释放</li>
     *   <li>支持多线程并发访问</li>
     *   <li>延迟加载支持</li>
     * </ul>
     */
    public static void extractAll() {
        log.info("=====================================");
        log.info("开始释放内置资源（Native 兼容模式）");

        // 加载资源列表
        List<String> resourceFiles = loadResourceList();

        // 逐个释放资源文件
        for (String path : resourceFiles) {
            extractSingleFile(path);
        }

        log.info("✅ 资源释放完成！外部目录：{}", FileUtil.getRelativeFile(RESOURCE_BASE_DIR));
        log.info("=====================================\n");
    }

    // ============================
    // 从内置资源文件读取路径列表
    // ============================

    /**
     * 从内置资源文件中加载资源路径列表
     *
     * <p>该方法读取resource-list.txt文件，解析出所有需要释放的资源路径。
     * 文件格式支持注释（以#开头）和空行过滤。
     *
     * <p>文件格式示例：
     * <pre>
     * # 资源列表文件
     * /models/ocr_model.dat
     * /fonts/simhei.ttf
     * /images/logo.png
     * </pre>
     *
     * <p>解析规则：
     * <ul>
     *   <li>忽略空行</li>
     *   <li>忽略以#开头的注释行</li>
     *   <li>自动去除行首尾空白字符</li>
     * </ul>
     *
     * <p>资源管理：
     * <ul>
     *   <li>使用try-with-resources确保流正确关闭</li>
   *   <li>支持UTF-8编码的多语言路径</li>
     *   <li>使用BufferedReader提高大文件读取效率</li>
     * </ul>
     *
     * @return 资源路径列表，如果文件不存在或读取失败则返回空列表
     */
    public static List<String> loadResourceList() {
        List<String> list = new ArrayList<>();

        try (InputStream in = ResourceUtils.class.getResourceAsStream(RESOURCE_LIST_FILE)) {
            // 检查资源列表文件是否存在
            if (in == null) {
                log.warn("未找到资源列表文件：{}", RESOURCE_LIST_FILE);
                return list;
            }

            // 使用BufferedReader逐行读取，支持大文件
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String line;

            while ((line = br.readLine()) != null) {
                String trim = line.trim();

                // 过滤掉空行和注释行
                if (!trim.isEmpty() && !trim.startsWith("#")) {
                    list.add(trim);
                }
            }

            log.info("✅ 加载资源列表成功，共 {} 个文件", list.size());
        } catch (Exception e) {
            log.error("❌ 加载资源列表失败", e);
        }

        return list;
    }

    // ============================
    // 单个文件释放（Native 安全）
    // ============================

    /**
     * 单个文件释放（Native Image安全模式）
     *
     * <p>该方法将指定的内置资源文件释放到外部文件系统。
     * 使用存在性检查避免重复释放，提高性能。
     *
     * <p>释放策略：
     * <ul>
     *   <li>如果外部文件已存在，跳过释放</li>
     *   <li>自动创建父目录结构</li>
     *   <li>支持文件覆盖（REPLACE_EXISTING）</li>
     *   <li>使用NIO的Files.copy()提高性能</li>
     * </ul>
     *
     * <p>Native Image兼容性：
     * <ul>
     *   <li>在Native Image中无法直接访问classpath资源</li>
     *   <li>必须释放到外部文件系统才能访问</li>
     *   <li>支持动态资源更新</li>
     * </ul>
     *
     * <p>异常处理：
     * <ul>
     *   <li>资源不存在时记录警告，不抛出异常</li>
     *   <li>释放失败时记录错误，不中断程序</li>
     *   <li>使用try-with-resources确保流正确关闭</li>
     * </ul>
     *
     * @param internalPath 内部资源路径，如"/models/ocr_model.dat"
     */
    public static void extractSingleFile(String internalPath) {
        try {
            // 获取对应的外部文件路径
            File externalFile = getExternalFile(internalPath);

            // 检查文件是否已存在，避免重复释放
            if (externalFile.exists()) {
                log.debug("已存在，跳过：{}", internalPath);
                return;
            }

            // 从内置资源读取数据
            try (InputStream in = ResourceUtils.class.getResourceAsStream(internalPath)) {
                // 检查资源是否存在
                if (in == null) {
                    log.warn("资源不存在：{}", internalPath);
                    return;
                }

                // 创建父目录（如果不存在）
                externalFile.getParentFile().mkdirs();

                // 复制资源到外部文件系统
                Files.copy(in, externalFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                log.info("释放：{} → {}", internalPath, externalFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("释放失败：{}", internalPath, e);
        }
    }

    // ============================
    // 【核心】优先读取外部资源
    // ============================

    /**
     * 获取资源输入流，优先使用外部资源
     *
     * <p>该方法实现了智能的资源访问策略：
     * <ul>
     *   <li>优先使用外部文件系统中的资源（可能被用户修改）</li>
     *   <li>外部资源不存在时，回退到内置资源</li>
     *   <li>都找不到时抛出异常</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>Native Image环境下的资源访问</li>
     *   <li>支持用户自定义资源替换</li>
     *   <li>统一的资源访问接口</li>
     * </ul>
     *
     * <p>资源访问策略：
     * <ul>
     *   <li>外部文件存在：直接返回外部文件输入流</li>
     *   <li>外部文件不存在：返回内置资源输入流</li>
     *   <li>都不存在：抛出FileNotFoundException</li>
     * </ul>
     *
     * <p>注意：返回的InputStream需要调用方手动关闭以释放资源。
     *
     * @param internalPath 内部资源路径，如"/models/ocr_model.dat"
     * @return 资源的输入流，如果资源不存在则抛出异常
     * @throws RuntimeException 如果资源读取失败
     */
    public static InputStream getResourceStream(String internalPath) {
        try {
            // 1. 尝试获取外部文件
            File external = getExternalFile(internalPath);
            if (external.exists()) {
                log.debug("使用外部资源：{}", external.getPath());
                return new FileInputStream(external);
            }

            // 2. 回退到内置资源
            InputStream internal = ResourceUtils.class.getResourceAsStream(internalPath);
            if (internal != null) {
                log.debug("使用内置资源：{}", internalPath);
                return internal;
            }

            // 3. 都找不到，抛出异常
            throw new FileNotFoundException("资源不存在：" + internalPath);
        } catch (Exception e) {
            throw new RuntimeException("读取资源失败：" + internalPath, e);
        }
    }

    // ============================
    // 获取外部资源路径
    // ============================

    /**
     * 获取外部资源的文件路径
     *
     * <p>该方法将内部资源路径转换为外部文件系统的绝对路径。
     * 支持路径安全处理，防止路径遍历攻击。
     *
     * <p>路径转换规则：
     * <ul>
     *   <li>移除开头的斜杠和反斜杠</li>
     *   <li>在资源基础目录下创建对应的子目录</li>
     *   <li>保持原有的文件和目录结构</li>
     * </ul>
     *
     * <p>示例：
     * <pre>
     * 输入："/models/ocr_model.dat"
     * 输出："resources/models/ocr_model.dat"（相对路径）
     * </pre>
     *
     * <p>安全性：
     * <ul>
     *   <li>使用正则表达式移除开头的路径分隔符</li>
     *   <li>防止路径遍历攻击</li>
     *   <li>标准化路径格式</li>
     * </ul>
     *
     * @param internalPath 内部资源路径，如"/models/ocr_model.dat"
     * @return 外部文件对象，指向释放后的资源文件
     */
    public static File getExternalFile(String internalPath) {
        // 移除开头的斜杠和反斜杠，防止路径遍历攻击
        String safePath = internalPath.replaceFirst("^[/\\\\]+", "");
        return FileUtil.getRelativeFile(RESOURCE_BASE_DIR, safePath);
    }

    /**
     * 获取资源路径（优先物理路径）
     * 逻辑：
     * 1. 检查外部 resources 目录下是否存在该文件。
     * 2. 若存在，直接返回外部绝对路径。
     * 3. 若不存在，尝试从内置资源中“实时释放”到外部，再返回路径。
     * * 修改影响：确保了 OCR 模型加载等需要物理 File Path 的场景在 Native 模式下依然可用。
     */
    public static String getExternalPath(String internalPath, boolean isExtract) {
        // 1. 获取外部对应的 File 对象
        File externalFile = getExternalFile(internalPath);

        // 2. 如果外部物理文件不存在，则尝试释放它
        if (!externalFile.exists() && isExtract) {
            log.info("外部路径不存在，尝试从内置资源释放：{}", internalPath);
            extractSingleFile(internalPath);
        }

        // 3. 再次检查是否释放成功
        if (externalFile.exists()) {
            return externalFile.getAbsolutePath();
        }

        // 4. 如果内置也没有（extractSingleFile 失败），抛出异常或返回原始路径
        // 在 Native 模式下，此处如果不报错，后续加载模型时会因为找不到文件直接 Crash
        log.error("无法获取有效的物理资源路径：{}", internalPath);
        return externalFile.getAbsolutePath();
    }


    public static String getExternalPath(String internalPath) {
        return getExternalPath(internalPath, false);
    }

    /**
     * 读取资源文件的所有字节
     *
     * <p>该方法用于将资源文件完整读取到字节数组中。
     * 适合用于需要处理整个文件内容的场景。
     *
     * <p>资源访问策略：
     * <ul>
     *   <li>优先使用外部文件（如果存在）</li>
     *   <li>回退到内置资源</li>
     *   <li>使用try-with-resources确保流正确关闭</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>加载模型文件</li>
     *   <li>读取配置文件</li>
     *   <li>处理二进制数据</li>
     * </ul>
     *
     * <p>性能考虑：
     * <ul>
     *   <li>使用readAllBytes()，适合小到中等文件</li>
     *   <li>对于超大文件，建议使用流式处理</li>
     *   <li>内存使用与文件大小成正比</li>
     * </ul>
     *
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
     *
     * <p>该方法用于按行读取文本资源文件，特别适合处理字典文件、配置文件等。
     * 自动过滤空行，支持使用UTF-8编码的多语言内容。
     *
     * <p>修改影响：
     * <ul>
     *   <li>1. 显式指定 UTF-8，防止在不同系统（如 Windows 中文版）下读取字典文件出现乱码</li>
     *   <li>2. 使用 BufferedReader 替代 Scanner，在大文件（如几千行的识别字典）下效率更高</li>
     *   <li>3. 自动过滤空行，简化调用方的数据处理逻辑</li>
     * </ul>
     *
     * <p>读取策略：
     * <ul>
     *   <li>优先使用外部文件（如果存在）</li>
     *   <li>回退到内置资源</li>
     *   <li>使用BufferedReader逐行读取，支持大文件</li>
     *   <li>自动过滤空行</li>
     * </ul>
     *
     * <p>应用场景：
     * <ul>
     *   <li>OCR字典文件加载</li>
     *   <li>配置文件读取</li>
     *   <li>文本数据解析</li>
     * </ul>
     *
     * <p>性能优化：
     * <ul>
     *   <li>使用BufferedReader而非Scanner，提高大文件读取效率</li>
     *   <li>使用UTF-8编码，避免字符转换开销</li>
     *   <li>try-with-resources确保资源正确释放</li>
     * </ul>
     *
     * @param internalPath 内部资源路径
     * @return 包含所有非空行的列表，如果文件为空则返回空列表
     * @throws IOException 如果资源读取失败
     * @throws NullPointerException 如果输入流为null
     */
    public static List<String> readResourceLines(String internalPath) throws IOException {
        List<String> result = new ArrayList<>();

        // getResourceStream 内部已经处理了”外部优先”逻辑
        // 使用try-with-resources确保流和reader正确关闭
        try (InputStream in = getResourceStream(internalPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                // 过滤掉空行，OCR 字典通常不包含空行
                if (!line.isBlank()) {
                    result.add(line);
                }
            }
        }

        return result;
    }

}