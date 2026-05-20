package com.luoke.app.update;

import com.luoke.app.hook.event.NotificationType;

/**
 * 更新 UI 回调接口 — 由 JavaFX 层实现 UI 操作。
 */
public interface UpdateUiDelegate {

    void showNotification(String message, NotificationType type);

    void showUpdateAvailable(VersionInfo info);

    void restartApplication();
}
