package io.github.kedaya0209.roco.app.ui.service.ui;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.BuildConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.ui.component.Sidebar;
import io.github.kedaya0209.roco.app.ui.component.dialog.DownloadProgressDialog.ProgressControl;
import io.github.kedaya0209.roco.app.ui.component.dialog.UpdateDialog;
import io.github.kedaya0209.roco.app.update.UpdateManager;
import io.github.kedaya0209.roco.app.update.UpdateUiDelegate;
import io.github.kedaya0209.roco.app.update.VersionInfo;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;

@NotThreadSafe
public class AppUpdateUiHandler implements UpdateUiDelegate {

    private final StackPane rootStack;
    private final Sidebar sidebar;
    private volatile ProgressControl downloadProgress;
    private volatile boolean backgroundMode;

    public AppUpdateUiHandler(StackPane rootStack, Sidebar sidebar) {
        this.rootStack = rootStack;
        this.sidebar = sidebar;
    }

    @Override
    public void showNotification(String message, NotificationType type) {
        Platform.runLater(() ->
                AppEvents.publish(StatusEvent.class, new StatusEvent(message, type)));
    }

    @Override
    public void showUpdateAvailable(VersionInfo info) {
        Platform.runLater(() ->
                UpdateDialog.showUpdateDialog(rootStack,
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
            if (backgroundMode) return;
            if (downloadProgress == null) {
                downloadProgress = io.github.kedaya0209.roco.app.ui.component.dialog.DownloadProgressDialog
                        .showDownloadProgressDialog(rootStack, version, () -> {
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
                UpdateDialog.showUpdateReadyDialog(rootStack,
                        info.version(), onInstallNow, onLater));
    }

    @Override
    public void restartApplication() {
        Platform.runLater(() -> {
            Platform.exit();
            System.exit(0);
        });
    }
}
