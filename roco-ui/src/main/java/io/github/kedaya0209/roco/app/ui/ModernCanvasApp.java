package io.github.kedaya0209.roco.app.ui;

import io.github.kedaya0209.roco.app.ui.util.TaskbarIconHelper;
import io.github.kedaya0209.roco.app.utils.EnvironmentUtil;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.BuildConfig;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.SnifferConfig;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.config.UiConfig;
import io.github.kedaya0209.roco.app.config.UpdateConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.ProgressEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.hook.UiResponseHook;
import io.github.kedaya0209.roco.app.hook.multicast.HookRegistry;
import io.github.kedaya0209.roco.app.match.map.SwitchMapMatcher;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.ui.component.FloatToolbox;
import io.github.kedaya0209.roco.app.ui.component.LoadingOverlay;
import io.github.kedaya0209.roco.app.ui.component.ResourceCounterPanel;
import io.github.kedaya0209.roco.app.ui.component.Sidebar;
import io.github.kedaya0209.roco.app.ui.component.TitleBar;
import io.github.kedaya0209.roco.app.ui.component.UiAnimator;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.CaptureServiceManager;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.InfrastructureManager;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.PcapBridgeManager;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.SiftClientManager;
import io.github.kedaya0209.roco.app.ui.service.resource.ResourceInitService;
import io.github.kedaya0209.roco.app.ui.service.resource.ResourceInitUiDelegate;
import io.github.kedaya0209.roco.app.ui.service.ui.MainUiComposer;
import io.github.kedaya0209.roco.app.ui.service.ui.ThemeManager;
import io.github.kedaya0209.roco.app.ui.service.ui.VersionManager;
import io.github.kedaya0209.roco.app.ui.service.ui.WindowManager;
import io.github.kedaya0209.roco.app.ui.util.DialogUtils;
import io.github.kedaya0209.roco.app.ui.util.TrayManager;
import io.github.kedaya0209.roco.app.update.UpdateManager;
import io.github.kedaya0209.roco.app.update.UpdateUiDelegate;
import io.github.kedaya0209.roco.app.update.VersionInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginSource;
import io.github.kedaya0209.roco.app.update.plugin.PluginStatus;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateInfo;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateUiDelegate;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.nio.file.StandardCopyOption;
import io.github.kedaya0209.roco.app.utils.ResourceExtractor;

