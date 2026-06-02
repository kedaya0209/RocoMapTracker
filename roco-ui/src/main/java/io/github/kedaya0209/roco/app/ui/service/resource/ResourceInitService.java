package io.github.kedaya0209.roco.app.ui.service.resource;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.ProgressEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import io.github.kedaya0209.roco.app.map.core.DownloadProgressContext;
import io.github.kedaya0209.roco.app.map.core.IconDownloader;
import io.github.kedaya0209.roco.app.map.core.MapDownloader;
import io.github.kedaya0209.roco.app.map.loader.ImageLoader;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.utils.JsonUtils;
import io.github.kedaya0209.roco.app.utils.PngUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
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
import java.util.List;
import java.util.Set;

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
            if (DownloadConfig.INTERNAL_RESOURCE) {
                // 内置资源全部从 classpath 加载（JAR 解压/PNG 解码），耗时较长。
                // 在虚拟线程执行以免阻塞 JavaFX Application Thread，确保进度更新能送达 UI。
                // 使用平台线程而非虚拟线程，避免 GraalVM Native Image 下 carrier 栈不足导致 segfault。
                Thread.ofPlatform().daemon(true).name("init-internal-profile").start(() -> {
                    try {
                        initWithInternalProfile(onReady);
                    } catch (Exception e) {
                        log.error("内置资源初始化异常: ", e);
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                                new StatusEvent("内置资源初始化失败: " + e.getMessage(), NotificationType.ERROR));
                    }
                });
            } else {
                initWithExternalProfile(onReady);
            }
        } catch (Exception e) {
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
                // 瓦片生成消耗了大量堆内存，触发 GC 让堆缩回
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
            if (!MapResourceUpdater.updateMapOnly()) {
                log.warn("地图修复未能完成");
            }
        }
        if (needConfig || needIcons) {
            log.info("修复缺失的配置/图标文件...");
            if (!MapResourceUpdater.updateIconsAndConfigOnly()) {
                log.warn("配置/图标修复未能完成");
            }
        }
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
                    if (MapResourceUpdater.updateAllResources()) {
                        log.info("后台资源下载完成");
                    } else {
                        log.warn("后台资源下载未完成（可忽略，下次启动会重试）");
                    }
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
                boolean success = MapResourceUpdater.updateAllResources();
                if (!downloadCancelled) {
                    if (success) {
                        log.info("资源下载完成，重新进入初始化自检");
                        initWithExternalProfile(onReady);
                    } else {
                        log.error("资源下载失败");
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                                new StatusEvent("资源同步失败，请检查网络", NotificationType.ERROR));
                    }
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
     * 优先使用 MultiMap 元数据，回退到从 WorldMap_SIFT.png 头部 IHDR chunk 读取地图尺寸。
     */
    private void initInternalMapMetadata() throws Exception {
        // MultiMap 检测：优先使用复合地图元数据
        if (ResourceConfigContext.isMultiMapActive()) {
            try (InputStream is = ResourceUtils.getResourceStream(
                    ResourceConfigContext.getMultiMapMetadata())) {
                CompositeMapMetadata metadata = CompositeMapMetadata.load(is);
                int imgW = metadata.width();
                int imgH = metadata.totalHeight();
                log.info("内置地图元数据从 MultiMap_metadata.json 读取: {}x{} ({} 子图)",
                        imgW, imgH, metadata.subImages().size());
                MapContext.getInstance().init("G", imgW, imgH);
                MapContext.getInstance().setMultiMapMetadata(metadata);
            }
            return;
        }

        String mapPath = ResourceConfigContext.getSiftMap();
        int imgW, imgH;

        int[] size;
        try (InputStream in = ResourceUtils.getResourceStream(mapPath)) {
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
            size = PngUtil.parseSize(header);
            if (size == null) {
                throw new Exception("PNG 头部格式无效");
            }
        }
        imgW = size[0];
        imgH = size[1];
        log.info("内置地图元数据从 PNG 头部读取: {}x{}", imgW, imgH);
        MapContext.getInstance().init("G", imgW, imgH);
    }

    private void initMapMetadata() throws Exception {
        // MultiMap 检测：优先使用复合地图元数据
        if (ResourceConfigContext.isMultiMapActive()) {
            try (InputStream is = ResourceUtils.getResourceStream(
                    ResourceConfigContext.getMultiMapMetadata())) {
                CompositeMapMetadata metadata = CompositeMapMetadata.load(is);
                int imgW = metadata.width();
                int imgH = metadata.totalHeight();
                log.info("地图元数据从 MultiMap_metadata.json 读取: {}x{} ({} 子图)",
                        imgW, imgH, metadata.subImages().size());
                MapContext.getInstance().init("G", imgW, imgH);
                MapContext.getInstance().setMultiMapMetadata(metadata);
            }
            return;
        }

        String mapPath = ResourceConfigContext.getShowMap();
        int imgW, imgH;

        String metaPath = ResourceConfigContext.getTilesDir() + "/tiles_meta.json";
        try (InputStream metaIn = ResourceUtils. getResourceStream(metaPath)) {
            JsonNode meta = JsonUtils.getMapper().readTree(metaIn);
            imgW = meta.get("mapWidth").asInt();
            imgH = meta.get("mapHeight").asInt();
            log.info("地图元数据从 tiles_meta.json 读取: {}x{}", imgW, imgH);
        } catch (IOException metaEx) {
            try (InputStream in = ResourceUtils.getResourceStream(mapPath)) {
                byte[] header = new byte[24];
                int off = 0;
                while (off < header.length) {
                    int read = in.read(header, off, header.length - off);
                    if (read < 0) break;
                    off += read;
                }
                if (off < 24) throw new Exception("PNG 头部不完整");
                int[] pngSize = PngUtil.parseSize(header);
                if (pngSize == null) throw new Exception("PNG 头部格式无效");
                imgW = pngSize[0];
                imgH = pngSize[1];
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
