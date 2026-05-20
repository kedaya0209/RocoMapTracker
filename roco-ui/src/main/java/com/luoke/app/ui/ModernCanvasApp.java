package com.luoke.app.ui;

import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.PathConfig;
import com.luoke.app.config.UiConfig;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.context.OcrAsyncManager;
import com.luoke.app.hook.impl.UiResponseHook;
import com.luoke.app.hook.multicast.HookRegistry;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.ui.component.LoadingOverlay;
import com.luoke.app.ui.component.UiAnimator;
import com.luoke.app.ui.service.*;
import com.luoke.app.ui.util.DialogUtils;
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
@Slf4j
public class ModernCanvasApp extends Application {

    private final WindowManager windowManager = new WindowManager(UiConfig.RESIZE_MARGIN);
    private final UiAnimator uiAnimator = new UiAnimator();
    private final SiftClientManager siftClientManager = new SiftClientManager();
    private final CaptureServiceManager captureServiceManager = new CaptureServiceManager();
    private StackPane rootStack;
    private Stage primaryStage;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        ConfigPersistence.init(); // 配置加载必须在所有 Config 字段读取之前
        this.primaryStage = primaryStage;

        // ---- 1. 场景骨架 ----
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

        // 程序图标
        try {
            Image icon = SvgManager.createImage(PathConfig.ICON, 256);
            if (icon != null) primaryStage.getIcons().add(icon);
        } catch (Exception e) {
            log.warn("程序图标加载失败", e);
        }
        primaryStage.setTitle(CaptureConfig.APP_MAIN_TITLE);
        primaryStage.show();

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
     * 主界面构建：UI 组装 → 核心服务初始化 → 渲染循环启动。
     * 由 ResourceInitService 资源就绪后回调。
     */
    private void buildMainUi() {
        MainUiComposer.UiBuildResult result = MainUiComposer.buildMainUI(primaryStage, rootStack, windowManager, uiAnimator);

        siftClientManager.init();
        captureServiceManager.init(siftClientManager.getClient());

        result.renderer().start();
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
