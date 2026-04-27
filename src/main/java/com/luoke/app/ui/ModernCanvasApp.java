package com.luoke.app.ui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.Styles;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.MapContext;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.context.ResourcePointContext;
import com.luoke.app.hook.HookRegistry;
import com.luoke.app.hook.impl.RealOcrHook;
import com.luoke.app.hook.impl.ResourceGrayHook;
import com.luoke.app.hook.multicast.HookMulticaster;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.core.DownloadProgressContext;
import com.luoke.app.map.core.IconDownloader;
import com.luoke.app.map.core.MapDownloader;
import com.luoke.app.processor.CoreProcessor;
import com.luoke.app.ui.component.*;
import com.luoke.app.ui.render.PlayerRenderer;
import com.luoke.app.ui.render.RenderLoop;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.WindowManager;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.InputStream;

@Slf4j
@NoArgsConstructor
public class ModernCanvasApp extends Application {

    private static final int RESIZE_MARGIN = 8;
    private static final String UNIFIED_BLUE = "#00BFFF";

    private static StackPane rootStack;
    private final WindowManager windowManager = new WindowManager(RESIZE_MARGIN);

    private RenderLoop renderLoop;
    private final UiAnimator uiAnimator = new UiAnimator();
    private final CoreProcessor coreProcessor = CoreProcessor.getInstance();
    private LoadingOverlay globalLoading;

    public static void main(String[] args) {
        launch(args);
    }

    public static void notify(String message, NotificationToast.Type type) {
        if (rootStack != null) {
            Platform.runLater(() -> NotificationToast.show(rootStack, message, type));
        }
    }

    @Override
    public void init() throws Exception {
        super.init();
        ResourceUtils.extractAll();
    }

    @Override
    public void start(Stage primaryStage) {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: #1e1e1e;");

        globalLoading = new LoadingOverlay(null);
        globalLoading.updateProgress(0.1, "正在检查运行环境...");
        rootStack.getChildren().add(globalLoading);

        Scene scene = new Scene(rootStack, 1100, 800);
        scene.setFill(Color.TRANSPARENT);

        primaryStage.initStyle(StageStyle.UNDECORATED);
        primaryStage.setScene(scene);
        primaryStage.show();

        checkAndInitResourcesAsync(primaryStage);
    }

    private void checkAndInitResourcesAsync(Stage primaryStage) {
        Thread.ofVirtual().start(() -> {
            try {
                File rootDir = ResourceUtils.getExternalFile(AppConfig.SOURCE_ROOT_DIR);
                if (!rootDir.exists()) rootDir.mkdirs();

                File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
                if (initFile.exists()) {
                    updateLoadingProgress(0.3, "正在初始化钩子组件...");
                    registerHook();
                    updateLoadingProgress(0.5, "正在载入大地图资源...");
                    initBigMapResource();
                    updateLoadingProgress(0.8, "正在载入坐标索引...");
                    ResourcePointContext.getInstance().loadAndInit();
                    updateLoadingProgress(1.0, "准备就绪");
                    Platform.runLater(() -> buildMainUI(primaryStage));
                } else {
                    Platform.runLater(() -> {
                        rootStack.getChildren().remove(globalLoading);
                        DialogUtils.showSimpleDialog(rootStack, "首次启动", "程序需要下载必要的地图资源。", "开始下载", false,
                                () -> startResourceDownloadAsync(primaryStage, initFile));
                    });
                }
            } catch (Exception e) {
                log.error("资源自检异常", e);
                showErrorAndExit("环境检查失败，请检查文件权限。");
            }
        });
    }

    private void updateLoadingProgress(double progress, String text) {
        Platform.runLater(() -> {
            if (globalLoading != null) globalLoading.updateProgress(progress, text);
        });
    }

    private void startResourceDownloadAsync(Stage primaryStage, File initFile) {
        Platform.runLater(() -> {
            rootStack.getChildren().clear();
            LoadingOverlay downloadOverlay = new LoadingOverlay(() -> {
                MapDownloader.stopDownload();
                IconDownloader.stopDownload();
                Platform.runLater(() -> checkAndInitResourcesAsync(primaryStage));
            });
            rootStack.getChildren().add(downloadOverlay);

            DownloadProgressContext.getInstance().setOnProgressUpdate((completed, total) -> {
                Platform.runLater(() -> {
                    double progress = total == 0 ? 0 : (double) completed / total;
                    downloadOverlay.updateProgress(progress, String.format("%s (%d/%d)",
                            DownloadProgressContext.getInstance().getStatusText(), completed, total));
                });
            });
        });

        Thread.ofVirtual().start(() -> {
            try {
                MapResourceUpdater.updateAllResources();
                initFile.createNewFile();
                registerHook();
                initBigMapResource();
                ResourcePointContext.getInstance().loadAndInit();
                Platform.runLater(() -> buildMainUI(primaryStage));
            } catch (Exception e) {
                log.error("资源同步失败", e);
                showErrorAndExit("资源下载流程中断。");
            }
        });
    }

