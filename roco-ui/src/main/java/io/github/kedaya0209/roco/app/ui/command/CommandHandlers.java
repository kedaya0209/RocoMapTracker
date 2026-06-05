package io.github.kedaya0209.roco.app.ui.command;

import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.config.NavigConfig;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.config.ViewConfig;
import io.github.kedaya0209.roco.app.context.CameraContext;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.match.map.SwitchMapMatcher;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.ui.command.AppCommands.*;
import io.github.kedaya0209.roco.app.ui.command.SidebarCommands.*;
import io.github.kedaya0209.roco.app.ui.command.ViewportCommands.*;
import io.github.kedaya0209.roco.app.ui.component.overlay.ResourceCounterPanel;
import io.github.kedaya0209.roco.app.ui.component.widget.FloatToolbox;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.service.VersionMode;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.PcapBridgeManager;
import io.github.kedaya0209.roco.app.ui.service.ui.SnifferInstallService;
import io.github.kedaya0209.roco.app.ui.service.ui.ThemeManager;
import io.github.kedaya0209.roco.app.ui.state.AppState;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;
import io.github.kedaya0209.roco.app.ui.state.ViewportState;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import net.jcip.annotations.ThreadSafe;

/**
 * Command 处理器注册中心 — 集中注册所有 {@link CommandBus} handler。
 * <p>
 * 每个 handler 负责：状态写入 → Config 同步 → EventBus 通知 → 服务调用。
 * 保证 UI 组件只调用 {@link CommandBus#dispatch(UiCommand)}，不混写其他数据路径。
 */
@ThreadSafe
public final class CommandHandlers {

    private static volatile boolean initialized = false;
    private static StackPane rootStack;
    private static PcapBridgeManager pcapBridgeManager;

    private CommandHandlers() {
    }

    public static void init(StackPane rootStack, PcapBridgeManager pm) {
        if (initialized) return;
        CommandHandlers.rootStack = rootStack;
        CommandHandlers.pcapBridgeManager = pm;
        initialized = true;

        registerAppHandlers();
        registerViewportHandlers();
        registerSidebarHandlers();
    }

    // ================================================================
    // 应用级命令
    // ================================================================

    private static void registerAppHandlers() {
        CommandBus.subscribe(ToggleMatchingCommand.class, cmd -> {
            AppState app = AppState.getInstance();
            boolean nowOn = !app.isMatchingEnabled();
            app.setMatchingEnabled(nowOn);
            SiftConfig.SIFT_MATCHING_ENABLED = nowOn;
            AppEvents.publish(StatusEvent.class,
                    nowOn
                            ? new StatusEvent("匹配已开启", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL)
                            : new StatusEvent("匹配已暂停", NotificationType.INFO, StatusEvent.DisplayMode.CAROUSEL));
        });

        CommandBus.subscribe(ToggleMaterialCollectionCommand.class, cmd -> {
            AppState app = AppState.getInstance();
            boolean nowOn = !app.isMaterialCollection();
            app.setMaterialCollection(nowOn);
            ViewConfig.MATERIAL_COLLECTION = nowOn;
        });

        CommandBus.subscribe(ToggleGhostModeCommand.class, cmd -> {
            AppState app = AppState.getInstance();
            app.setGhostMode(!app.isGhostMode());
        });

        CommandBus.subscribe(SetWindowOpacityCommand.class, cmd -> {
            NavigConfig.NAV_WINDOW_OPACITY = cmd.opacity();
            ConfigPersistence.save();
        });

        CommandBus.subscribe(SetFollowModeCommand.class, cmd -> CameraContext.getInstance().setFollowMode(cmd.enabled()));

        CommandBus.subscribe(ToggleNavModeCommand.class, cmd -> {
            CameraContext cam = CameraContext.getInstance();
            boolean next = !cam.isNavMode();
            NavigConfig.NAVIGATION_ENABLED = next;
            cam.setNavMode(next);
            if (next && NavigConfig.AUTO_FOLLOW_MODE) {
                cam.setFollowMode(true);
            } else if (!next) {
                cam.setNavAngle(0);
            }
        });
    }

    // ================================================================
    // 视口命令
    // ================================================================

