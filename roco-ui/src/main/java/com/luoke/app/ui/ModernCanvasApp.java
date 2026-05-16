package com.luoke.app.ui;

import com.luoke.app.capture.CaptureService;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.MapMatcherProcessor;
import com.luoke.app.capture.processor.OcrProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.ProgressEvent;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.impl.UiResponseHook;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.macher.SiftVariant;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.map.model.ResourcePoint;
import com.luoke.app.process.JobObjectManager;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.IconCache;
import com.luoke.app.ui.render.MapRenderer;
import com.luoke.app.ui.render.TileGeneratorService;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.ThemeManager;
import com.luoke.app.ui.util.WindowManager;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@NoArgsConstructor
public class ModernCanvasApp extends Application {

    private static final int RESIZE_MARGIN = 8;
    private static final String UNIFIED_BLUE = "#00BFFF";

    private final TileGeneratorService tileGeneratorService = new TileGeneratorService();

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

    @Override
    public void start(Stage primaryStage) {

        checkAndInitResourcesAsync(primaryStage);

        JobObjectManager.init();

        try {
            int port = SocketServer.instance().start();
            log.info("SocketServer 已启动, 端口: {}", port);
        } catch (Exception e) {
            log.error("SocketServer 启动失败", e);
        }

        SwitchMapMatcher.getInstance().setSwitchCallback(newVariant -> {
            log.info("算法变体切换: {}", newVariant);
            if (siftMatchClient != null) {
                siftMatchClient.restart(SiftVariant.variantOrdinal(newVariant));
            }
        });

        ThemeManager.applyTheme(AppConfig.THEME);

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

        // 设置程序图标
        try {
            Image icon = loadSvgIcon(AppConfig.ICON, 256);
            if (icon != null) {
                primaryStage.getIcons().add(icon);
            }
        } catch (Exception e) {
            log.warn("程序图标加载失败", e);
        }

        primaryStage.show();

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
                    tileGeneratorService.validateAndGenerateTiles();
                }

                publishInitStep(0.7, "构建坐标索引系统...");
                ResourcePointContext.getInstance().loadAndInit();

