package com.luoke.app.ui;

import com.luoke.app.ui.util.TaskbarIconHelper;
import com.luoke.app.utils.FilePathUtil;
import com.luoke.app.utils.ResourceUtils;
import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.BuildConfig;
import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.PathConfig;
import com.luoke.app.config.UiConfig;
import com.luoke.app.config.UpdateConfig;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.hook.event.StatusEvent;
import com.luoke.app.hook.impl.UiResponseHook;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.ui.component.LoadingOverlay;
import com.luoke.app.ui.component.Sidebar;
import com.luoke.app.ui.component.UiAnimator;
import com.luoke.app.ui.service.*;
import com.luoke.app.ui.util.DialogUtils;
import com.luoke.app.ui.util.DialogUtils.ProgressControl;
import com.luoke.app.update.UpdateManager;
import com.luoke.app.update.UpdateUiDelegate;
import com.luoke.app.update.VersionInfo;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
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
 * </ul>
 */
@NotThreadSafe
@Slf4j
public class ModernCanvasApp extends Application {

    private final WindowManager windowManager = new WindowManager(UiConfig.RESIZE_MARGIN);
    private final UiAnimator uiAnimator = new UiAnimator();
    private final SiftClientManager siftClientManager = new SiftClientManager();
    private final CaptureServiceManager captureServiceManager = new CaptureServiceManager();
    private StackPane rootStack;
    private Stage primaryStage;
    private Sidebar sidebar;
    private String iconFilePath;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        ConfigPersistence.init(); // 配置加载必须在所有 Config 字段读取之前
        this.primaryStage = primaryStage;

        // ---- 1. 场景骨架 ----
        initScene();

        primaryStage.setTitle(CaptureConfig.APP_MAIN_TITLE);
        primaryStage.show();

        // 提取 .ico 图标文件并立即设置任务栏图标（尽早设置，避免窗口出现时无图标）
        initTaskbarIcon();

        // ---- 2. 基础设施 ----
        InfrastructureManager.init();

        // ---- 3. 主题 ----
        ThemeManager.applyTheme(UiConfig.THEME);

        // ---- 4. SIFT 切换回调 ----
        SwitchMapMatcher.getInstance().setSwitchCallback(newVariant -> {
            log.info("算法变体切换: {}", newVariant);
            siftClientManager.restartClient(newVariant);
        });

        // ---- 5. 资源初始化 → 主界面构建 ----
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
        wrapper.getChildren().add(rootStack);

        LoadingOverlay globalLoading = new LoadingOverlay(null);
        rootStack.getChildren().add(globalLoading);
        HookRegistry.INSTANCE.register(new UiResponseHook(rootStack, globalLoading));

        Scene scene = new Scene(wrapper, ViewConfig.INITIAL_WINDOW_WIDTH, ViewConfig.INITIAL_WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setScene(scene);
    }

    /**
     * 加载程序图标（JavaFX 窗口装饰图标）。
     */
    private void loadStageIcons() {
        try {
            Image icon = new Image(ResourceUtils.getResourceStream(PathConfig.ICON_PNG));
            if (!icon.isError()) {
                primaryStage.getIcons().add(icon);
            }
        } catch (Exception e) {
            log.warn("程序图标加载失败", e);
        }
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
                iconFilePath = iconFile.getAbsolutePath();
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

        result.renderer().start();
        this.sidebar = result.sidebar();

        // 设置更新 UI 回调
        UpdateManager.getInstance().setUiDelegate(new UpdateUiDelegate() {
            private volatile ProgressControl downloadProgress;
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
                    if (backgroundMode) {
                        sidebar.setDownloadProgress(progress);
                        return;
                    }
                    if (downloadProgress == null) {
                        downloadProgress = DialogUtils.showDownloadProgressDialog(rootStack, version, () -> {
                            // 后台下载按钮回调：切到侧边栏显示进度
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
                    if (downloadProgress != null) {
                        downloadProgress.close();
                        downloadProgress = null;
                    }
                    if (backgroundMode) {
                        backgroundMode = false;
                        sidebar.setDownloadProgress(-1);
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
        });

        // 启动定时更新检查
        if (UpdateConfig.CHECK_ENABLED) {
            UpdateManager.getInstance().startPeriodicCheck(UpdateConfig.CHECK_INTERVAL_HOURS);
        }

        log.info("主界面构建完成");
    }

    @Override
    public void stop() {
        log.info("正在关闭程序...");

        captureServiceManager.stop();
        siftClientManager.stop();
        InfrastructureManager.destroy();
        HookRegistry.INSTANCE.destroy();
        OcrAsyncManager.getInstance().close();
        SocketServer.instance().stop();

        Platform.exit();
    }
}