    private static void registerViewportHandlers() {
        CommandBus.subscribe(DragViewportCommand.class, cmd -> {
            MapContext mc = MapContext.getInstance();
            ViewportState vp = ViewportState.getInstance();
            if (vp.isFollowMode()) {
                CameraContext.getInstance().setFollowMode(false);
            }
            double dx = cmd.dx();
            double dy = cmd.dy();
            if (vp.isNavMode() && vp.getNavAngle() != 0) {
                double rad = Math.toRadians(vp.getNavAngle());
                double cos = Math.cos(rad);
                double sin = Math.sin(rad);
                dx = cmd.dx() * cos - cmd.dy() * sin;
                dy = cmd.dx() * sin + cmd.dy() * cos;
            }
            mc.setOffsetX(mc.getOffsetX() + dx);
            mc.setOffsetY(mc.getOffsetY() + dy);
            mc.ensureBounds();
        });

        CommandBus.subscribe(ZoomViewportCommand.class, cmd -> {
            MapContext mc = MapContext.getInstance();
            ViewportState vp = ViewportState.getInstance();
            if (vp.isFollowMode()) {
                if (!vp.isPlayerInitialized()) {
                    mc.zoom(cmd.factor(), cmd.mx(), cmd.my());
                    return;
                }
                double oldScale = mc.getScale();
                double newScale = Math.clamp(oldScale * cmd.factor(),
                        ViewConfig.INTERACTIVE_FOLLOW_MIN_SCALE,
                        ViewConfig.INTERACTIVE_FOLLOW_MAX_SCALE);
                mc.setScale(newScale);
                double cx = mc.getViewWidth() / 2;
                double cy = mc.getViewHeight() / 2;
                mc.setOffsetX(cx - vp.getSmoothedPlayerX() * newScale);
                mc.setOffsetY(cy - vp.getSmoothedPlayerY() * newScale);
                mc.ensureBounds();
                CameraContext.getInstance().setFollowScale(newScale);
            } else {
                mc.zoom(cmd.factor(), cmd.mx(), cmd.my());
            }
        });

        CommandBus.subscribe(SetViewportSizeCommand.class, cmd -> {
            MapContext mc = MapContext.getInstance();
            ViewportState vp = ViewportState.getInstance();
            mc.setViewWidth(cmd.width());
            mc.setViewHeight(cmd.height());
            vp.setViewWidth(cmd.width());
            vp.setViewHeight(cmd.height());
        });

        CommandBus.subscribe(ResetViewportCommand.class, cmd -> {
            // handled externally by MapRenderer.resetViewport()
        });
    }

    // ================================================================
    // 侧边栏命令
    // ================================================================

    private static void registerSidebarHandlers() {
        CommandBus.subscribe(SwitchAlgorithmCommand.class, cmd -> {
            try {
                SwitchMapMatcher.getInstance().switchMapMatcher(cmd.algorithm());
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("正在重启匹配引擎: " + cmd.algorithm() + " ...", NotificationType.INFO));
            } catch (Exception e) {
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("切换算法失败", NotificationType.ERROR));
            }
        });

        CommandBus.subscribe(SwitchResourceCommand.class, cmd -> {
            DownloadConfig.INTERNAL_RESOURCE = cmd.isInternal();
            ConfigPersistence.save();
        });

        CommandBus.subscribe(SwitchThemeCommand.class, cmd -> {
            ThemeManager.switchTheme(cmd.name());
            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("主题已切换: " + cmd.name(), NotificationType.SUCCESS));
        });

        CommandBus.subscribe(SwitchVersionCommand.class, cmd -> {
            if (cmd.mode() == VersionMode.ADVANCED) {
                int port = SocketServer.instance().getPort();
                SnifferInstallService.installIfNeeded(rootStack, port, pcapBridgeManager);
                ResourceCounterPanel.getInstance().toggle(false);
                FloatToolbox.getInstance().setCollectButtonVisible(true);
                SiftConfig.SIFT_MATCHING_ENABLED = true;
                Platform.runLater(() ->
                        ConfirmDialog.showSimpleDialog(rootStack, "提示",
                                "高级版组件 (sniffer) 已就绪，请手动断开游戏网络连接后重连，以便抓包组件捕获通信密钥。",
                                "确定", true, () -> {}));
                PluginUpdateManager.getInstance().checkAllPlugins(true);
            } else {
                pcapBridgeManager.stop();
                ResourceCounterPanel.getInstance().toggle(false);
                FloatToolbox.getInstance().setCollectButtonVisible(false);
                SiftConfig.SIFT_MATCHING_ENABLED = false;
            }
        });
    }
}
