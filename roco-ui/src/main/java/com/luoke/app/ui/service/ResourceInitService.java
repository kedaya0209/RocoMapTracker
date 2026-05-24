package com.luoke.app.ui.service;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import com.fasterxml.jackson.databind.JsonNode;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.PathConfig;
import com.luoke.app.config.DownloadConfig;
import com.luoke.app.config.OcrConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.map.loader.ImageLoader;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.map.util.MapFileMover;
import com.luoke.app.utils.JsonUtils;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 资源初始化编排服务。
 * 负责 INTERNAL/EXTERNAL 资源路径的校验、下载修复、元数据加载和图集构建。
 * UI 操作通过 {@link ResourceInitUiDelegate} 回调，不直接依赖 JavaFX UI 类。
 */
@NotThreadSafe
@Slf4j
public class ResourceInitService {

    private final ResourceInitUiDelegate uiDelegate;
    private final TileGeneratorService tileGeneratorService = new TileGeneratorService();
    private volatile boolean downloadCancelled = false;

    public ResourceInitService(ResourceInitUiDelegate uiDelegate) {
        this.uiDelegate = uiDelegate;
    }

    // ================================================================
    // 入口
    // ================================================================

    /**
     * 开始资源初始化流程。
     *
     * @param onReady 资源就绪后的回调（主界面构建）
     */
    public void start(Runnable onReady) {
        try {
            OcrAsyncManager.initialize(OcrConfig.OCR_CORE_SIZE);

            if (DownloadConfig.INTERNAL_RESOURCE) {
                // 内置资源全部从 classpath 加载（JAR 解压/PNG 解码），耗时较长。
                // 在虚拟线程执行以免阻塞 JavaFX Application Thread，确保进度更新能送达 UI。
                // 使用平台线程而非虚拟线程，避免 GraalVM Native Image 下 carrier 栈不足导致 segfault。
                Thread.ofPlatform().daemon(true).name("init-internal-profile").start(() -> {
                    try {
                        initWithInternalProfile(onReady);
                    } catch (Exception e) { // initWithInternalProfile 声明 throws Exception，包含文件 I/O 和自定义异常
                        log.error("内置资源初始化异常: ", e);
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                                new StatusEvent("内置资源初始化失败: " + e.getMessage(), NotificationType.ERROR));
                    }
                });
            } else {
                initWithExternalProfile(onReady);
            }
        } catch (Exception e) { // OcrAsyncManager.initialize 和 initWith*Profile 都可能抛出异常
            log.error("环境初始化致命异常: ", e);
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent("核心服务启动失败: " + e.getMessage(), NotificationType.ERROR));
        }
    }

    // ================================================================
    // 内置资源
    // ================================================================

    private void initWithInternalProfile(Runnable onReady) throws Exception {
        publishInitStep(0.2, "正在载入地图元数据...");
        initInternalMapMetadata();
        publishInitStep(0.4, "构建坐标索引系统...");
        ResourcePointContext.getInstance().loadAndInit();
        publishInitStep(0.7, "合并图标纹理图集...");
        buildIconAtlas();
        publishInitStep(1.0, "核心引擎已就绪");
        uiDelegate.onResourceReady(onReady);
    }

    // ================================================================
    // 外部资源
    // ================================================================

    private void initWithExternalProfile(Runnable onReady) throws Exception {
        File initFile = ResourceUtils.getExternalFile(PathConfig.SOURCE_INIT);
        if (initFile.exists()) {
            List<MissingEntry> missing = validateManifest(initFile);
            if (!missing.isEmpty()) {
                log.warn("资源文件缺失 {} 个，启动修复流程", missing.size());
                recoverMissingResources(missing);
            }

            publishInitStep(0.2, "正在载入地图元数据...");
            initMapMetadata();
            publishInitStep(0.4, "正在验证地图瓦片...");
            if (ResourceConfigContext.getCurrentProfile() != ResourceConfigContext.ResourceProfile.INTERNAL) {
                tileGeneratorService.validateAndGenerateTiles();
                // 瓦片生成可能加载了 256MB BufferedImage，触发 GC 让堆缩回
                System.gc();
                log.info("瓦片验证完成，已触发堆内存回收");
            }
            publishInitStep(0.6, "构建坐标索引系统...");
            ResourcePointContext.getInstance().loadAndInit();
            publishInitStep(0.8, "合并图标纹理图集...");
            buildIconAtlas();
            publishInitStep(1.0, "核心引擎已就绪");
            uiDelegate.onResourceReady(onReady);
        } else {
            handleFirstRun(onReady);
        }
    }

    // ================================================================
    // 资源清单校验
    // ================================================================

    private List<MissingEntry> validateManifest(File initFile) {
        List<MissingEntry> missing = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new FileReader(initFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 1) continue;
                String path = parts[0].strip();
                if (path.isEmpty()) continue;
                @SuppressWarnings("unused")
                String urlOrType = parts.length >= 2 ? parts[1].strip() : "";

                File f = ResourceUtils.getExternalFile(path);
                if (!f.exists()) {
                    ResourceType type;
                    if (path.startsWith(PathConfig.MAP_RESOURCE_DIR)) {
                        type = ResourceType.MAP;
                    } else if (path.startsWith(PathConfig.ICON_DIR)) {
                        type = ResourceType.ICON;
                    } else if (path.startsWith(PathConfig.RESOURCE_ICON_DIR)) {
                        type = ResourceType.CONFIG;
                    } else {
                        continue;
                    }
                    missing.add(new MissingEntry(path, urlOrType, type));
                }
            }
        } catch (IOException e) {
            log.warn("读取资源清单失败", e);
        }
        return missing;
    }

    private void recoverMissingResources(List<MissingEntry> missing) {
        boolean needMap = missing.stream().anyMatch(e -> e.type() == ResourceType.MAP);
        boolean needConfig = missing.stream().anyMatch(e -> e.type() == ResourceType.CONFIG);
        boolean needIcons = missing.stream().anyMatch(e -> e.type() == ResourceType.ICON);

        if (needMap) {
            log.info("修复缺失的地图文件...");
            MapResourceUpdater.updateMapOnly();
        }
        if (needConfig || needIcons) {
            log.info("修复缺失的配置/图标文件...");
            MapResourceUpdater.updateIconsAndConfigOnly();
        }

        MapFileMover.writeInitManifest();
        log.info("资源修复完成");
    }

    private void handleFirstRun(Runnable onReady) {
        uiDelegate.showFirstRunDialog(
                () -> startResourceDownloadAsync(onReady),
                () -> startWithBuiltInResources(onReady),
                Platform::exit
        );
    }

    private void startWithBuiltInResources(Runnable onReady) {
        try {
            DownloadConfig.INTERNAL_RESOURCE = true;
            ConfigPersistence.save();
            Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    log.info("后台开始下载 WIKI 资源...");
                    MapResourceUpdater.updateAllResources();
                    log.info("后台资源下载完成");
                } catch (RuntimeException e) {
                    log.warn("后台资源下载异常（可忽略，下次启动会重试）", e);
                }
            });
            Thread.ofPlatform().daemon(true).name("init-internal-builtin").start(() -> {
                try {
                    initWithInternalProfile(onReady);
                } catch (Exception e) { // initWithInternalProfile 声明 throws Exception，包含文件 I/O 和自定义异常
                    log.error("内置资源初始化异常: ", e);
                    HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                            new StatusEvent("内置资源初始化失败: " + e.getMessage(), NotificationType.ERROR));
                }
            });
        } catch (RuntimeException e) {
            log.error("内置资源模式启动失败", e);
        }
    }

    // ================================================================
    // 首次运行
    // ================================================================

    private void startResourceDownloadAsync(Runnable onReady) {
        downloadCancelled = false;

        uiDelegate.showDownloadOverlay(() -> {
            downloadCancelled = true;
            MapDownloader.stopDownload();
            IconDownloader.stopDownload();
            uiDelegate.removeDownloadOverlay();
            Platform.runLater(() -> start(onReady));
        });

        DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
            double progress = total <= 0 ? 0 : (double) completed / total;
            HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress,
                    String.format("%s (%d/%d)", DownloadProgressContext.getInstance().getStatusText(), completed, total)));
        });

        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                log.info("开始下载地图资源...");
                MapResourceUpdater.updateAllResources();
                if (!downloadCancelled) {
                    log.info("资源下载完成，重新进入初始化自检");
                    initWithExternalProfile(onReady);
                }
            } catch (Exception e) { // 包含网络 I/O 和 initWithExternalProfile（声明 throws Exception）
                log.error("地图资源下载异常", e);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("资源同步中断，请检查网络", NotificationType.ERROR));
            }
        });
    }

    /**
     * 内置资源元数据加载（无需校验，资源打包在 JAR 中）。
     * 直接从 WorldMap_SIFT.png 头部 IHDR chunk 读取地图尺寸，避免 ImageIO native 栈深度。
     */
    private void initInternalMapMetadata() throws Exception {
        String mapPath = ResourceConfigContext.getSiftMap();
        int imgW, imgH;

        try (InputStream in = ResourceUtils.getResourceStream(mapPath)) {
            // PNG 头部结构: 8 bytes signature + IHDR chunk (4 len + 4 type + 13 data + 4 crc)
            // IHDR data: 4 bytes width + 4 bytes height (big-endian)
            byte[] header = new byte[24];
            int offset = 0;
            while (offset < header.length) {
                int read = in.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset < 24) {
                throw new Exception("PNG 文件不完整，期望 24 字节头部，实际 " + offset);
            }
            imgW = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                 | ((header[18] & 0xFF) << 8)  | (header[19] & 0xFF);
            imgH = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                 | ((header[22] & 0xFF) << 8)  | (header[23] & 0xFF);
        }
        log.info("内置地图元数据从 PNG 头部读取: {}x{}", imgW, imgH);
        MapContext.getInstance().init("G", imgW, imgH);
    }

    private void initMapMetadata() throws Exception {
        String mapPath = ResourceConfigContext.getShowMap();
        int imgW, imgH;

        String metaPath = ResourceConfigContext.getTilesDir() + "/tiles_meta.json";
        try (InputStream metaIn = ResourceUtils. getResourceStream(metaPath)) {
            JsonNode meta = JsonUtils.getMapper().readTree(metaIn);
            imgW = meta.get("mapWidth").asInt();
            imgH = meta.get("mapHeight").asInt();
            log.info("地图元数据从 tiles_meta.json 读取: {}x{}", imgW, imgH);
        } catch (IOException metaEx) {
            try (InputStream in = ResourceUtils.getResourceStream(mapPath);
                 ImageInputStream iis = ImageIO.createImageInputStream(in)) {
                Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("png");
                if (!readers.hasNext()) {
                    throw new Exception("无可用 PNG ImageReader");
                }
                ImageReader reader = readers.next();
                reader.setInput(iis);
                imgW = reader.getWidth(0);
                imgH = reader.getHeight(0);
                reader.dispose();
            }
            log.info("地图元数据从 PNG 读取: {}x{}", imgW, imgH);
        }

        MapContext.getInstance().init("G", imgW, imgH);
    }

    private void buildIconAtlas() {
        Set<String> iconPaths = new HashSet<>();
        for (ResourcePoint rp : ResourcePointContext.getInstance().getAllPoints()) {
            String iconFile = rp.getConfig().getIcon();
            if (iconFile != null && !iconFile.isEmpty()) {
                iconPaths.add(PathConfig.ICON_DIR + iconFile);
            }
        }
        if (!iconPaths.isEmpty()) {
            IconCache.getInstance().buildAtlas(iconPaths);
            IconCache.getInstance().clearIndividualCaches();
            ImageLoader.getInstance().clearCache();
            log.info("图标纹理图集已构建: {} 个图标, 已释放单图标缓存", iconPaths.size());
        }
    }

    // ================================================================
    // 元数据与图集
    // ================================================================

    private void publishInitStep(double progress, String message) {
        HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress, message));
    }

    @ThreadSafe
    enum ResourceType {MAP, ICON, CONFIG}

    @ThreadSafe
    record MissingEntry(String path, String url, ResourceType type) {
    }
}
