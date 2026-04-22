package com.luoke.app.map.util;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.file.Files;

@Slf4j
public class MapFileMover {

    public static void moveAllResources() {
        moveIcons();
        movePoints();
    }

    public static void moveIcons() {
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR);
        File dst = FileUtil.getRelativeFile(AppConfig.RESOURCE_ICON_PATH).getParentFile();
        if (!dst.exists()) dst.mkdirs();
        move(src, dst);
    }

    public static void movePoints() {
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_POINT_DIR);
        File dst = FileUtil.getRelativeFile(AppConfig.RESOURCE_POINT_PATH).getParentFile();
        if (!dst.exists()) dst.mkdirs();
        move(src, dst);
    }

    public static void moveMapsToResource() {
        File src = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR);
        File dst = FileUtil.getRelativeFile(AppConfig.MAP_RESOURCE_PATH).getParentFile();
        if (!dst.exists()) dst.mkdirs();
        move(src, dst);
    }

    private static void move(File srcDir, File dstDir) {
        if (!srcDir.exists() || srcDir.listFiles() == null) return;
        for (File f : srcDir.listFiles()) {
            try {
                File to = new File(dstDir, f.getName());
                Files.move(f.toPath(), to.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log.info("✅ 移动：{}", f.getName());
            } catch (Exception e) {
                log.error("❌ 移动失败：{}", f.getName());
            }
        }
    }
}