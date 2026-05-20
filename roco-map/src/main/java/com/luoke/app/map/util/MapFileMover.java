package com.luoke.app.map.util;

import com.luoke.app.config.PathConfig;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class MapFileMover {

    /**
     * 相对路径 → 来源 URL/类型标记，用于生成 init 资源清单
     */
    private static final Map<String, String> urlMap = new ConcurrentHashMap<>();

    /**
     * IconDownloader 下载图标后调用此方法记录 URL，
     * 供 writeInitManifest() 写入 init 清单。
     */
    public static void recordIconUrl(String fileName, String sourceUrl) {
        urlMap.put(PathConfig.ICON_DIR + fileName, sourceUrl);
    }

    public static void moveAllResources() {
        // 移动图标文件
        // 图标是基础资源，应该先移动
        moveIcons();

        // 移动点位配置文件
        // 点位配置引用图标，需要图标先就绪
        movePoints();

        // 全部移动完成后写 init 资源清单
        writeInitManifest();
    }

    public static void moveIcons() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR);

        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(PathConfig.ICON_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    public static void movePoints() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR);

        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(PathConfig.RESOURCE_ICON_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    public static void moveMapsToResource() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR);

        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(PathConfig.MAP_RESOURCE_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    /**
     * 将当前已移动的资源写入 init 清单文件。
     * 扫描 resources/source/ 下各目录，结合 urlMap 中记录的图标 URL，
     * 生成 <classpath路径> | <URL/类型标记> 格式的清单。
     */
    public static void writeInitManifest() {
        // 收集 map 文件（类型标记：MAP）
        File mapDir = ResourceUtils.getExternalFile(PathConfig.MAP_RESOURCE_DIR);
        File[] mapFiles = mapDir.listFiles();
        if (mapFiles != null) {
            for (File f : mapFiles) {
                if (f.isFile()) {
                    urlMap.putIfAbsent(PathConfig.MAP_RESOURCE_DIR + f.getName(), "MAP");
                }
            }
        }
        // 收集 point 文件（类型标记：CONFIG）
        File pointDir = ResourceUtils.getExternalFile(PathConfig.RESOURCE_ICON_DIR);
        File[] ptFiles = pointDir.listFiles();
        if (ptFiles != null) {
            for (File f : ptFiles) {
                if (f.isFile()) {
                    urlMap.putIfAbsent(PathConfig.RESOURCE_ICON_DIR + f.getName(), "CONFIG");
                }
            }
        }

        try {
            File initFile = ResourceUtils.getExternalFile(PathConfig.SOURCE_INIT);
            initFile.getParentFile().mkdirs();
            try (PrintWriter w = new PrintWriter(initFile, StandardCharsets.UTF_8)) {
                w.println("# RocoMapTracker Resource Manifest");
                for (Map.Entry<String, String> entry : urlMap.entrySet()) {
                    w.println(entry.getKey() + " | " + entry.getValue());
                }
            }
            log.info("资源清单已写入: {} ({} 条目)", initFile.getAbsolutePath(), urlMap.size());
        } catch (Exception e) {
            log.error("写资源清单失败", e);
        }
    }

    // ====================== 通用移动 ======================

    private static void move(File srcDir, File dstDir) {
        // 检查源目录是否存在
        // 同时检查是否为空，避免NPE
        if (!srcDir.exists() || srcDir.listFiles() == null) return;

        // 遍历源目录所有文件
        for (File f : Optional.ofNullable(srcDir.listFiles()).orElse(new File[0])) {
            try {
                // 构建目标文件路径
                // 保持原文件名不变
                File to = new File(dstDir, f.getName());

                // 移动文件，REPLACE_EXISTING覆盖已存在文件
                // 使用Files.move进行原子性移动
                // 这种方法比复制-删除更高效
                Files.move(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                // 记录移动成功的日志
                log.info("✅ 移动：{} → {}", f.getName(), dstDir);
            } catch (Exception e) {
                // 捕获异常，记录错误日志
                // 单个文件失败不影响其他文件
                log.error("❌ 移动失败：{}", f.getName(), e);
            }
        }
    }
}
