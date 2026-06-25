package io.github.kedaya0209.roco.app.process;

import io.github.kedaya0209.roco.app.platform.JobObjectManager;
import net.jcip.annotations.ThreadSafe;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件进程处理程序注册表。管理 pluginId → 启动/停止处理程序的映射，
 * 供 {@code PluginUpdateManager} 在启用/禁用/更新插件时调用。
 * <p>
 * 可通过 {@link #register(String, Runnable, Runnable)} 手动注册自定义启停逻辑，
 * 或使用 {@link #registerEntry(String, String, String)} 从 metadata entry 自动注册。
 */
@ThreadSafe
public class PluginProcessHandler {

    private final Map<String, Runnable> stopHandlers = new ConcurrentHashMap<>();
    private final Map<String, Runnable> startHandlers = new ConcurrentHashMap<>();
    private final Map<String, NativeProcess> runningProcesses = new ConcurrentHashMap<>();

    /**
     * 注册插件进程的启动/停止处理程序。
     *
     * @param pluginId     插件标识
     * @param startHandler 插件启用时调用（可为 null）
     * @param stopHandler  插件禁用或更新前调用（可为 null）
     */
    public void register(String pluginId, Runnable startHandler, Runnable stopHandler) {
        if (startHandler != null) startHandlers.put(pluginId, startHandler);
        if (stopHandler != null) stopHandlers.put(pluginId, stopHandler);
    }

    /**
     * 根据插件入口路径自动注册启停处理程序。
     * 启动时以 NativeProcess 创建入口进程，停止时强制销毁。
     *
     * @param pluginId     插件标识
     * @param entryAbsPath 入口可执行文件绝对路径
     * @param workDir      进程工作目录
     */
    public void registerEntry(String pluginId, String entryAbsPath, String workDir) {
        startHandlers.put(pluginId, () -> {
            if (runningProcesses.containsKey(pluginId)) return;
            NativeProcess proc = NativeProcess.create(
                    "\"" + entryAbsPath + "\"", JobObjectManager.getJobHandle(), true, workDir);
            if (proc != null) {
                runningProcesses.put(pluginId, proc);
            }
        });
        stopHandlers.put(pluginId, () -> {
            NativeProcess proc = runningProcesses.remove(pluginId);
            if (proc != null) proc.destroyForcibly();
        });
    }

    /**
     * 插件启用时调用对应的启动处理程序。
     */
    public void onPluginEnabled(String pluginId) {
        Runnable h = startHandlers.get(pluginId);
        if (h != null) h.run();
    }

    /**
     * 插件禁用时调用对应的停止处理程序。
     */
    public void onPluginDisabled(String pluginId) {
        Runnable h = stopHandlers.get(pluginId);
        if (h != null) h.run();
    }

    /**
     * 停止指定插件的进程（更新前调用）。
     */
    public void stopPlugin(String pluginId) {
        Runnable h = stopHandlers.get(pluginId);
        if (h != null) h.run();
    }

    /**
     * 指定插件是否注册了停止处理程序。
     */
    public boolean hasStopHandler(String pluginId) {
        return stopHandlers.containsKey(pluginId);
    }
}