    private void buildMainUI(Stage primaryStage) {
        try {
            rootStack.getChildren().clear();

            // =========================
            // Canvas (全屏绘制)
            // =========================
            InteractiveCanvas interactiveCanvas = new InteractiveCanvas();
            interactiveCanvas.setPickOnBounds(true);

            StackPane canvasContainer = new StackPane(interactiveCanvas);
            canvasContainer.setPickOnBounds(false);
            StackPane.setAlignment(interactiveCanvas, Pos.CENTER);

            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(canvasContainer.widthProperty());
            clip.heightProperty().bind(canvasContainer.heightProperty());
            canvasContainer.setClip(clip);

            interactiveCanvas.widthProperty().bind(canvasContainer.widthProperty());
            interactiveCanvas.heightProperty().bind(canvasContainer.heightProperty());

            // =========================
            // Sidebar / UI
            // =========================
            Sidebar sidebar = new Sidebar();
            sidebar.setTranslateX(-220);
            AnchorPane sidebarContainer = new AnchorPane(sidebar);
            sidebarContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(sidebar, 45.0);
            AnchorPane.setBottomAnchor(sidebar, 0.0);

            ResourceCounterPanel resourcePanel = ResourceCounterPanel.getInstance();
            AnchorPane panelAnchor = new AnchorPane(resourcePanel);
            panelAnchor.setPickOnBounds(false);
            AnchorPane.setTopAnchor(resourcePanel, 80.0);
            AnchorPane.setRightAnchor(resourcePanel, 20.0);

            FloatToolbox floatToolbox = new FloatToolbox(resourcePanel, UNIFIED_BLUE);
            AnchorPane floatContainer = new AnchorPane(floatToolbox);
            floatContainer.setPickOnBounds(false);
            AnchorPane.setTopAnchor(floatToolbox, 80.0);
            AnchorPane.setLeftAnchor(floatToolbox, 15.0);

            Button menuBtn = createMenuButton();
            TitleBar titleBar = TitleBar.getInstance(primaryStage, menuBtn, UNIFIED_BLUE,
                    canvasContainer, sidebarContainer, panelAnchor, floatContainer);

            VBox uiOverlay = new VBox(titleBar);
            uiOverlay.setPickOnBounds(false);

            // =========================
            // 🔥 关键：透明 Resize 层
            // =========================
            AnchorPane resizeLayer = new AnchorPane();
            resizeLayer.setPickOnBounds(false);
            resizeLayer.setMouseTransparent(false);
            windowManager.install(primaryStage, resizeLayer);

            // =========================
            // 层级装载
            // =========================
            rootStack.getChildren().addAll(
                    canvasContainer,
                    sidebarContainer,
                    panelAnchor,
                    floatContainer,
                    uiOverlay,
                    resizeLayer // 放在最上层以保证捕获鼠标
            );

            uiAnimator.setupSidebarToggle(menuBtn, sidebar, floatContainer);
            interactiveCanvas.addEventHandler(MouseEvent.MOUSE_CLICKED, e -> {
                if (e.getButton() == MouseButton.PRIMARY && uiAnimator.isSidebarVisible()) menuBtn.fire();
            });

            renderLoop = new RenderLoop(interactiveCanvas.getGraphicsContext2D());
            renderLoop.start();

            coreProcessor.setStatusUpdateHandler(this::updateStatus);
            coreProcessor.preloadMatcherAsync();

        } catch (Exception e) {
            log.error("UI 构建逻辑失败: ", e);
            showErrorAndExit("界面挂载异常。");
        }
    }

    private void updateStatus(String msg, Color color) {
    }

    private Button createMenuButton() {
        Button btn = new Button();
        SVGPath icon = new SVGPath();
        icon.setContent("M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z");
        icon.setFill(Color.WHITE);
        btn.setGraphic(icon);
        btn.getStyleClass().addAll(Styles.BUTTON_CIRCLE, Styles.FLAT);
        return btn;
    }

    private void registerHook() {
        HookRegistry.INSTANCE.registers(new ResourceGrayHook(), new RealOcrHook());
    }

    private void initBigMapResource() throws Exception {
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            MapContext.getInstance().initWithKey(rawImage, rawImage.getWidth(), rawImage.getHeight(), "G");
        }
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.PLAYER_ICON_PATH)) {
            PlayerRenderer.getInstance().initIcon(is);
        }
    }

    private void showErrorAndExit(String msg) {
        Platform.runLater(() -> {
            DialogUtils.showSimpleDialog(rootStack, "严重错误", msg, "退出", true, () -> {
                Platform.exit();
                System.exit(0);
            });
        });
    }

    @Override
    public void stop() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(500);
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignored) {
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            HookMulticaster.getInstance().shutdown();
            coreProcessor.shutdown();
            if (renderLoop != null) renderLoop.stop();
            OcrAsyncManager.getInstance().close();
            Platform.exit();
            System.exit(0);
        } catch (Exception e) {
            Runtime.getRuntime().halt(1);
        }
    }
}