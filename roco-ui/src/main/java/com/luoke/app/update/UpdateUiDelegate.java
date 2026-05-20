package com.luoke.app.update;

import com.luoke.app.hook.event.NotificationType;

/**
 * 更新 UI 回调接口 — 由 JavaFX 层实现 UI 操作。
 */
public interface UpdateUiDelegate {

    void showNotification(String message, NotificationType type);

    void showUpdateAvailable(VersionInfo info);

    void restartApplication();

    /** 更新下载进度回调 */
    void showDownloadProgress(String version, double progress);

    /** 隐藏下载进度弹窗 */
    void hideDownloadProgress();

    /** 下载完毕就绪弹窗（立即更新 / 下次再说） */
    void showUpdateReadyDialog(VersionInfo info, Runnable onInstallNow, Runnable onLater);
}
