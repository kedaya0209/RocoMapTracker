package io.github.kedaya0209.roco.app.ui.service.ui;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.SnifferConfig;
import io.github.kedaya0209.roco.app.ui.component.dialog.ConfirmDialog;
import io.github.kedaya0209.roco.app.ui.component.dialog.DownloadProgressDialog;
import io.github.kedaya0209.roco.app.ui.component.dialog.DownloadProgressDialog.ProgressControl;
import io.github.kedaya0209.roco.app.ui.service.lifecycle.PcapBridgeManager;
import io.github.kedaya0209.roco.app.update.plugin.PluginSource;
import io.github.kedaya0209.roco.app.update.plugin.PluginUpdateManager;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import lombok.extern.slf4j.Slf4j;
import java.util.concurrent.atomic.AtomicBoolean;

@NotThreadSafe
@Slf4j
public class SnifferInstallService {

    private SnifferInstallService() {}

    /** 会话级安装标记，防止同一 JVM 生命周期内重复下载 */
    private static final AtomicBoolean installAttempted = new AtomicBoolean(false);

    /**
     * 检查并自动安装 sniffer 插件（后台下载）。
     */
    public static void installIfNeeded(StackPane rootStack, int port,
                                        PcapBridgeManager pcapBridgeManager) {
        PluginUpdateManager pm = PluginUpdateManager.getInstance();

        pm.scanPlugins();
        boolean snifferReady = pm.getPlugin("sniffer")
                .filter(p -> p.status() != io.github.kedaya0209.roco.app.update.plugin.PluginStatus.DAMAGED
                        && p.status() != io.github.kedaya0209.roco.app.update.plugin.PluginStatus.DISABLED)
                .isPresent();

        if (snifferReady) {
            pcapBridgeManager.init(port, null);
        } else if (installAttempted.compareAndSet(false, true)) {
            // CAS 成功：这是第一次尝试安装
            ProgressControl pc = DownloadProgressDialog.showDownloadProgressDialog(
                    rootStack, "需要下载高级版组件 (sniffer)...", null);
            Thread.ofPlatform().daemon(true).name("sniffer-install").start(() -> pm.checkRemotePlugin("sniffer",
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
                                            ConfirmDialog.showSimpleDialog(rootStack,
                                                    "安装失败",
                                                    "sniffer 插件安装失败: " + err,
                                                    "确定", true, () -> {}));
                                }),
                        () -> {
                            pc.close();
                            Platform.runLater(() ->
                                    ConfirmDialog.showSimpleDialog(rootStack,
                                            "安装失败",
                                            "无法获取 sniffer 插件信息，请检查网络连接",
                                            "确定", true, () -> {}));
                        }));
        } else {
            log.info("sniffer 安装已在之前尝试过，跳过重复下载");
        }
    }
}
