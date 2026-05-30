package io.github.kedaya0209.roco.app.update.plugin;

import net.jcip.annotations.ThreadSafe;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 插件更新 UI 回调接口 - 由 JavaFX 层实现.
 */
@ThreadSafe
public interface PluginUpdateUiDelegate {

    /**
     * 通知用户有插件更新可用（批量）.
     * @param updates           所有有更新的插件 <PluginInfo, PluginUpdateInfo>
     * @param onDownloadSelected 用户选中一批插件后回调，参数为选中插件的 pluginId 列表
     */
    void showPluginUpdatesAvailable(Map<PluginInfo, PluginUpdateInfo> updates,
                                    Consumer<List<String>> onDownloadSelected);

    /** 更新下载进度 */
    void showDownloadProgress(String pluginId, String version, double progress);

    /** 隐藏下载进度 */
    void hideDownloadProgress(String pluginId);

    /** 下载完成, 通知用户 */
    void showUpdateReady(String pluginId, String message, Runnable onOk);
}
