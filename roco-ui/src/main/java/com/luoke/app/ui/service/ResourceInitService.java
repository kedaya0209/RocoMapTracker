package com.luoke.app.ui.service;

import com.luoke.app.config.AppConfig;
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
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.map.util.MapFileMover;
import com.luoke.app.ui.render.IconCache;
import com.luoke.app.ui.render.TileGeneratorService;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
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
            OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);

            if (AppConfig.INTERNAL_RESOURCE) {
                initWithInternalProfile(onReady);
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
        publishInitStep(0.2, "初始化逻辑处理器...");
        publishInitStep(0.4, "正在载入地图元数据...");
        initMapMetadata();
        publishInitStep(0.7, "构建坐标索引系统...");
        ResourcePointContext.getInstance().loadAndInit();
        publishInitStep(0.85, "合并图标纹理图集...");
        buildIconAtlas();
        publishInitStep(1.0, "核心引擎已就绪");
        uiDelegate.onResourceReady(onReady);
    }

    // ================================================================
    // 外部资源
    // ================================================================

    private void initWithExternalProfile(Runnable onReady) throws Exception {
        File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
        if (initFile.exists()) {
            List<MissingEntry> missing = validateManifest(initFile);
            if (!missing.isEmpty()) {
                log.warn("资源文件缺失 {} 个，启动修复流程", missing.size());
                recoverMissingResources(missing);
            }

            publishInitStep(0.2, "初始化逻辑处理器...");
            publishInitStep(0.4, "正在载入地图元数据...");
            initMapMetadata();
            publishInitStep(0.5, "正在验证地图瓦片...");
            if (ResourceConfigContext.getCurrentProfile() != ResourceConfigContext.ResourceProfile.INTERNAL) {
                tileGeneratorService.validateAndGenerateTiles();
            }
            publishInitStep(0.7, "构建坐标索引系统...");
            ResourcePointContext.getInstance().loadAndInit();
            publishInitStep(0.85, "合并图标纹理图集...");
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
                    if (path.startsWith(AppConfig.MAP_RESOURCE_DIR)) {
                        type = ResourceType.MAP;
                    } else if (path.startsWith(AppConfig.ICON_DIR)) {
                        type = ResourceType.ICON;
                    } else if (path.startsWith(AppConfig.RESOURCE_ICON_DIR)) {
                        type = ResourceType.CONFIG;
                    } else {
                        continue;
                    }
                    missing.add(new MissingEntry(path, urlOrType, type));
                }
            }
        } catch (Exception e) {
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
            AppConfig.INTERNAL_RESOURCE = true;
            AppConfig.save();
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("后台开始下载 WIKI 资源...");
                    MapResourceUpdater.updateAllResources();
                    log.info("后台资源下载完成");
                } catch (Exception e) {
                    log.warn("后台资源下载异常（可忽略，下次启动会重试）", e);
                }
            });
            initWithInternalProfile(onReady);
        } catch (Exception e) {
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

        Thread.ofVirtual().start(() -> {
            try {
                log.info("开始下载地图资源...");
                MapResourceUpdater.updateAllResources();
                if (!downloadCancelled) {
                    log.info("资源下载完成，重新进入初始化自检");
                    initWithExternalProfile(onReady);
                }
            } catch (Exception e) {
                log.error("地图资源下载异常", e);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("资源同步中断，请检查网络", NotificationType.ERROR));
            }
        });
    }

    private void initMapMetadata() throws Exception {
        String mapPath = ResourceConfigContext.getShowMap();
        int imgW, imgH;

        String metaPath = ResourceConfigContext.getTilesDir() + "/tiles_meta.json";
        try (InputStream metaIn = ResourceUtils.getResourceStream(metaPath)) {
            com.fasterxml.jackson.databind.JsonNode meta = com.luoke.app.utils.JsonUtils.getMapper().readTree(metaIn);
            imgW = meta.get("mapWidth").asInt();
            imgH = meta.get("mapHeight").asInt();
            log.info("地图元数据从 tiles_meta.json 读取: {}x{}", imgW, imgH);
        } catch (Exception metaEx) {
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
                iconPaths.add(AppConfig.ICON_DIR + iconFile);
            }
        }
        if (!iconPaths.isEmpty()) {
            IconCache.getInstance().buildAtlas(iconPaths);
            IconCache.getInstance().clearIndividualCaches();
            com.luoke.app.map.loader.ImageLoader.getInstance().clearCache();
            log.info("图标纹理图集已构建: {} 个图标, 已释放单图标缓存", iconPaths.size());
        }
    }

    // ================================================================
    // 元数据与图集
    // ================================================================

    private void publishInitStep(double progress, String message) {
        HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress, message));
    }

    enum ResourceType {MAP, ICON, CONFIG}

    record MissingEntry(String path, String url, ResourceType type) {
    }
}
