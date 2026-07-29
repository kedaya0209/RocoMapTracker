package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 下载与远程资源配置持久化 
 */
@NotThreadSafe
public final class DownloadConfig {

    // ============================================================
    // 远程资源与下载
    // ============================================================
    /**
     * 使用内置资源（否则从远程下载）
     */
    public static boolean INTERNAL_RESOURCE = false;
    /**
     * 远程瓦片 URL 列表
     */
    public static String[] MAP_REMOTE_URLS = new String[0];
    /**
     * 远程瓦片 URL 名称列表
     */
    public static String[] MAP_REMOTE_URL_NAME = new String[0];
    /**
     * 远程瓦片 URL 排序权重
     */
    public static int[] MAP_REMOTE_URL_SORT = new int[0];
    /**
     * 远程瓦片 URL 对应的显示名称（中文名，如"卡洛西亚大陆"）
     */
    public static String[] MAP_REMOTE_URL_DISPLAY_NAME = new String[0];
    /**
     * 远程瓦片 URL 对应的洞穴标记（true=洞穴，false=大陆）
     */
    public static boolean[] MAP_REMOTE_URL_IS_CAVE = new boolean[0];
    /**
     * 远程瓦片 URL 对应的层数（0=大陆/地表，1=一层，2=二层...）
     */
    public static int[] MAP_REMOTE_URL_LAYER = new int[0];
    /**
     * 地图资源信息页 URL
     */
    public static String MAP_RESOURCE_INFO_URL = "https://wiki.biligame.com/rocom/大地图";
    /**
     * 地图资源点数据 URL
     */
    public static String MAP_RESOURCE_POINT_URL = "https://wiki.biligame.com/rocom/";

    // --- 下载器参数 ---
    /**
     * HTTP 连接超时（毫秒）
     */
    public static int DOWNLOAD_CONNECT_TIMEOUT = 10000;
    /**
     * HTTP 读取超时（毫秒）
     */
    public static int DOWNLOAD_READ_TIMEOUT = 30000;
    /**
     * 并发下载虚拟线程数
     */
    public static int DOWNLOAD_THREAD_COUNT = 32;
    /**
     * 瓦片下载间隔（毫秒）
     */
    public static long DOWNLOAD_TILE_DELAY_MS = 10;
    /**
     * 图标下载间隔（毫秒）
     */
    public static long DOWNLOAD_ICON_DELAY_MS = 100;
    /**
     * 瓦片分块持久化批次大小
     */
    public static int DOWNLOAD_CHUNK_SIZE = 100;

    private DownloadConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        INTERNAL_RESOURCE = ConfigHelper.getBool(prop, "internal.resource", INTERNAL_RESOURCE);
        DOWNLOAD_CONNECT_TIMEOUT = ConfigHelper.getInt(prop, "download.connect.timeout", DOWNLOAD_CONNECT_TIMEOUT);
        DOWNLOAD_READ_TIMEOUT = ConfigHelper.getInt(prop, "download.read.timeout", DOWNLOAD_READ_TIMEOUT);
        DOWNLOAD_THREAD_COUNT = ConfigHelper.getInt(prop, "download.thread.count", DOWNLOAD_THREAD_COUNT);
        DOWNLOAD_TILE_DELAY_MS = ConfigHelper.getLong(prop, "download.tile.delay.ms", DOWNLOAD_TILE_DELAY_MS);
        DOWNLOAD_ICON_DELAY_MS = ConfigHelper.getLong(prop, "download.icon.delay.ms", DOWNLOAD_ICON_DELAY_MS);
        MAP_REMOTE_URLS = ConfigHelper.getStrArray(prop, "map.remote.urls");
        MAP_REMOTE_URL_NAME = ConfigHelper.getStrArray(prop, "map.remote.url.name");
        MAP_REMOTE_URL_SORT = ConfigHelper.getIntArray(prop, "map.remote.url.sort");
        MAP_REMOTE_URL_DISPLAY_NAME = ConfigHelper.getStrArray(prop, "map.remote.url.display.name");
        MAP_REMOTE_URL_IS_CAVE = ConfigHelper.getBoolArray(prop, "map.remote.url.is.cave");
        MAP_REMOTE_URL_LAYER = ConfigHelper.getIntArray(prop, "map.remote.url.layer");
    }

    public static void save(StringBuilder sb) {
        sb.append("# 使用内置资源（否则从远程下载）\n");
        sb.append("internal.resource=").append(INTERNAL_RESOURCE).append("\n\n");
        sb.append("# HTTP 连接超时（毫秒）\n");
        sb.append("download.connect.timeout=").append(DOWNLOAD_CONNECT_TIMEOUT).append("\n");
        sb.append("# HTTP 读取超时（毫秒）\n");
        sb.append("download.read.timeout=").append(DOWNLOAD_READ_TIMEOUT).append("\n");
        sb.append("# 下载失败最大重试次数\n");
        sb.append("# 并发下载虚拟线程数\n");
        sb.append("download.thread.count=").append(DOWNLOAD_THREAD_COUNT).append("\n");
        sb.append("# 瓦片下载间隔（毫秒）\n");
        sb.append("download.tile.delay.ms=").append(DOWNLOAD_TILE_DELAY_MS).append("\n");
        sb.append("# 图标下载间隔（毫秒）\n");
        sb.append("download.icon.delay.ms=").append(DOWNLOAD_ICON_DELAY_MS).append("\n\n");
        sb.append("# 远程瓦片 URL 列表\n");
        sb.append("map.remote.urls=").append(String.join(",", MAP_REMOTE_URLS)).append("\n");
        sb.append("# 远程瓦片 URL 名称列表\n");
        sb.append("map.remote.url.name=").append(String.join(",", MAP_REMOTE_URL_NAME)).append("\n");
        String sortArr = Arrays.stream(MAP_REMOTE_URL_SORT).mapToObj(String::valueOf).collect(Collectors.joining(","));
        sb.append("# 远程瓦片 URL 排序权重\n");
        sb.append("map.remote.url.sort=").append(sortArr).append("\n\n");
        sb.append("# 远程瓦片 URL 对应的显示名称（中文名，如\"卡洛西亚大陆\"）\n");
        sb.append("map.remote.url.display.name=").append(String.join(",", MAP_REMOTE_URL_DISPLAY_NAME)).append("\n\n");
        String isCaveArr = "";
        for (int i = 0; i < MAP_REMOTE_URL_IS_CAVE.length; i++) {
            if (i > 0) isCaveArr += ",";
            isCaveArr += MAP_REMOTE_URL_IS_CAVE[i];
        }
        sb.append("# 远程瓦片 URL 对应的洞穴标记（true=洞穴，false=大陆）\n");
        sb.append("map.remote.url.is.cave=").append(isCaveArr).append("\n\n");
        String layerArr = Arrays.stream(MAP_REMOTE_URL_LAYER).mapToObj(String::valueOf).collect(Collectors.joining(","));
        sb.append("# 远程瓦片 URL 对应的层数（0=大陆/地表，1=一层，2=二层...）\n");
        sb.append("map.remote.url.layer=").append(layerArr).append("\n\n");
    }
}
