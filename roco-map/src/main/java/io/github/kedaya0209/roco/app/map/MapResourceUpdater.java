package io.github.kedaya0209.roco.app.map;

import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.map.core.DownloadProgressContext;
import io.github.kedaya0209.roco.app.map.core.IconDownloader;
import io.github.kedaya0209.roco.app.map.loader.LoadInfo;
import io.github.kedaya0209.roco.app.map.util.MapPostProcessor;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.map.core.MapDownloader;
import io.github.kedaya0209.roco.app.map.core.ResourceConfigBuilder;
import io.github.kedaya0209.roco.app.map.util.MapFileMover;
import ar.com.hjg.pngj.ImageLineByte;
import ar.com.hjg.pngj.PngReader;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
     * 全量更新：地图瓦片 → 亮度提取 → 资源点配置 → 图标 → 移动到最终目录。
     * 每次更新开始前清空分类缓存，确保从远程获取最新数据。
     *
     * @return true 全部成功，false 中途失败（地图下载或配置构建出错）
     */
    public static boolean updateAllResources() {
        LoadInfo.invalidateCategoryCache();

        if (!MapDownloader.updateMap()) {
            System.gc();
            return false;
        }
        processAllDownloadedMaps();
        try {
            MapPostProcessor.processMaps();
        } catch (IOException e) {
            log.error("多地图后处理失败", e);
        }
        MapFileMover.moveMapsToResource();

        if (!ResourceConfigBuilder.buildAndSaveConfig()) {
            System.gc();
            return false;
        }

        IconDownloader.downloadIcons();
        MapFileMover.moveAllResources();

        // 全量下载完成，清理临时文件
        MapFileMover.cleanupTempFiles();

        // 地图瓦片 byte[] + 图块拼接 BufferedImage + 图标下载临时内存全部回收
        System.gc();
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
     * 对下载拼接后的地图执行一致性处理：
     * - 洞穴子图：先 CLAHE 增强，再与大陆图融合（暗化的大陆特征填充透明边缘）
     * - 大陆图：保持不变
     * <p>自动检测洞穴图（含有透明像素的 PNG 判定为洞穴图）。
     * 处理完的图像与内置资源管线（CaveImageEnhancer + CaveImageFuser）一致。
     */
    static void processAllDownloadedMaps() {
        String[] tags = DownloadConfig.MAP_REMOTE_URL_NAME;
        String[] displayNames = DownloadConfig.MAP_REMOTE_URL_DISPLAY_NAME;

        if (tags.length == 0) {
            log.info("未配置 MAP_REMOTE_URL_NAME，跳过地图后处理");
            return;
        }

        // 大陆图 / 洞穴图检测
        // 策略：先按亮度提取阈值(50)模拟提取，透明比例最低的是大陆图（地面图亮区多），
        // 透明比例高的是洞穴图（暗色缝隙多）。
        // 优先使用已有的 IS_CAVE 配置（持久化），否则自动检测。
        File mainlandMapFile = null;
        List<Integer> caveIndices = new java.util.ArrayList<>();
        boolean[] isCave = new boolean[tags.length];

        // 尝试用已有配置识别大陆图
        if (DownloadConfig.MAP_REMOTE_URL_IS_CAVE.length == tags.length) {
            isCave = DownloadConfig.MAP_REMOTE_URL_IS_CAVE.clone();
            for (int i = 0; i < tags.length; i++) {
                String tag = tags[i];
                File mapFile = FilePathUtil.getRelativeFile(DOWNLOAD_MAP_DIR, "map_" + tag + ".png");
                if (!mapFile.exists()) continue;

                if (!isCave[i] && mainlandMapFile == null) {
                    mainlandMapFile = mapFile;
                    log.info("配置指定为大陆图: {}", mapFile.getName());
                } else if (isCave[i]) {
                    caveIndices.add(i);
                }
            }
        }

        // 无已有配置或配置未找到大陆图 → 模拟亮度提取后按透明比例自动检测
        if (mainlandMapFile == null) {
            caveIndices.clear();
            isCave = new boolean[tags.length];

            // 第一遍：计算每张图亮度提取后的估计透明比例
            int[] estimatedTransparent = new int[tags.length];
            int[] totalPixels = new int[tags.length];
            int validCount = 0;

            for (int i = 0; i < tags.length; i++) {
                File mapFile = FilePathUtil.getRelativeFile(
                        DOWNLOAD_MAP_DIR, "map_" + tags[i] + ".png");
                if (!mapFile.exists()) continue;

                int w, h, total, darkCount;
                PngReader reader = null;
                try {
                    reader = new PngReader(mapFile);
                    w = reader.imgInfo.cols;
                    h = reader.imgInfo.rows;
                    int channels = reader.imgInfo.channels;
                    total = w * h;
                    darkCount = 0;

                    for (int y = 0; y < h; y++) {
                        darkCount += countDarkPixels(reader.readRow(), channels, w, 50);
                    }
                } catch (Exception e) {
                    log.warn("无法读取地图文件: {}，跳过", mapFile, e);
                    continue;
                } finally {
                    if (reader != null) reader.end();
                }

                estimatedTransparent[i] = darkCount;
                totalPixels[i] = total;
                validCount++;
                log.info("亮度模拟: {} — 估计透明={} / {} ({}%)",
                        mapFile.getName(), darkCount, total,
                        String.format("%.1f", 100.0 * darkCount / total));
            }

            // 第二遍：透明比例最低的为大陆图，其余为洞穴图
            if (validCount > 0) {
                int mainlandIdxLocal = -1;
                double minRatio = Double.MAX_VALUE;
                for (int i = 0; i < tags.length; i++) {
                    if (totalPixels[i] == 0) continue;
                    double ratio = (double) estimatedTransparent[i] / totalPixels[i];
                    if (ratio < minRatio) {
                        minRatio = ratio;
                        mainlandIdxLocal = i;
                    }
                }

                if (mainlandIdxLocal >= 0) {
                    mainlandMapFile = FilePathUtil.getRelativeFile(
                            DOWNLOAD_MAP_DIR, "map_" + tags[mainlandIdxLocal] + ".png");
                    isCave[mainlandIdxLocal] = false;
                    log.info("自动检测大陆图: {} (透明比例 {}%)",
                            mainlandMapFile.getName(),
                            String.format("%.1f", minRatio * 100));

                    for (int i = 0; i < tags.length; i++) {
                        if (i == mainlandIdxLocal || totalPixels[i] == 0) continue;
                        caveIndices.add(i);
                        isCave[i] = true;
                        log.info("自动检测洞穴图: {} (透明比例 {}%)",
                                FilePathUtil.getRelativeFile(
                                        DOWNLOAD_MAP_DIR, "map_" + tags[i] + ".png"),
                                String.format("%.1f",
                                        100.0 * estimatedTransparent[i] / totalPixels[i]));
                    }
                }
            }
        }

        // 写入 IS_CAVE 配置，供下游 MapPostProcessor 使用
        DownloadConfig.MAP_REMOTE_URL_IS_CAVE = isCave;

        if (mainlandMapFile == null) {
            log.warn("未找到大陆图（所有地图均含透明像素），跳过洞穴增强处理");
            return;
        }

        if (caveIndices.isEmpty()) {
            log.info("没有洞穴图，跳过增强处理");
            return;
        }

        // 初始化进度跟踪
        DownloadProgressContext progress = DownloadProgressContext.getInstance();
        progress.reset("图片处理：生成SIFT图片");

        // 处理洞穴图（传递大陆图路径，不加载 BufferedImage — 内部按需条带读取）
        Path mainlandPath = mainlandMapFile.toPath();
        int processed = 0;
        for (int idx : caveIndices) {
            progress.addTask();

            String tag = tags.length > idx ? tags[idx] : "unknown";
            String displayName = displayNames.length > idx && !displayNames[idx].isBlank()
                    ? displayNames[idx] : tag;
            File caveFile = FilePathUtil.getRelativeFile(DOWNLOAD_MAP_DIR, "map_" + tag + ".png");
            if (!caveFile.exists()) {
                log.warn("洞穴图不存在，跳过: {}", caveFile);
                progress.finishTask();
                continue;
            }

            Path cavePath = caveFile.toPath();
            try {
                // 融合大陆特征（内部自动完成亮度提取、CLAHE、膨胀、填充）
                log.info("融合大陆特征: {} ({})", displayName, caveFile.getName());
                CaveImageFuser.fuse(cavePath, mainlandPath, 120, 0.3);

                processed++;
            } catch (IOException e) {
                log.warn("洞穴处理失败: {} (tag={})", displayName, tag, e);
            } finally {
                progress.finishTask();
            }
        }
        log.info("洞穴图处理完成: {}/{} 个", processed, caveIndices.size());
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

    /**
     * 统计一行像素中"暗色"像素数量（alpha=0 或 luminance ≤ threshold），
     * 兼容 ImageLineByte / ImageLineInt。
     */
    private static int countDarkPixels(ar.com.hjg.pngj.IImageLine line, int channels, int w, int lumThreshold) {
        int darkCount = 0;
        int[] bufInt = null;
        byte[] bufByte = null;

        if (line instanceof ImageLineByte byteLine) {
            bufByte = byteLine.getScanlineByte();
        } else if (line instanceof ar.com.hjg.pngj.ImageLineInt intLine) {
            bufInt = intLine.getScanline();
        } else {
            // 未知行类型，全部计为暗色
            return w;
        }

        boolean useByte = (bufByte != null);
        for (int x = 0; x < w; x++) {
            int off = x * channels;
            int a;
            int r, g, b;
            if (useByte) {
                a = bufByte[off + 3] & 0xFF;
                r = bufByte[off] & 0xFF;
                g = bufByte[off + 1] & 0xFF;
                b = bufByte[off + 2] & 0xFF;
            } else {
                a = bufInt[off + 3];
                r = bufInt[off];
                g = bufInt[off + 1];
                b = bufInt[off + 2];
            }
            if (a == 0) {
                darkCount++;
            } else {
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum <= lumThreshold) darkCount++;
            }
        }
        return darkCount;
    }
}