import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.Scene;
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
    private Sidebar sidebar;

    public static void main(String[] args) {
        launch(args);
    }

    public static HostServices hostServices() {
        return appHostServices;
    }

    @Override
    public void start(Stage primaryStage) {
        ConfigPersistence.init(); // 配置加载必须在所有 Config 字段读取之前
        appHostServices = getHostServices();
        this.primaryStage = primaryStage;
        this.trayManager = new TrayManager(primaryStage);

        // ---- 1. 主题（必须在场景创建前，否则 CSS 变量不可用）----
        ThemeManager.applyTheme(UiConfig.THEME);

        // ---- 2. 场景骨架 ----
        initScene();

        primaryStage.setTitle(CaptureConfig.APP_MAIN_TITLE);
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
                Platform.runLater(() -> HookRegistry.INSTANCE.publish(
                        HookEventType.INIT_PROGRESS, new ProgressEvent(progress, text)));
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
        SwitchMapMatcher.getInstance().setAlgoKindCallback(newAlgoKind -> {
            log.info("算法类型固定为 SIFT");
        });

        ResourceInitService initService = getResourceInitService();
        initService.start(this::buildMainUi);
    }

    private ResourceInitService getResourceInitService() {
        ResourceInitUiDelegate uiDelegate = new ResourceInitUiDelegate() {
            @Override
            public void showFirstRunDialog(Runnable onDownload, Runnable onBuiltIn, Runnable onExit) {
                DialogUtils.showFirstRunDialog(
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
                Platform.runLater(runnable);
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
        HookRegistry.INSTANCE.register(new UiResponseHook(rootStack, globalLoading));

        Scene scene = new Scene(wrapper, ViewConfig.INITIAL_WINDOW_WIDTH, ViewConfig.INITIAL_WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        // 确保 inline style 中的 CSS 变量（如 -color-bg-default）能被正确解析
        String css = ThemeManager.getCurrentStylesheetUrl();
        if (css != null) {
            scene.getStylesheets().add(css);
        }
        // 加载全局 UI 增强样式表
        scene.getStylesheets().add(getClass().getResource("/styles/ui.css").toExternalForm());
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(scene);
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
        MainUiComposer.UiBuildResult result = MainUiComposer.buildMainUI(primaryStage, rootStack, windowManager, uiAnimator);

        siftClientManager.init();
        captureServiceManager.init(siftClientManager.getClient());

        FloatToolbox floatToolbox = result.floatToolbox();

        // 版本切换回调 — 控制 pcap/物资面板/浮动按钮/匹配
        VersionManager.getInstance().setOnSwitch(mode -> {
            if (mode == VersionMode.ADVANCED) {
                int port = SocketServer.instance().getPort();
                PluginUpdateManager pm = PluginUpdateManager.getInstance();

                // 确保 sniffer 插件已安装
                pm.scanPlugins();
                boolean snifferReady = pm.getPlugin("sniffer")
                        .filter(p -> p.status() != PluginStatus.DAMAGED
                                && p.status() != PluginStatus.DISABLED)
                        .isPresent();

                if (snifferReady) {
                    pcapBridgeManager.init(port, null);
                } else {
                    // 后台自动下载安装
                    var pc = DialogUtils.showDownloadProgressDialog(
                            rootStack, "需要下载高级版组件 (sniffer)...", null);
                    Thread.ofPlatform().daemon(true).name("sniffer-install").start(() -> {
                        pm.checkRemotePlugin("sniffer",
                                new PluginSource("github-release", SnifferConfig.SNIFFER_REPO))
                            .ifPresentOrElse(update ->
                                    pm.downloadPlugin(update,
                                        prog -> pc.updateProgress(prog, "下载中..."),
                                        () -> {
                                            pc.close();
                                            pm.scanPlugins();
                                            pcapBridgeManager.init(port, null);
                                        },
                                        err -> {
                                            pc.close();
                                            Platform.runLater(() ->
                                                    DialogUtils.showSimpleDialog(rootStack,
                                                            "安装失败",
                                                            "sniffer 插件安装失败: " + err,
                                                            "确定", true, () -> {}));
                                        }),
                                    () -> {
                                        pc.close();
                                        Platform.runLater(() ->
                                                DialogUtils.showSimpleDialog(rootStack,
                                                        "安装失败",
                                                        "无法获取 sniffer 插件信息，请检查网络连接",
                                                        "确定", true, () -> {}));
                                    });
                    });
                }

                ResourceCounterPanel.getInstance().toggle(false);
                floatToolbox.setCollectButtonVisible(true);
                SiftConfig.SIFT_MATCHING_ENABLED = true;
                Platform.runLater(() ->
                        DialogUtils.showSimpleDialog(rootStack, "提示",
                                "高级版组件 (sniffer) 已就绪，请手动断开游戏网络连接后重连，以便抓包组件捕获通信密钥。", "确定", true, () -> {}));

                // 切换到高级模式时检查插件更新
                pm.checkAllPlugins(true);
            } else {
                pcapBridgeManager.stop();
                ResourceCounterPanel.getInstance().toggle(false);
                floatToolbox.setCollectButtonVisible(false);
                SiftConfig.SIFT_MATCHING_ENABLED = false;
            }
        });

        result.renderer().start();
        this.sidebar = result.sidebar();

        // 设置更新 UI 回调
        UpdateManager.getInstance().setUiDelegate(createUpdateUiDelegate());

        // 启动定时更新检查（仅 Native Image 环境）
        if (EnvironmentUtil.isNative() && UpdateConfig.CHECK_ENABLED) {
            UpdateManager.getInstance().startPeriodicCheck(UpdateConfig.CHECK_INTERVAL_HOURS);
        }

        // 插件更新管理器
        PluginUpdateManager pm = PluginUpdateManager.getInstance();
        pm.setUiDelegate(createPluginUpdateUiDelegate());
        pm.checkAllPlugins(true);

        // 插件启用/禁用与 NativeProcess 联动
        pm.setOnPluginDisabled(id -> {
            if ("sniffer".equals(id)) {
                pcapBridgeManager.stop();
            }
        });
        pm.setOnPluginEnabled(id -> {
            if ("sniffer".equals(id) && VersionManager.getInstance().getCurrentMode() == VersionMode.ADVANCED) {
                pcapBridgeManager.init(SocketServer.instance().getPort(), null);
            }
        });

        // UI 完全就绪后补设任务栏图标（start() 阶段 HWND 可能未就绪）
        initTaskbarIcon();

        // 初始化系统托盘并绑定最小化按钮
        trayManager.init();
        TitleBar.getInstance().setMinimizeHandler(trayManager::minimizeToTray);

        log.info("主界面构建完成");
    }

    private UpdateUiDelegate createUpdateUiDelegate() {
        return new UpdateUiDelegate() {
            private volatile DialogUtils.ProgressControl downloadProgress;
            private volatile boolean backgroundMode;

            @Override
            public void showNotification(String message, NotificationType type) {
                Platform.runLater(() ->
                        HookRegistry.INSTANCE.publish(HookEventType.UI_NOTIFICATION,
                                new StatusEvent(message, type)));
            }

            @Override
            public void showUpdateAvailable(VersionInfo info) {
                Platform.runLater(() ->
                        DialogUtils.showUpdateDialog(rootStack,
                                "发现新版本 " + info.version(),
                                BuildConfig.APP_VERSION,
                                info.version(),
                                info.releaseNotes(),
                                () -> UpdateManager.getInstance().startDownload(info),
                                () -> UpdateManager.getInstance().resetUpdateDialogShowing()));
            }

            @Override
            public void showDownloadProgress(String version, double progress) {
                Platform.runLater(() -> {
                    sidebar.setDownloadProgress(progress);
                    if (backgroundMode) {
                        return;
                    }
                    if (downloadProgress == null) {
                        downloadProgress = DialogUtils.showDownloadProgressDialog(rootStack, version, () -> {
                            backgroundMode = true;
                            sidebar.setDownloadProgress(0);
                        });
                    }
                    downloadProgress.updateProgress(progress,
                            String.format("%.1f%%", progress * 100));
                });
            }

            @Override
            public void hideDownloadProgress() {
                Platform.runLater(() -> {
                    sidebar.setDownloadProgress(-1);
                    if (downloadProgress != null) {
                        downloadProgress.close();
                        downloadProgress = null;
                    }
                    if (backgroundMode) {
                        backgroundMode = false;
                    }
                });
            }

            @Override
            public void showUpdateReadyDialog(VersionInfo info, Runnable onInstallNow, Runnable onLater) {
                Platform.runLater(() ->
                        DialogUtils.showUpdateReadyDialog(rootStack,
                                info.version(), onInstallNow, onLater));
            }

            @Override
            public void restartApplication() {
                Platform.runLater(() -> {
                    Platform.exit();
                    System.exit(0);
                });
            }
        };
    }

    private PluginUpdateUiDelegate createPluginUpdateUiDelegate() {
        return new PluginUpdateUiDelegate() {
            @Override
            public void showPluginUpdatesAvailable(Map<PluginInfo, PluginUpdateInfo> updates,
                                                    Consumer<List<String>> onDownloadSelected) {
                Platform.runLater(() ->
                        DialogUtils.showPluginUpdatesDialog(rootStack, updates, onDownloadSelected));
            }

            @Override
            public void showDownloadProgress(String pluginId, String version, double progress) {
                // 插件进度由 PluginManagementView 自己的轮询处理，不需要更新 sidebar
            }

            @Override
            public void hideDownloadProgress(String pluginId) {
            }

            @Override
            public void showUpdateReady(String pluginId, String message, Runnable onOk) {
                Platform.runLater(() ->
                        DialogUtils.showSimpleDialog(rootStack, "插件更新", message,
                                "确定", false, onOk));
            }
        };
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
        HookRegistry.INSTANCE.destroy();
        SocketServer.instance().stop();

        Platform.exit();
    }
}
