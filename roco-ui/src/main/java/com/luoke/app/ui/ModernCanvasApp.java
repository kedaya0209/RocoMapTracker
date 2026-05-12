package com.luoke.app.ui;

import atlantafx.base.theme.*;
import com.luoke.app.capture.CaptureService;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.MapMatcherProcessor;
import com.luoke.app.capture.processor.OcrProcessor;
import com.luoke.app.capture.processor.SaveImageProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.*;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.impl.UiResponseHook;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.process.JobObjectManager;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.MapRenderer;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.WindowManager;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class ModernCanvasApp extends Application {

    private static final int RESIZE_MARGIN = 8;
    private static final String UNIFIED_BLUE = "#00BFFF";

    private static StackPane rootStack;
    private final WindowManager windowManager = new WindowManager(RESIZE_MARGIN);
    private final UiAnimator uiAnimator = new UiAnimator();

    private LoadingOverlay globalLoading;
    private CaptureService mainCaptureService;
    private SiftMatchHandler siftMatchClient;
    private volatile boolean isAppRunning = true;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void init() throws Exception {
        super.init();
    }

    public static String[] getAvailableThemes() {
        return new String[]{"PrimerDark", "PrimerLight", "NordDark", "NordLight",
                "CupertinoDark", "CupertinoLight", "Dracula"};
    }

    // ==================== 主题管理 ====================

    public static void applyTheme(String name) {
        Theme theme = switch (name) {
            case "PrimerLight" -> new PrimerLight();
            case "NordDark" -> new NordDark();
            case "NordLight" -> new NordLight();
            case "CupertinoDark" -> new CupertinoDark();
            case "CupertinoLight" -> new CupertinoLight();
            case "Dracula" -> new Dracula();
            default -> new PrimerDark();
        };
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
    }

    public static void switchTheme(String name) {
        AppConfig.THEME = name;
        AppConfig.save();
        applyTheme(name);
    }

    @Override
    public void start(Stage primaryStage) {
        JobObjectManager.init();

        try {
            int port = SocketServer.instance().start();
            log.info("SocketServer 已启动, 端口: {}", port);
        } catch (Exception e) {
            log.error("SocketServer 启动失败", e);
        }

        initSiftMatchClient();

        SwitchMapMatcher.getInstance().setSwitchCallback(newVariant -> {
            log.info("算法变体切换: {}", newVariant);
            if (siftMatchClient != null) {
                siftMatchClient.restart(SiftMatchHandler.variantOrdinal(newVariant));
            }
        });

        applyTheme(AppConfig.THEME);

        StackPane wrapper = new StackPane();
        wrapper.setBackground(Background.EMPTY);
        wrapper.setPadding(new Insets(15));

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: -color-bg-default; " +
                "-fx-background-radius: 12px; " +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.4), 30, 0, 0, 15);");

        Rectangle rootClip = new Rectangle();
        rootClip.widthProperty().bind(rootStack.widthProperty());
        rootClip.heightProperty().bind(rootStack.heightProperty());
        rootClip.setArcWidth(24);
        rootClip.setArcHeight(24);
        rootStack.setClip(rootClip);

        wrapper.getChildren().add(rootStack);

        globalLoading = new LoadingOverlay(null);
        rootStack.getChildren().add(globalLoading);
        HookRegistry.INSTANCE.register(new UiResponseHook(rootStack, globalLoading));

        Scene scene = new Scene(wrapper, 1100, 800);
        scene.setFill(Color.TRANSPARENT);

        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(scene);
        primaryStage.show();

        checkAndInitResourcesAsync(primaryStage);
    }

    private void initSiftMatchClient() {
        siftMatchClient = new SiftMatchHandler();
        SocketServer.instance().register(siftMatchClient);

        siftMatchClient.start((ready, detail) -> {
            if (ready) {
                log.info("SIFT 匹配引擎就绪: {}", detail);
            } else {
                log.warn("SIFT 匹配引擎未就绪: {}", detail);
            }
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent(ready ? "SIFT引擎已就绪" : "SIFT引擎未连接: " + detail,
                            ready ? NotificationType.SUCCESS : NotificationType.ERROR));
        });
    }

    private void checkAndInitResourcesAsync(Stage primaryStage) {
        Thread.ofVirtual().start(() -> {
            try {
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);

                File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
                if (initFile.exists()) {
                    publishInitStep(0.2, "初始化逻辑处理器...");

                    publishInitStep(0.4, "正在载入地图元数据...");
                    initMapMetadata();

                    publishInitStep(0.5, "正在验证地图瓦片...");
                    if (ResourceConfigContext.getCurrentProfile() != ResourceConfigContext.ResourceProfile.INTERNAL) {
                        //使用内置资源，不生成
                        validateAndGenerateTiles();
                    }

                    publishInitStep(0.7, "构建坐标索引系统...");
                    ResourcePointContext.getInstance().loadAndInit();

                    publishInitStep(1.0, "核心引擎已就绪");
                    Platform.runLater(() -> buildMainUI(primaryStage));
                } else {
                    handleFirstRun(primaryStage, initFile);
                }
            } catch (Exception e) {
                log.error("环境初始化致命异常: ", e);
                HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                        new StatusEvent("核心服务启动失败: " + e.getMessage(), NotificationType.ERROR));
            }
        });
    }

    private void handleFirstRun(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            globalLoading.dispose();
            rootStack.getChildren().remove(globalLoading);
            DialogUtils.showSimpleDialog(rootStack, "初始化配置",
                    "检测到本地资源不完整，是否立即从 WIKI 同步最新地图数据？", "立即同步", false,
                    () -> startResourceDownloadAsync(primaryStage, initFile));
        });
    }

    private void startResourceDownloadAsync(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            rootStack.getChildren().stream()
                    .filter(node -> node instanceof LoadingOverlay)
                    .forEach(node -> ((LoadingOverlay) node).dispose());
            rootStack.getChildren().removeIf(node -> node instanceof LoadingOverlay);

            LoadingOverlay downloadOverlay = new LoadingOverlay(() -> {
                MapDownloader.stopDownload();
                IconDownloader.stopDownload();
                Platform.runLater(() -> checkAndInitResourcesAsync(primaryStage));
            });

            rootStack.getChildren().add(downloadOverlay);

            DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
                double progress = total <= 0 ? 0 : (double) completed / total;
                HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress,
                        String.format("%s (%d/%d)", DownloadProgressContext.getInstance().getStatusText(), completed, total)));
            });

            Thread.ofVirtual().start(() -> {
                try {
                    log.info("开始下载地图资源...");
                    MapResourceUpdater.updateAllResources();
                    if (initFile.createNewFile()) {
                        log.info("资源下载完成，重新进入初始化自检");
                        checkAndInitResourcesAsync(primaryStage);
                    }
                } catch (Exception e) {
                    log.error("地图资源下载异常", e);
                    HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                            new StatusEvent("资源同步中断，请检查网络", NotificationType.ERROR));
                }
            });
        });
    }

    private void buildMainUI(Stage primaryStage) {
        try {
            globalLoading.dispose();
            rootStack.getChildren().clear();

            // 画布容器
            Pane canvasContainer = new Pane();
            canvasContainer.setStyle("-fx-background-color: #1a1a2e;");

            // 地图渲染器（瓦片金字塔 + 双 Canvas 图标 + 玩家）
            MapRenderer renderer = new MapRenderer(canvasContainer);
            renderer.init((int) MapContext.getInstance().getMapWidth(), (int) MapContext.getInstance().getMapHeight());

            // 玩家图标
            try {
                Image playerIcon = new Image(ResourceUtils.getResourceStream(
                        ResourceConfigContext.getPlayerIcon()));
                if (!playerIcon.isError()) {
                    renderer.setPlayerImage(playerIcon);
                }
            } catch (Exception e) {
                log.warn("玩家图标加载失败", e);
            }

            // InteractiveCanvas（透明覆盖层，处理鼠标事件）
            InteractiveCanvas interactiveCanvas = new InteractiveCanvas();
            interactiveCanvas.setMapRenderer(renderer);
            interactiveCanvas.setUiAnimator(uiAnimator);
            interactiveCanvas.widthProperty().bind(canvasContainer.widthProperty());
            interactiveCanvas.heightProperty().bind(canvasContainer.heightProperty());
            canvasContainer.getChildren().add(interactiveCanvas);

            // 视口大小变化 → 标记脏
            canvasContainer.widthProperty().addListener(e -> renderer.markDirty());
            canvasContainer.heightProperty().addListener(e -> renderer.markDirty());

            // 资源点变化 → 标记脏
            HookRegistry.INSTANCE.register(new AbstractGenericHook<>() {
                @Override
                public java.util.Set<HookEventType> supportedEvents() {
                    return java.util.Set.of(HookEventType.RESOURCE_POINT_CHANGED);
                }

                @Override
                public void onEvent(HookEventType eventType, Object data) {
                    Platform.runLater(renderer::markDirty);
                }
            });

            // 覆盖层组件
            StatsOverlay statsOverlay = StatsOverlay.getInstance();
            ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();

            // 侧边栏
            Sidebar sidebar = new Sidebar();
            sidebar.setTranslateX(-240);
            AnchorPane sidebarContainer = new AnchorPane(sidebar);
            sidebarContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(sidebar, 45.0);
            AnchorPane.setBottomAnchor(sidebar, 0.0);

            // 右侧面板
            AnchorPane panelAnchor = new AnchorPane(statsOverlay, resourcePanel);
            panelAnchor.setPickOnBounds(false);
            AnchorPane.setTopAnchor(statsOverlay, 45.0);
            AnchorPane.setRightAnchor(statsOverlay, 20.0);
            AnchorPane.setTopAnchor(resourcePanel, 90.0);
            AnchorPane.setRightAnchor(resourcePanel, 20.0);

            // 浮动工具箱
            FloatToolbox floatToolbox = new FloatToolbox(resourcePanel, UNIFIED_BLUE);
            AnchorPane floatContainer = new AnchorPane(floatToolbox);
            floatContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(floatToolbox, 90.0);
            AnchorPane.setLeftAnchor(floatToolbox, 20.0);

            // 菜单按钮
            Button menuBtn = createMenuButton();
            TitleBar titleBar = TitleBar.getInstance(primaryStage, menuBtn,
                    canvasContainer, sidebarContainer, panelAnchor, floatContainer);

            VBox uiOverlay = new VBox(titleBar);
            uiOverlay.setPickOnBounds(false);

            AnchorPane resizeLayer = new AnchorPane();
            resizeLayer.setPickOnBounds(false);
            windowManager.install(primaryStage, resizeLayer);

            // 层级
            rootStack.getChildren().addAll(canvasContainer, sidebarContainer, panelAnchor, floatContainer, uiOverlay, resizeLayer);

            // 侧边栏切换
            uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);

            // 核心服务
            startCaptureWatchdog();

            // 启动渲染循环
            renderer.start();

            log.info("主界面构建完成");
        } catch (Exception e) {
            log.error("UI构建异常: ", e);
            HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                    new StatusEvent("界面加载失败，请尝试重启", NotificationType.ERROR));
        }
    }

    private void startCaptureWatchdog() {
        Thread.ofVirtual().start(() -> {
            while (isAppRunning) {
                try {
                    if (mainCaptureService == null) {
                        mainCaptureService = new CaptureService("洛克王国：世界");
                        setupCaptureProcessors();
                    }
                    if (!mainCaptureService.isRunning()) {
                        if (mainCaptureService.tryConnect()) {
                            log.info("采集会话已连接");
                        } else {
                            log.info("未找到游戏窗口，5秒后重试...");
                        }
                    }
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("采集监控守护进程异常", e);
                }
            }
        });
    }

    private void setupCaptureProcessors() {
        if (mainCaptureService == null) return;
//        SaveImageProcessor saveImageProcessor = new SaveImageProcessor(0);
        MapMatcherProcessor siftProcessor = new MapMatcherProcessor(0, siftMatchClient);
        OcrProcessor ocrProcessor = new OcrProcessor(1);
        mainCaptureService.addProcessors(siftProcessor, ocrProcessor);

        List<ROIData> rois = new ArrayList<>();
        rois.add(siftProcessor.getRoi());
        rois.add(ocrProcessor.getRoi());

        mainCaptureService.setRois(ROIData.createContiguousArray(rois));
        log.info("采集处理器配置完成");
    }

    /**
     * 初始化 MapContext 元数据（地图宽高）。
     * 优先从 tiles_meta.json 读取，不存在时回退到解析 PNG 头。
     */
    private void initMapMetadata() throws Exception {
        String mapPath = ResourceConfigContext.getShowMap();
        int imgW, imgH;

        String metaPath = ResourceConfigContext.getTilesDir() + "/tiles_meta.json";
        // 优先从瓦片元数据 JSON 读取尺寸
        try (InputStream metaIn = ResourceUtils.getResourceStream(metaPath)) {
            com.fasterxml.jackson.databind.JsonNode meta =
                    com.luoke.app.utils.JsonUtils.getMapper().readTree(metaIn);
            imgW = meta.get("mapWidth").asInt();
            imgH = meta.get("mapHeight").asInt();
            log.info("地图元数据从 tiles_meta.json 读取: {}x{}", imgW, imgH);
        } catch (Exception metaEx) {
            // 回退：解析 PNG 头获取尺寸
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

    /** 瓦片层级元数据 */
    private record LevelInfo(int level, int cols, int rows, int total) {}

    /**
     * 检查各层级瓦片完整性，缺失的从源 PNG 多线程生成。
     * 通过 tiles_meta.json 元数据快速校验，避免逐层 list 文件。
     */
    private void validateAndGenerateTiles() throws IOException {
        String externalPath = ResourceUtils.getExternalPath(ResourceConfigContext.getShowMap(), false);
        File sourceFile = new File(externalPath);

        int mapW = (int) MapContext.getInstance().getMapWidth();
        int mapH = (int) MapContext.getInstance().getMapHeight();
        int tileSize = 256;

        List<LevelInfo> levels = new ArrayList<>();
        for (int lv = 0; lv < 5; lv++) {
            int cols = (int) Math.ceil((double) mapW / (tileSize * (1 << lv)));
            int rows = (int) Math.ceil((double) mapH / (tileSize * (1 << lv)));
            levels.add(new LevelInfo(lv, cols, rows, cols * rows));
        }
        File metaFile = ResourceUtils.getExternalFile(ResourceConfigContext.getTilesDir() + "/tiles_meta.json");
        if (metaFile.exists() && quickValidate(levels)) {
            log.info("瓦片元数据校验通过，跳过生成");
            return;
        }
        if (!sourceFile.exists()) {
            log.error("源 PNG 不存在: {}", sourceFile.getAbsolutePath());
            return;
        }

        log.info("开始生成瓦片金字塔...");

        // 1. 加载源图一次
        BufferedImage sourceImage = ImageIO.read(sourceFile);
        int srcW = sourceImage.getWidth();
        int srcH = sourceImage.getHeight();

        int threads = Runtime.getRuntime().availableProcessors();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

            for (LevelInfo li : levels) {
                // 2. 对该级别缩放一次
                double factor = 1.0 / (1 << li.level);
                BufferedImage levelImage;
                if (li.level == 0) {
                    levelImage = sourceImage;
                } else {
                    int lw = (int) Math.ceil(srcW * factor);
                    int lh = (int) Math.ceil(srcH * factor);
                    levelImage = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g = levelImage.createGraphics();
                    g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(sourceImage, 0, 0, lw, lh, null);
                    g.dispose();
                }

                File levelDir = ResourceUtils.getExternalFile(
                        ResourceConfigContext.getTilesDir() + "/" + li.level);
                levelDir.mkdirs();

                // 3. 从缩放图裁剪子图，多线程保存
                int tileWorldSize = tileSize * (1 << li.level);
                for (int row = 0; row < li.rows; row++) {
                    for (int col = 0; col < li.cols; col++) {
                        File tileFile = new File(levelDir, row + "_" + col + ".png");
                        if (tileFile.exists()) continue;

                        int x = col * tileSize;
                        int y = row * tileSize;
                        int w = Math.min(tileSize, levelImage.getWidth() - x);
                        int h = Math.min(tileSize, levelImage.getHeight() - y);
                        if (w <= 0 || h <= 0) continue;

                        BufferedImage tile = levelImage.getSubimage(x, y, w, h);
                        futures.add(executor.submit(() -> {
                            try {
                                ImageIO.write(tile, "png", tileFile);
                            } catch (IOException e) {
                                log.warn("瓦片保存失败: {}", tileFile, e);
                            }
                        }));
                    }
                }

                // 每层处理完确保目录存在
                if (li.level > 0) {
                    levelImage.flush();
                }
            }

            for (java.util.concurrent.Future<?> f : futures) {
                try { f.get(); } catch (Exception ignored) {}
            }
        }

        log.info("瓦片生成完成");
        writeMetaFile(metaFile, mapW, mapH, tileSize, levels);
    }

    /** 快速校验：比对元数据中各级别瓦片数与实际文件数 */
    private boolean quickValidate(List<LevelInfo> levels) {
        for (LevelInfo li : levels) {
            File levelDir = ResourceUtils.getExternalFile(Path.of(ResourceConfigContext.getTilesDir(), String.valueOf(li.level)).toString());
            if (!levelDir.isDirectory()) return false;
            int actual = levelDir.list((d, n) -> n.endsWith(".png")).length;
            if (actual < li.total) {
                log.warn("瓦片 Level {} 不完整: {}/{}", li.level, actual, li.total);
                return false;
            }
        }
        return true;
    }

    /** 写入瓦片元数据 JSON */
    private void writeMetaFile(File metaFile, int mapW, int mapH, int tileSize,
                               List<LevelInfo> levels) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"mapWidth\": ").append(mapW).append(",\n");
        sb.append("  \"mapHeight\": ").append(mapH).append(",\n");
        sb.append("  \"tileSize\": ").append(tileSize).append(",\n");
        sb.append("  \"levels\": [\n");
        for (int i = 0; i < levels.size(); i++) {
            LevelInfo li = levels.get(i);
            sb.append("    {\"level\": ").append(li.level)
              .append(", \"cols\": ").append(li.cols)
              .append(", \"rows\": ").append(li.rows)
              .append(", \"total\": ").append(li.total).append("}");
            if (i < levels.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        java.nio.file.Files.writeString(metaFile.toPath(), sb.toString());
        log.info("瓦片元数据已写入: {}", metaFile);
    }

    private void publishInitStep(double progress, String message) {
        HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress, message));
    }

    private Button createMenuButton() {
        Button btn = new Button();
        SVGPath icon = new SVGPath();
        icon.setContent("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
        icon.setStyle("-fx-fill: -color-fg-default;");
        btn.setGraphic(icon);
        btn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);
        return btn;
    }

    @Override
    public void stop() {
        log.info("正在关闭程序...");
        isAppRunning = false;

        HookRegistry.INSTANCE.destroy();

        if (mainCaptureService != null) {
            mainCaptureService.stop();
        }

        if (siftMatchClient != null) {
            siftMatchClient.stop();
        }

        SocketServer.instance().stop();
        OcrAsyncManager.getInstance().close();

        Platform.exit();
    }
}
