package io.github.kedaya0209.roco.app.ui;

import io.github.kedaya0209.roco.app.ui.util.TaskbarIconHelper;
import io.github.kedaya0209.roco.app.utils.EnvironmentUtil;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.config.UpdateConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.ProgressEvent;
import io.github.kedaya0209.roco.app.ui.command.CommandBus;
import io.github.kedaya0209.roco.app.ui.command.CommandHandlers;
import io.github.kedaya0209.roco.app.ui.command.SidebarCommands.SwitchVersionCommand;
import io.github.kedaya0209.roco.app.ui.hook.UiResponseHook;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.ui.state.StateBridge;
import io.github.kedaya0209.roco.app.match.map.SwitchMapMatcher;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.ui.component.overlay.LoadingOverlay;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingsStage;
import io.github.kedaya0209.roco.app.ui.component.sidebar.Sidebar;
import io.github.kedaya0209.roco.app.ui.component.sidebar.UiAnimator;
import io.github.kedaya0209.roco.app.ui.component.widget.TitleBar;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.PluginProcessRegistry;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.CaptureServiceManager;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.InfrastructureManager;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.PcapBridgeManager;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.SiftClientManager;
import io.github.kedaya0209.roco.app.ui.service.resource.ResourceInitService;
import io.github.kedaya0209.roco.app.ui.service.resource.ResourceInitUiDelegate;
import io.github.kedaya0209.roco.app.ui.component.dialog.FirstRunDialog;
import io.github.kedaya0209.roco.app.ui.component.dialog.ModalConfirmDialog;
import io.github.kedaya0209.roco.app.ui.service.ui.MainUiComposer;
import io.github.kedaya0209.roco.app.ui.service.ui.ThemeManager;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.service.ui.WindowManager;
import io.github.kedaya0209.roco.app.ui.service.ui.AppUpdateUiHandler;
import io.github.kedaya0209.roco.app.ui.service.ui.PluginUpdateHandler;
import io.github.kedaya0209.roco.app.ui.util.TrayManager;
import io.github.kedaya0209.roco.app.update.UpdateManager;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import io.github.kedaya0209.roco.app.utils.ResourceExtractor;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import lombok.extern.slf4j.Slf4j;

/**
 * 主 Application — 编排器角色。
 * <p>
 * 职责边界：仅处理 Application 生命周期（start/stop）和场景图骨架创建。
 * 具体业务逻辑委托给以下 service 类：
 * <ul>
 *   <li>{@link ResourceInitService} — 资源初始化编排</li>
 *   <li>{@link MainUiComposer} — 主界面 UI 组装</li>
 *   <li>{@link InfrastructureManager} — JobObject/SocketServer 生命周期</li>
 *   <li>{@link SiftClientManager} — SIFT 客户端生命周期</li>
 *   <li>{@link CaptureServiceManager} — 截图服务生命周期</li>
 *   <li>{@link PcapBridgeManager} — pcap 桥接器生命周期</li>
 * </ul>
 */
@NotThreadSafe
@Slf4j
public class ModernCanvasApp extends Application {

    private final WindowManager windowManager = new WindowManager(UiConfig.RESIZE_MARGIN);
    private final UiAnimator uiAnimator = new UiAnimator();
    private final SiftClientManager siftClientManager = new SiftClientManager();
    private final CaptureServiceManager captureServiceManager = new CaptureServiceManager();
    private final PcapBridgeManager pcapBridgeManager = new PcapBridgeManager();
    private static HostServices appHostServices;
    private TrayManager trayManager;
    private StackPane rootStack;
    private Stage primaryStage;

    public static HostServices hostServices() {
        return appHostServices;
    }

    @Override
    public void start(Stage primaryStage) {
        ConfigPersistence.init(); // 配置加载必须在所有 Config 字段读取之前
        ConfigPersistence.setOnConfigLoaded(() -> Platform.runLater(() -> AppState.getInstance().reloadFromConfig()));
        appHostServices = getHostServices();
        this.primaryStage = primaryStage;
        this.trayManager = new TrayManager(primaryStage);

        // ---- 1. 主题（必须在场景创建前，否则 CSS 变量不可用）----
        ThemeManager.applyTheme(UiConfig.THEME);

        // ---- 2. 场景骨架 ----
        initScene();

        primaryStage.setTitle(CaptureConfig.APP_MAIN_TITLE);

        // Native Image 环境下 StageStyle.TRANSPARENT 窗口不会自动获得焦点，
        // 首次点击只能激活窗口而按钮无法响应，配合场景事件过滤器确保焦点即时激活
        primaryStage.setOnShown(_ -> {
            primaryStage.requestFocus();
            primaryStage.toFront();
        });
        primaryStage.show();

        // 提取 .ico 图标文件并立即设置任务栏图标（尽早设置，避免窗口出现时无图标）
        initTaskbarIcon();

        // ---- 3. 后台释放资源 → 基础设施 → 主界面构建 ----
        startBackgroundInit();
    }

