package io.github.kedaya0209.roco.app.ui.service.resource;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.context.ResourcePointContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.ProgressEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import io.github.kedaya0209.roco.app.map.core.DownloadProgressContext;
import io.github.kedaya0209.roco.app.map.core.IconDownloader;
import io.github.kedaya0209.roco.app.map.core.MapDownloader;
import io.github.kedaya0209.roco.app.map.loader.ImageLoader;
import io.github.kedaya0209.roco.app.map.model.ResourcePoint;
import io.github.kedaya0209.roco.app.map.LayerMapTileGenerator;
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
                    } catch (Throwable e) {
                        log.error("内置资源初始化异常: ", e);
                        uiDelegate.onInitFailed("内置资源初始化失败: " + e.getMessage());
                    }
                });
            } else {
                // 外部资源涉及文件 I/O（校验资源清单），在后台线程执行以免阻塞 FX 线程
                Thread.ofPlatform().daemon(true).name("init-external-profile").start(() -> {
                    try {
                        initWithExternalProfile(onReady);
                    } catch (Throwable e) {
                        log.error("外部资源初始化异常: ", e);
                        uiDelegate.onInitFailed("外部资源初始化失败: " + e.getMessage());
                    }
                });
            }
        } catch (Throwable e) {
            log.error("环境初始化致命异常: ", e);
            AppEvents.publish(StatusEvent.class,
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
                recoverMissingResourcesAsync(missing, () -> {
                    try {
                        continueInitWithExternalProfile(onReady);
                    } catch (Throwable e) {
                        log.error("资源修复后初始化异常", e);
                        AppEvents.publish(StatusEvent.class,
                                new StatusEvent("资源修复后初始化失败: " + e.getMessage(), NotificationType.ERROR));
                    }
                });
                return;
            }
            continueInitWithExternalProfile(onReady);
        } else {
            handleFirstRun(onReady);
        }
    }

    private void continueInitWithExternalProfile(Runnable onReady) throws Exception {
        publishInitStep(0.2, "正在载入地图元数据...");
        initMapMetadata();
        publishInitStep(0.35, "校验瓦片文件...");
        validateAndRepairTiles();
        publishInitStep(0.6, "构建坐标索引系统...");
        ResourcePointContext.getInstance().loadAndInit();
        publishInitStep(0.8, "合并图标纹理图集...");
        buildIconAtlas();
        publishInitStep(1.0, "核心引擎已就绪");
        uiDelegate.onResourceReady(onReady);
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
                    if (path.startsWith(PathConfig.MAPS_DIR)) {
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

    /**
     * 异步修复缺失资源，UI 显示下载覆盖层和进度。
     * 下载完成后在 FX 线程回调 onComplete。
     */
    private void recoverMissingResourcesAsync(List<MissingEntry> missing, Runnable onComplete) {
        boolean needMap = missing.stream().anyMatch(e -> e.type() == ResourceType.MAP);
        boolean needConfig = missing.stream().anyMatch(e -> e.type() == ResourceType.CONFIG);
        boolean needIcons = missing.stream().anyMatch(e -> e.type() == ResourceType.ICON);

        downloadCancelled = false;

        Platform.runLater(() -> uiDelegate.showDownloadOverlay(() -> {
            downloadCancelled = true;
            MapDownloader.stopDownload();
            IconDownloader.stopDownload();
            uiDelegate.removeDownloadOverlay();
        }));

        DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
            double progress = total <= 0 ? 0 : (double) completed / total;
            AppEvents.publish(ProgressEvent.class, new ProgressEvent(progress,
                    String.format("%s (%d/%d)", DownloadProgressContext.getInstance().getStatusText(), completed, total)));
        });

        Thread.ofPlatform().daemon(true).start(() -> {
            try {
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
                if (!downloadCancelled) {
                    log.info("资源修复完成");
                    Platform.runLater(onComplete);
                }
            } catch (Throwable e) {
                log.error("资源修复异常", e);
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("资源修复失败: " + e.getMessage(), NotificationType.ERROR));
            }
        });
    }

    private void handleFirstRun(Runnable onReady) {
        Platform.runLater(() ->
                uiDelegate.showFirstRunDialog(
                        () -> startResourceDownloadAsync(onReady),
                        () -> startWithBuiltInResources(onReady),
                        Platform::exit
                )
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
                } catch (Throwable e) {
                    log.warn("后台资源下载异常（可忽略，下次启动会重试）", e);
                }
            });
            Thread.ofPlatform().daemon(true).name("init-internal-builtin").start(() -> {
                try {
                    initWithInternalProfile(onReady);
                } catch (Throwable e) {
                    log.error("内置资源初始化异常: ", e);
                    uiDelegate.onInitFailed("内置资源初始化失败: " + e.getMessage());
                }
            });
        } catch (Throwable e) {
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
            AppEvents.publish(ProgressEvent.class, new ProgressEvent(progress,
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
                        AppEvents.publish(StatusEvent.class,
                                new StatusEvent("资源同步失败，请检查网络", NotificationType.ERROR));
                    }
                }
            } catch (Throwable e) {
                log.error("地图资源下载异常", e);
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("资源同步中断，请检查网络", NotificationType.ERROR));
            }
        });
    }

    /**
     * 内置资源元数据加载（无需校验，资源打包在 JAR 中）。
     * 从 MultiMap_metadata.json 读取子图信息，取大陆图尺寸作为局部坐标空间。
     */
    private void initInternalMapMetadata() throws Exception {
        try (InputStream is = ResourceUtils.getResourceStream(
                ResourceConfigContext.getMultiMapMetadata())) {
            CompositeMapMetadata metadata = CompositeMapMetadata.load(is);
            CompositeMapMetadata.SubImageInfo mainland = metadata.subImages().stream()
                    .filter(s -> !s.isCave())
                    .findFirst().orElse(null);
            int imgW = mainland != null ? mainland.width() : 8192;
            int imgH = mainland != null ? mainland.height() : 8192;
            log.info("内置地图元数据从 MultiMap_metadata.json 读取: {}x{} ({} 子图)",
                    imgW, imgH, metadata.subImages().size());
            MapContext.getInstance().init("G", imgW, imgH);
            MapContext.getInstance().setMultiMapMetadata(metadata);
        }
    }

    private void initMapMetadata() throws Exception {
        try (InputStream is = ResourceUtils.getResourceStream(
                ResourceConfigContext.getMultiMapMetadata())) {
            CompositeMapMetadata metadata = CompositeMapMetadata.load(is);
            CompositeMapMetadata.SubImageInfo mainland = metadata.subImages().stream()
                    .filter(s -> !s.isCave())
                    .findFirst().orElse(null);
            int imgW = mainland != null ? mainland.width() : 8192;
            int imgH = mainland != null ? mainland.height() : 8192;
            log.info("地图元数据从 MultiMap_metadata.json 读取: {}x{} ({} 子图)",
                    imgW, imgH, metadata.subImages().size());
            MapContext.getInstance().init("G", imgW, imgH);
            MapContext.getInstance().setMultiMapMetadata(metadata);
        }
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
    // 瓦片校验与修复
    // ================================================================

    /**
     * 校验所有子图的瓦片金字塔文件是否存在，对缺失的尝试从源 PNG 重新生成。
     * init 资源清单不包含瓦片文件（数量多且层级结构固定），需单独校验。
     */
    private void validateAndRepairTiles() {
        CompositeMapMetadata metadata = MapContext.getInstance().getMultiMapMetadata();
        if (metadata == null || metadata.subImages().isEmpty()) return;

        boolean needsRepair = false;
        for (CompositeMapMetadata.SubImageInfo sub : metadata.subImages()) {
            String tileDir = sub.tileDir();
            if (tileDir == null || tileDir.isEmpty()) continue;

            File dir = ResourceUtils.getExternalFile(tileDir);
            if (tilesExist(dir)) continue;

            if (!needsRepair) {
                needsRepair = true;
                publishInitStep(0.35, "修复瓦片文件...");
            }

            log.warn("瓦片目录不存在或不完整: {}，尝试从源 PNG 重新生成", tileDir);
            if (regenerateTiles(sub, dir)) {
                log.info("瓦片重新生成成功: {}", tileDir);
            } else {
                log.warn("瓦片重新生成失败，渲染时瓦片会按需回退: {}", tileDir);
            }
        }
    }

    /**
     * 检查瓦片目录是否存在且包含所需的层级文件（至少 level 0 和 4 有瓦片）。
     */
    private static boolean tilesExist(File tileDir) {
        if (!tileDir.isDirectory()) return false;
        // 检查最高和最低分辨率层级是否有瓦片文件
        for (int level : new int[]{0, 4}) {
            File levelDir = new File(tileDir, String.valueOf(level));
            if (!levelDir.isDirectory()) return false;
            File[] files = levelDir.listFiles((_, name) -> name.endsWith(".png"));
            if (files == null || files.length == 0) return false;
        }
        return true;
    }

    /**
     * 从子图源 PNG 重新生成瓦片金字塔（5 级，0=全分辨率 ~ 4=最低分辨率）。
     */
    private static boolean regenerateTiles(CompositeMapMetadata.SubImageInfo sub, File outputDir) {
        String sourcePath = sub.sourcePath();
        if (sourcePath == null || sourcePath.isEmpty()) return false;

        File sourceFile = ResourceUtils.getExternalFile(sourcePath);
        if (!sourceFile.isFile()) {
            log.warn("源 PNG 不存在，无法生成瓦片: {}", sourceFile);
            return false;
        }

        outputDir.mkdirs();
        try {
            new LayerMapTileGenerator().generateTiles(
                    sourceFile.getAbsolutePath(),
                    outputDir.getAbsolutePath());
            return true;
        } catch (Exception e) {
            log.warn("瓦片生成异常: {}", sourceFile, e);
            return false;
        }
    }

    // ================================================================
    // 元数据与图集
    // ================================================================

    private void publishInitStep(double progress, String message) {
        AppEvents.publish(ProgressEvent.class, new ProgressEvent(progress, message));
    }

    @ThreadSafe
    enum ResourceType {MAP, ICON, CONFIG}

    @ThreadSafe
    record MissingEntry(String path, String url, ResourceType type) {
    }
}
