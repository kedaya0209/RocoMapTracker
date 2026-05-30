package com.luoke.app.map;

import com.luoke.app.config.DownloadConfig;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.loader.LoadInfo;
import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.map.core.ResourceConfigBuilder;
import com.luoke.app.map.util.MapFileMover;
import lombok.extern.slf4j.Slf4j;

/**
 * 地图资源更新触发器
 *
 * @author 可达鸭
 * @since 1.0.0
 */
@Slf4j
@NotThreadSafe
public final class MapResourceUpdater {

    // ========== 常量配置 ==========

    /**
     * 下载根目录
     */
    public static final String DOWNLOAD_DIR = "download";

    /**
     * 图标下载目录
     */
    public static final String DOWNLOAD_ICON_DIR = "download/icon";

    /**
     * 点位数据下载目录
     */
    public static final String DOWNLOAD_POINT_DIR = "download/point";

    /**
     * 地图瓦片下载目录
     */
    public static final String DOWNLOAD_MAP_DIR = "download/maps";

    /**
     * 分块处理目录
     */
    public static final String CHUNK_DIR = "download/maps/chunks";

    /**
     * 大图输出文件路径模板
     */
    public static final String OUTPUT_FILE = "download/maps/map_%s.png";

    /**
     * 元数据文件路径模板
     */
    public static final String METADATA_FILE = "download/maps/metadata_%s.csv";

    /**
     * 分块处理大小
     */
    public static int CHUNK_SIZE = DownloadConfig.DOWNLOAD_CHUNK_SIZE;

    /**
     * 网络连接超时时间（毫秒）
     */
    public static int CONNECT_TIMEOUT = DownloadConfig.DOWNLOAD_CONNECT_TIMEOUT;

    /**
     * 网络读取超时时间（毫秒）
     */
    public static int READ = DownloadConfig.DOWNLOAD_READ_TIMEOUT;

    /**
     * 最大重试次数
     */
    public static int MAX_RETRY = DownloadConfig.DOWNLOAD_MAX_RETRY;

    /**
     * 并发下载线程数
     */
    public static int THREAD_COUNT = DownloadConfig.DOWNLOAD_THREAD_COUNT;

    /**
     * 瓦片下载延迟（毫秒）
     */
    public static long TILE_DELAY_MS = DownloadConfig.DOWNLOAD_TILE_DELAY_MS;

    /**
     * 图标下载延迟（毫秒）
     */
    public static long ICON_DELAY_MS = DownloadConfig.DOWNLOAD_ICON_DELAY_MS;

    // ========== 构造方法 ==========

    private MapResourceUpdater() {
    }

    // ========== 公共API方法 ==========

    /**
     * 全量更新：地图瓦片 → 资源点配置 → 图标 → 移动到最终目录。
     * 每次更新开始前清空分类缓存，确保从远程获取最新数据。
     *
     * @return true 全部成功，false 中途失败（地图下载或配置构建出错）
     */
    public static boolean updateAllResources() {
        LoadInfo.invalidateCategoryCache();

        if (!MapDownloader.updateMap()) return false;
        MapFileMover.moveMapsToResource();

        if (!ResourceConfigBuilder.buildAndSaveConfig()) return false;

        IconDownloader.downloadIcons();
        MapFileMover.moveAllResources();
        return true;
    }

    /**
     * 仅更新地图瓦片，用于恢复缺失的地图文件。
     *
     * @return true 成功，false 失败
     */
    public static boolean updateMapOnly() {
        LoadInfo.invalidateCategoryCache();

        if (!MapDownloader.updateMap()) return false;
        MapFileMover.moveMapsToResource();
        return true;
    }

    /**
     * 仅更新配置和图标，用于恢复缺失的配置/图标文件。
     *
     * @return true 成功，false 失败
     */
    public static boolean updateIconsAndConfigOnly() {
        LoadInfo.invalidateCategoryCache();

        if (!ResourceConfigBuilder.buildAndSaveConfig()) return false;

        IconDownloader.downloadIcons();
        MapFileMover.moveAllResources();
        return true;
    }
}