    /**
     * 后台线程释放内嵌资源，完成后回到 FX 线程继续初始化。
     */
    private void startBackgroundInit() {
        Thread.ofPlatform().daemon(true).name("resource-extractor").start(() -> {
            // 预加载插件管理器，WatchService + 后台扫描提前就绪
            PluginUpdateManager.getInstance();

            ResourceExtractor.extractAll((total, done) -> {
                double progress = 0.15 * done / total;
                String text = String.format("正在校验&释放内嵌资源 (%d/%d)...", done, total);
                Platform.runLater(() -> AppEvents.publish(
                        ProgressEvent.class, new ProgressEvent(progress, text)));
            });
            Platform.runLater(this::initAfterResourcesReady);
        });
    }

    /**
     * 内嵌资源就绪后，在 FX 线程上完成基础设施、SIFT 回调和主界面构建。
     */
    private void initAfterResourcesReady() {
        InfrastructureManager.init();

        SwitchMapMatcher.getInstance().setSwitchCallback(newVariant -> {
            log.info("算法变体切换: {}", newVariant);
            siftClientManager.restartClient(newVariant);
        });
        SwitchMapMatcher.getInstance().setAlgoKindCallback(_ -> log.info("算法类型固定为 SIFT"));

        ResourceInitService initService = getResourceInitService();
        initService.start(this::buildMainUi);
    }

    private ResourceInitService getResourceInitService() {
        ResourceInitUiDelegate uiDelegate = new ResourceInitUiDelegate() {
            @Override
            public void showFirstRunDialog(Runnable onDownload, Runnable onBuiltIn, Runnable onExit) {
                FirstRunDialog.showFirstRunDialog(
                        rootStack, "资源准备", "本地资源未准备好，选择启动方式：",
                        onDownload, onBuiltIn, onExit);
            }

            @Override
            public void showDownloadOverlay(Runnable onCancel) {
                rootStack.getChildren().stream()
                        .filter(n -> n instanceof LoadingOverlay)
                        .forEach(n -> ((LoadingOverlay) n).dispose());
                rootStack.getChildren().removeIf(n -> n instanceof LoadingOverlay);
                rootStack.getChildren().add(new LoadingOverlay(onCancel));
            }

            @Override
            public void removeDownloadOverlay() {
                rootStack.getChildren().removeIf(n -> n instanceof LoadingOverlay);
            }

            @Override
            public void onResourceReady(Runnable runnable) {
                Platform.runLater(() -> {
                    rootStack.getChildren().removeIf(n -> n instanceof LoadingOverlay);
                    runnable.run();
                });
            }

            @Override
            public void onInitFailed(String message) {
                Platform.runLater(() -> {
                    rootStack.getChildren().removeIf(n -> n instanceof LoadingOverlay);
                    ModalConfirmDialog.showModalConfirmDialog(
                            primaryStage,
                            "初始化失败",
                            message,
                            "退出",
                            Platform::exit,
                            Platform::exit
                    );
                });
            }
        };

        return new ResourceInitService(uiDelegate);
    }

    /**
     * 初始化场景骨架：透明圆角窗口 + 根容器 + 全局加载遮罩。
     */
    private void initScene() {
        StackPane wrapper = new StackPane();
        wrapper.setBackground(Background.EMPTY);

        rootStack = new StackPane();
        rootStack.setStyle("-fx-background-color: -color-bg-default; -fx-background-radius: 12px;");
        Rectangle rootClip = new Rectangle();
        rootClip.widthProperty().bind(rootStack.widthProperty());
        rootClip.heightProperty().bind(rootStack.heightProperty());
        rootClip.setArcWidth(24);
        rootClip.setArcHeight(24);
        rootStack.setClip(rootClip);

        // wrapper 也需 clip，防止圆角外露出黑色底色
        Rectangle wrapperClip = new Rectangle();
        wrapperClip.widthProperty().bind(wrapper.widthProperty());
        wrapperClip.heightProperty().bind(wrapper.heightProperty());
        wrapperClip.setArcWidth(24);
        wrapperClip.setArcHeight(24);
        wrapper.setClip(wrapperClip);

        wrapper.getChildren().add(rootStack);

        LoadingOverlay globalLoading = new LoadingOverlay(null);
        rootStack.getChildren().add(globalLoading);
        new UiResponseHook(rootStack, globalLoading);

        Scene scene = new Scene(wrapper, ViewConfig.INITIAL_WINDOW_WIDTH, ViewConfig.INITIAL_WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        // 确保 inline style 中的 CSS 变量（如 -color-bg-default）能被正确解析
        String css = ThemeManager.getCurrentStylesheetUrl();
        if (css != null) {
            scene.getStylesheets().add(css);
        }
        // 加载全局 UI 增强样式表
        URL uiCss = getClass().getResource("/styles/ui.css");
        if (uiCss != null) {
            scene.getStylesheets().add(uiCss.toExternalForm());
        }
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(scene);

        // 修复 StageStyle.TRANSPARENT 窗口需要双击才能响应按钮的问题
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, _ -> {
            if (!primaryStage.isFocused()) {
                primaryStage.requestFocus();
            }
        });
    }