                publishInitStep(0.85, "合并图标纹理图集...");
                buildIconAtlas();

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
    }

    private void handleFirstRun(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            globalLoading.dispose();
            rootStack.getChildren().remove(globalLoading);
            DialogUtils.showFirstRunDialog(rootStack, "初始化配置",
                    "检测到本地资源不完整，请选择启动方式：",
                    () -> startResourceDownloadAsync(primaryStage, initFile),
                    () -> startWithBuiltInResources(primaryStage, initFile),
                    () -> Platform.exit());
        });
    }

    private void startWithBuiltInResources(Stage primaryStage, File initFile) {
        try {
            if (initFile.createNewFile()) {
                log.info("选择内置资源模式，标记初始化完成");
            }
            // 后台静默下载 WIKI 资源
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("后台开始下载 WIKI 资源...");
                    MapResourceUpdater.updateAllResources();
                    log.info("后台资源下载完成");
                } catch (Exception e) {
                    log.warn("后台资源下载异常（可忽略，下次启动会重试）", e);
                }
            });
            // 使用内置资源直接进入主界面
            checkAndInitResourcesAsync(primaryStage);
        } catch (Exception e) {
            log.error("内置资源模式启动失败", e);
        }
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

            // 视口大小变化 → 标记脏（重绘 + 瓦片更新即可，不需 autoFitViewport 改缩放）
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
            initSiftMatchClient();
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

    /** 收集所有资源点图标路径，构建纹理图集 */
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
            // 图集就绪后释放单图标缓存和原始字节缓存
            IconCache.getInstance().clearIndividualCaches();
            com.luoke.app.map.loader.ImageLoader.getInstance().clearCache();
            log.info("图标纹理图集已构建: {} 个图标, 已释放单图标缓存", iconPaths.size());
        }
    }

    private void publishInitStep(double progress, String message) {
        HookRegistry.INSTANCE.publish(HookEventType.INIT_PROGRESS, new ProgressEvent(progress, message));
    }

    private Button createMenuButton() {
        Button btn = new Button();
        try {
            Group svgGroup = loadSvgGroup("/icon/rmt.svg", 20);
            StackPane graphic = new StackPane(svgGroup);
            graphic.setPrefSize(20, 20);
            graphic.setMinSize(20, 20);
            graphic.setMaxSize(20, 20);
            btn.setGraphic(graphic);
            btn.setEffect(new DropShadow(3, 1, 1, Color.web("#000000", 0.25)));
        } catch (Exception e) {
            log.warn("菜单按钮 SVG 加载失败", e);
            SVGPath fallback = new SVGPath();
            fallback.setContent("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
            fallback.setStyle("-fx-fill: -color-fg-default;");
            btn.setGraphic(fallback);
        }
        String baseStyle =
                "-fx-background-color: transparent;" +
                "-fx-border-color: transparent;" +
                "-fx-padding: 6px;" +
                "-fx-cursor: hand;";
        btn.setStyle(baseStyle);
        btn.setOnMouseEntered(e -> btn.setStyle(
                baseStyle +
                "-fx-background-color: -color-bg-subtle;" +
                "-fx-background-radius: 6px;"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(baseStyle));
        return btn;
    }

    /**
     * 将 SVG 文件解析为 JavaFX Image（用于程序图标）。
     * SVG 路径数据由 javafx.scene.shape.SVGPath 解析，Group.snapshot() 渲染。
     */
    private static Image loadSvgIcon(String resourcePath, double size) {
        try {
            Group group = loadSvgGroup(resourcePath, size);
            SnapshotParameters sp = new SnapshotParameters();
            sp.setFill(Color.TRANSPARENT);
            WritableImage img = new WritableImage((int) size, (int) size);
            return group.snapshot(sp, img);
        } catch (Exception e) {
            log.warn("SVG 图标加载失败: {}", resourcePath, e);
            return null;
        }
    }

    /**
     * 将 SVG 文件解析为 SVGPath 节点组（矢量，适合直接用作按钮图形）。
     * 返回的 Group 已经过缩放居中变换，尺寸为 size × size。
     */
    public static Group loadSvgGroup(String resourcePath, double size) throws Exception {
        try (InputStream in = ResourceUtils.getResourceStream(resourcePath)) {

            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            Document doc = dbf.newDocumentBuilder().parse(in);
            Element svgRoot = doc.getDocumentElement();

            NodeList pathNodes = svgRoot.getElementsByTagName("path");
            if (pathNodes.getLength() == 0) throw new IllegalArgumentException("SVG 中没有 <path> 元素");

            // 创建 SVGPath 节点，提取路径数据
            int n = pathNodes.getLength();
            SVGPath[] paths = new javafx.scene.shape.SVGPath[n];
            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
            double maxX = Double.MIN_VALUE, maxY = Double.MIN_VALUE;
            for (int i = 0; i < n; i++) {
                Element el = (Element) pathNodes.item(i);
                javafx.scene.shape.SVGPath sp = new javafx.scene.shape.SVGPath();
                sp.setContent(el.getAttribute("d"));
                String fill = el.getAttribute("fill");
                if (!fill.isEmpty() && !"none".equals(fill)) {
                    String fillOpacityStr = el.getAttribute("fill-opacity");
                    double fillOpacity = 1.0;
                    if (!fillOpacityStr.isEmpty()) {
                        fillOpacity = Double.parseDouble(fillOpacityStr);
                    }
                    sp.setFill(Color.web(fill, fillOpacity));
                }
                paths[i] = sp;
                javafx.geometry.Bounds b = sp.getBoundsInLocal();
                minX = Math.min(minX, b.getMinX());
                minY = Math.min(minY, b.getMinY());
                maxX = Math.max(maxX, b.getMaxX());
                maxY = Math.max(maxY, b.getMaxY());
            }

            // 居中并缩放到目标尺寸
            double pw = maxX - minX;
            double ph = maxY - minY;
            double scale = size / Math.max(pw, ph);

            Group group = new Group();
            double tx = -minX * scale + (size - pw * scale) / 2;
            double ty = -minY * scale + (size - ph * scale) / 2;
            for (SVGPath path : paths) {
                path.getTransforms().add(new javafx.scene.transform.Scale(scale, scale));
                path.getTransforms().add(new javafx.scene.transform.Translate(tx, ty));
            }
            group.getChildren().addAll(paths);
            return group;
        }
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
