package com.luoke.app.map;

import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.map.core.ResourceConfigBuilder;
import com.luoke.app.map.util.MapFileMover;

/**
 * 地图资源更新触发器
 * 统一入口：更新地图、下载图标、生成配置、移动资源
 */
public final class MapResourceUpdater {

    // ==================== 全局常量 ====================
    public static final String DOWNLOAD_DIR = "download";
    public static final String DOWNLOAD_ICON_DIR = "download/icon";
    public static final String DOWNLOAD_POINT_DIR = "download/point";
    public static final String DOWNLOAD_MAP_DIR = "download/maps";
    public static final String CHUNK_DIR = "download/maps/chunks";

    public static final String OUTPUT_FILE = "download/maps/map_%s.png";
    public static final String METADATA_FILE = "download/maps/metadata_%s.csv";
    public static final String FAILED_FILE = "download/maps/failed_%s.csv";

    public static final int CHUNK_SIZE = 100;
    public static final int CONNECT_TIMEOUT = 10000;
    public static final int READ_TIMEOUT = 30000;
    public static final int MAX_RETRY = 3;
    public static final int THREAD_COUNT = 10;

    public static final long TILE_DELAY_MS = 30;
    public static final long ICON_DELAY_MS = 100;
    public static final int DEFAULT_ZOOM = 4;

    // 禁止实例化
    private MapResourceUpdater() {
    }

    /**
     * 触发全量资源更新
     */
    public static void updateAllResources() {
        // 1. 更新地图瓦片 + 拼接大图
        MapDownloader.updateMap();

        // 2. 生成合并后的资源配置文件
        ResourceConfigBuilder.buildAndSaveConfig();

        // 3. 下载所有图标（带限流、去重）
        IconDownloader.downloadIcons();

        // 4. 将下载的资源移动到正式目录（覆盖模式）
        MapFileMover.moveAllResources();
    }

    /**
     * 仅更新地图
     */
    public static void updateMapOnly() {
        MapDownloader.updateMap();
        MapFileMover.moveMapsToResource();
    }

    /**
     * 仅更新图标与配置
     */
    public static void updateIconsAndConfigOnly() {
        ResourceConfigBuilder.buildAndSaveConfig();
        IconDownloader.downloadIcons();
        MapFileMover.moveAllResources();
    }
}