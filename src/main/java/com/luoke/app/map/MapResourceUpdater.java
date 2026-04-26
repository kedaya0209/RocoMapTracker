package com.luoke.app.map;

import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.map.core.ResourceConfigBuilder;
import com.luoke.app.map.util.MapFileMover;

/**
 * 地图资源更新触发器
 *
 * @author 可达鸭
 * @since 1.0.0
 */
public final class MapResourceUpdater {

    // ==================== 全局常量配置 ====================

    /** 下载根目录 */
    public static final String DOWNLOAD_DIR = "download";

    /** 图标下载目录 */
    public static final String DOWNLOAD_ICON_DIR = "download/icon";

    /** 点位数据下载目录 */
    public static final String DOWNLOAD_POINT_DIR = "download/point";

    /** 地图瓦片下载目录 */
    public static final String DOWNLOAD_MAP_DIR = "download/maps";

    /** 分块处理目录（用于大数据量场景下的分批处理） */
    public static final String CHUNK_DIR = "download/maps/chunks";

    /** 拼接后的大图输出文件路径模板，使用%s替换为层级标识 */
    public static final String OUTPUT_FILE = "download/maps/map_%s.png";

    /** 元数据文件路径模板，用于记录大图的元信息 */
    public static final String METADATA_FILE = "download/maps/metadata_%s.csv";

    /** 失败记录文件路径模板，用于记录下载失败的瓦片信息 */
    public static final String FAILED_FILE = "download/maps/failed_%s.csv";

    /** 分块处理大小，用于大数据量场景下的内存优化 */
    public static final int CHUNK_SIZE = 100;

    /** 网络连接超时时间（毫秒），防止长时间阻塞 */
    public static final int CONNECT_TIMEOUT = 10000;

    /** 网络读取超时时间（毫秒），防止长时间阻塞 */
    public static final int READ = 30000;

    /** 最大重试次数，用于网络请求失败时的自动重试 */
    public static final int MAX_RETRY = 3;

    /** 并发下载线程数，控制网络请求的并发度 */
    public static final int THREAD_COUNT = 10;

    /** 瓦片下载延迟（毫秒），用于控制请求频率，避免被限流 */
    public static final long TILE_DELAY_MS = 30;

    /** 图标下载延迟（毫秒），用于控制请求频率，避免被限流 */
    public static final long ICON_DELAY_MS = 100;

    /** 默认地图缩放级别，用于未指定层级时的下载 */
    public static final int DEFAULT_ZOOM = 4;

    // ==================== 构造方法 ====================

    private MapResourceUpdater() {
    }

    // ==================== 公共API方法 ====================

    public static void updateAllResources() {
        // 1. 更新地图瓦片并拼接成大图
        // 这一步会下载指定层级范围内的所有瓦片，并将它们拼接成完整的大图
        MapDownloader.updateMap();

        // 2. 生成合并后的资源配置文件
        // 这一步会整合地图配置、点位信息、分类信息，生成统一的配置文件
        ResourceConfigBuilder.buildAndSaveConfig();

        // 3. 下载所有图标（带限流、去重）
        // 这一步会根据配置文件中的图标URL列表，批量下载图标资源
        // 使用限流机制避免请求过频，使用去重机制避免重复下载
        IconDownloader.downloadIcons();

        // 4. 将下载的资源移动到正式目录（覆盖模式）
        // 这一步会将download目录下的所有资源移动到正式资源目录
        // 使用覆盖模式确保资源始终是最新版本
        MapFileMover.moveAllResources();
    }

    public static void updateMapOnly() {
        // 下载地图瓦片并拼接成大图
        MapDownloader.updateMap();

        // 将生成的地图文件移动到正式资源目录
        // 仅移动地图相关的文件，不包括图标和配置
        MapFileMover.moveMapsToResource();
    }


    public static void updateIconsAndConfigOnly() {
        // 生成合并后的资源配置文件
        // 这一步会整合点位信息和分类信息，生成新的配置文件
        ResourceConfigBuilder.buildAndSaveConfig();

        // 下载所有图标（带限流和去重）
        // 根据新的配置文件中的图标URL列表，下载所需的图标资源
        IconDownloader.downloadIcons();

        // 将下载的资源移动到正式资源目录（覆盖模式）
        // 移动配置文件和图标资源
        MapFileMover.moveAllResources();
    }
}