    /**
     * 提取 .ico 图标到外部目录并通过 Win32 API 设置任务栏图标。
     * 在 primaryStage.show() 后立即调用，确保窗口出现时即有图标。
     */
    private void initTaskbarIcon() {
        try {
            File iconFile = FilePathUtil.getExternalFile("icon", "/rmt.ico");
            if (!iconFile.exists()) {
                File appDir = FilePathUtil.getAppRootDir().toFile();
                iconFile = new File(appDir, "rmt.ico");
                if (!iconFile.exists()) {
                    try (InputStream is = ResourceUtils.getResourceStream(PathConfig.ICON_ICO)) {
                        Files.copy(is, iconFile.toPath(),
                                StandardCopyOption.REPLACE_EXISTING);
                        log.info("ico 图标已释放到: {}", iconFile.getAbsolutePath());
                    }
                }
            }
            if (iconFile.exists()) {
                String iconFilePath = iconFile.getAbsolutePath();
                TaskbarIconHelper.setIcon(primaryStage, iconFilePath);
            } else {
                log.warn("ico 图标文件不存在");
            }
        } catch (Exception e) {
            log.warn("ico 图标处理失败", e);
        }
    }

    /**
     * 主界面构建：UI 组装 → 核心服务初始化 → 渲染循环启动。
     * 由 ResourceInitService 资源就绪后回调。
     */
    private void buildMainUi() {
        StateBridge.init();
        CommandHandlers.init(rootStack, pcapBridgeManager);
        MainUiComposer.UiBuildResult result = MainUiComposer.buildMainUI(primaryStage, rootStack, windowManager, uiAnimator);

        siftClientManager.init();
        captureServiceManager.init(siftClientManager.getClient());

        // 注册内置插件 PID 以便 CPU/内存监控
        PluginProcessRegistry.register("capture", captureServiceManager.getProcessPid());
        PluginProcessRegistry.register("sift", siftClientManager.getClient().getActiveProcessPid());

        // 预初始化设置面板，避免首次点击时 FX 线程阻塞导致卡顿
        SettingsStage.getInstance();

        // 版本切换回调
        VersionManager.getInstance().setOnSwitch(mode ->
                CommandBus.dispatch(new SwitchVersionCommand(mode)));

        result.renderer().start();
        Sidebar sidebar = result.sidebar();

        // 设置更新 UI 回调
        UpdateManager.getInstance().setUiDelegate(new AppUpdateUiHandler(rootStack, sidebar));

        // 启动定时更新检查（仅 Native Image 环境）
        if (EnvironmentUtil.isNative() && UpdateConfig.CHECK_ENABLED) {
            UpdateManager.getInstance().startPeriodicCheck(UpdateConfig.CHECK_INTERVAL_HOURS);
        }

        // 插件更新管理器
        PluginUpdateManager pm = PluginUpdateManager.getInstance();
        pm.setUiDelegate(new PluginUpdateHandler(rootStack));
        pm.checkAllPlugins(true);

        // UI 完全就绪后补设任务栏图标（start() 阶段 HWND 可能未就绪）
        initTaskbarIcon();

        // 初始化系统托盘并绑定最小化按钮
        trayManager.init();
        TitleBar.getInstance().setMinimizeHandler(trayManager::minimizeToTray);

        log.info("主界面构建完成");
    }



    @Override
    public void stop() {
        log.info("正在关闭程序...");

        trayManager.dispose();
        UpdateManager.getInstance().shutdown();
        captureServiceManager.stop();
        pcapBridgeManager.stop();
        siftClientManager.stop();
        InfrastructureManager.destroy();
        SocketServer.instance().stop();

        Platform.exit();
    }
}
