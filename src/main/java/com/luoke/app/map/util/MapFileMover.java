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

    // ====================== 下载完统一移动 ======================
    public static void moveAllResources() {
        moveIcons();
        movePoints();
    }

    // ====================== 移动图标 → 外部资源目录 ======================
    public static void moveIcons() {
        // 下载来源
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR);
        // 目标：从 AppConfig 读取图标目录
        File dst = ResourceUtils.getExternalFile(AppConfig.ICON_DIR);
        if (!dst.exists()) dst.mkdirs();
        move(src, dst);
    }

    // ====================== 移动点位json → 外部资源目录 ======================
    public static void movePoints() {
        // 下载来源
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR);
        // 目标：从 AppConfig 读取点位目录
        File dst = ResourceUtils.getExternalFile(AppConfig.RESOURCE_ICON_DIR);
        if (!dst.exists()) dst.mkdirs();
        move(src, dst);
    }

    // ====================== 移动地图 → 外部资源目录 ======================
    public static void moveMapsToResource() {
        // 下载来源
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR);
        // 目标：从 AppConfig 读取地图资源路径
        File dst = ResourceUtils.getExternalFile(AppConfig.MAP_RESOURCE_DIR);
        if (!dst.exists()) dst.mkdirs();
        move(src, dst);
    }

    // ====================== 通用移动 ======================
    private static void move(File srcDir, File dstDir) {
        if (!srcDir.exists() || srcDir.listFiles() == null) return;
        for (File f : srcDir.listFiles()) {
            try {
                File to = new File(dstDir, f.getName());
                Files.move(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("✅ 移动：{} → {}", f.getName(), dstDir);
            } catch (Exception e) {
                log.error("❌ 移动失败：{}", f.getName(), e);
            }
        }
    }
}