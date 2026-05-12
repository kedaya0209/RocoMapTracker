package com.luoke.app.map;

import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.map.core.ResourceConfigBuilder;
import com.luoke.app.map.util.MapFileMover;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * 地图资源更新触发器
 *
 * @author 可达鸭
 * @since 1.0.0
 */
@Slf4j
public final class MapResourceUpdater {

    // ========== 常量配置 ==========

    /** 下载根目录 */
    public static final String DOWNLOAD_DIR = "download";

    /** 图标下载目录 */
    public static final String DOWNLOAD_ICON_DIR = "download/icon";

    /** 点位数据下载目录 */
    public static final String DOWNLOAD_POINT_DIR = "download/point";

    /** 地图瓦片下载目录 */
    public static final String DOWNLOAD_MAP_DIR = "download/maps";

    /**
     * 分块处理目录
     */
    public static final String CHUNK_DIR = "download/maps/chunks";

    /** 大图输出文件路径模板 */
    public static final String OUTPUT_FILE = "download/maps/map_%s.png";

    /** 元数据文件路径模板 */
    public static final String METADATA_FILE = "download/maps/metadata_%s.csv";

    /** 失败记录文件路径模板 */
    public static final String FAILED_FILE = "download/maps/failed_%s.csv";

    /** 分块处理大小 */
    public static final int CHUNK_SIZE = 100;

    /** 网络连接超时时间（毫秒） */
    public static final int CONNECT_TIMEOUT = 10000;

    /** 网络读取超时时间（毫秒） */
    public static final int READ = 30000;

    /** 最大重试次数 */
    public static final int MAX_RETRY = 3;

    /** 并发下载线程数 */
    public static final int THREAD_COUNT = 16;

    /** 瓦片下载延迟（毫秒） */
    public static final long TILE_DELAY_MS = 30;

    /** 图标下载延迟（毫秒） */
    public static final long ICON_DELAY_MS = 100;

    // ========== 构造方法 ==========

    private MapResourceUpdater() {
    }

    // ========== 公共API方法 ==========

    public static void updateAllResources() {
        File downloadDir = ResourceUtils.getExternalFile(DOWNLOAD_DIR);
        if (downloadDir.exists()) {
            try {
                Files.walkFileTree(downloadDir.toPath(), new FileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.deleteIfExists(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        Files.deleteIfExists(dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (Exception e) {
                log.error("删除下载文件夹失败, e:", e);
            }
        }

        MapDownloader.updateMap();
        if (MapDownloader.getIsStopRequested().get()) {
            return;
        }

        ResourceConfigBuilder.buildAndSaveConfig();

        IconDownloader.downloadIcons();
        if (IconDownloader.getIsStopRequested().get()) {
            return;
        }

        MapFileMover.moveAllResources();
    }

    public static void updateMapOnly() {
        MapDownloader.updateMap();
        MapFileMover.moveMapsToResource();
    }

    public static void updateIconsAndConfigOnly() {
        ResourceConfigBuilder.buildAndSaveConfig();
        IconDownloader.downloadIcons();
        MapFileMover.moveAllResources();
    }
}
