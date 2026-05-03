package com.luoke.app.map.util;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;

@Slf4j
public class MapFileMover {

    public static void moveAllResources() {
        // 移动图标文件
        // 图标是基础资源，应该先移动
        moveIcons();

        // 移动点位配置文件
        // 点位配置引用图标，需要图标先就绪
        movePoints();
    }

    public static void moveIcons() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR);

        // 目标：从 AppConfig 读取图标目录
        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(AppConfig.ICON_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    public static void movePoints() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR);

        // 目标：从 AppConfig 读取点位目录
        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(AppConfig.RESOURCE_ICON_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    public static void moveMapsToResource() {
        // 下载来源：相对路径，临时存储
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR);

        // 目标：从 AppConfig 读取地图资源路径
        // 使用绝对路径，应用运行时使用
        File dst = ResourceUtils.getExternalFile(AppConfig.MAP_RESOURCE_DIR);

        // 如果目标目录不存在，自动创建
        if (!dst.exists()) dst.mkdirs();

        // 执行文件移动
        move(src, dst);
    }

    // ====================== 通用移动 ======================

    private static void move(File srcDir, File dstDir) {
        // 检查源目录是否存在
    // 同时检查是否为空，避免NPE
        if (!srcDir.exists() || srcDir.listFiles() == null) return;

        // 遍历源目录所有文件
        for (File f : srcDir.listFiles()) {
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
