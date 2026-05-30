package com.luoke.app.map.loader;

import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.config.DownloadConfig;
import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.map.dto.MapConfig;
import com.luoke.app.map.dto.MapPointItem;
import com.luoke.app.map.loader.MapCategoryLoader;
import com.luoke.app.map.loader.MapConfigLoader;
import com.luoke.app.map.loader.MapPointLoader;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@NotThreadSafe
public class LoadInfo {

    /**
     * 分类数据缓存，避免同一更新流程中重复 HTTP 请求。
     * 每次更新开始前通过 {@link #invalidateCategoryCache()} 清空。
     */
    private static List<MapCategoryItem> categoryCache = null;

    public static void invalidateCategoryCache() {
        categoryCache = null;
    }

    public static void remoteResolveConfig() {
        // 输出开始加载的日志分隔线，便于在控制台中快速定位日志区域
        log.info("=====================================");
        log.info("开始远程加载地图配置");
        log.info("=====================================");

        // 从远程服务器加载地图配置
        // 如果加载失败（网络错误、数据格式错误等），会返回null
        MapConfig cfg = MapConfigLoader.load();
        if (cfg == null) {
            // 配置加载失败，直接返回，不更新任何配置
            return;
        }

        // 更新地图缩放级别配置
        // 使用三元运算符确保配置值大于0时才更新，否则保留原有值
        // 这样可以防止无效配置导致应用异常
        ViewConfig.MAP_MAX_ZOOM = cfg.getMaxZoom() > 0 ? cfg.getMaxZoom() : ViewConfig.MAP_MAX_ZOOM;
        ViewConfig.MAP_MIN_ZOOM = cfg.getMinZoom() > 0 ? cfg.getMinZoom() : ViewConfig.MAP_MIN_ZOOM;

        // 初始化临时列表，用于收集有效的图层信息
        // 使用ArrayList保证添加顺序和迭代顺序一致
        List<Integer> sortList = new ArrayList<>();  // 图层排序索引
        List<String> urlList = new ArrayList<>();     // 瓦片URL列表
        List<String> nameList = new ArrayList<>();    // 图层名称列表

        // 遍历配置中的所有图层，提取有效信息
        cfg.getMapLayers().forEach(layer -> {
            // 提取图层基本信息
            String name = layer.getName();  // 图层名称
            int index = layer.getIndex();   // 图层显示索引（用于排序）
            String url = layer.getLayerOption().tileUrl();  // 瓦片URL模板

            // 数据校验：确保名称和URL都不为空且不全是空白字符
            // 这一步可以过滤掉无效的图层配置，避免后续处理出错
            if (name != null && !name.isBlank() && url != null && !url.isBlank()) {
                // 通过有效的图层信息
                sortList.add(index);
                urlList.add(url);
                nameList.add(name);
            }
            // 无效的图层信息会被自动跳过，不会被添加到列表中
        });

        // 检查是否解析到有效的图层信息
        if (!sortList.isEmpty()) {
            // 将URL模板中的{z}占位符替换为实际的缩放级别
            // 使用Stream API进行批量替换和转换，代码简洁高效
            // 替换后的URL可以直接用于瓦片下载请求
            DownloadConfig.MAP_REMOTE_URLS = urlList.stream()
                    .map(u -> u.replace("{z}", String.valueOf(ViewConfig.MAP_ZOOM)))
                    .toArray(String[]::new);

            // 将排序索引列表转换为int数组，用于图层排序
            // 使用mapToInt避免自动装箱，提高性能
            DownloadConfig.MAP_REMOTE_URL_SORT = sortList.stream().mapToInt(i -> i).toArray();

            // 将图层名称列表转换为String数组
            // 使用toArray方法进行转换，类型安全且高效
            DownloadConfig.MAP_REMOTE_URL_NAME = nameList.toArray(String[]::new);
            ConfigPersistence.save();
            // 记录加载成功的日志，包含解析到的地图数量
            log.info("✅ 远程配置加载完成，地图数量：{}", urlList.size());
        } else {
            // 未解析到任何有效的图层信息，记录警告日志
            // 这种情况可能是配置文件格式错误或配置内容为空
            log.warn("⚠️ 未解析到任何地图图层");
        }
    }

    public static List<MapPointItem> parsePointJson() {
        // 委托给MapPointLoader执行实际的加载和解析操作
        // 使用Loader模式分离数据获取和业务逻辑，提高代码的可维护性
        return MapPointLoader.load();
    }

    public static List<MapCategoryItem> parseCategoryData() {
        if (categoryCache != null) {
            log.debug("使用缓存的分类数据 ({} 条)", categoryCache.size());
            return categoryCache;
        }
        categoryCache = MapCategoryLoader.load();
        log.debug("分类数据已缓存 ({} 条)", categoryCache != null ? categoryCache.size() : 0);
        return categoryCache;
    }

    public static MapConfig getMapConfig() {
        // 委托给MapConfigLoader执行实际的加载操作
        // 使用Loader模式分离数据获取和业务逻辑，提高代码的可维护性
        return MapConfigLoader.load();
    }
}
