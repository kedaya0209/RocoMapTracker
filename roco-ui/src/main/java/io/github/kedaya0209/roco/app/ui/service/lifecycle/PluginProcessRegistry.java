package io.github.kedaya0209.roco.app.ui.service.lifecycle;

import io.github.kedaya0209.roco.app.process.ProcessMonitor;
import net.jcip.annotations.ThreadSafe;

import java.util.Map;

/**
 * 插件进程资源监控注册中心 — 将插件 ID 与子进程 PID 关联，提供 CPU/内存读数。
 * <p>
 * 在 {@code ModernCanvasApp} 初始化完成后注册内置插件进程 PID。
 */
@ThreadSafe
public class PluginProcessRegistry {

    private PluginProcessRegistry() {}

    private static final ProcessMonitor MONITOR = new ProcessMonitor();

    /** 获取底层 ProcessMonitor */
    public static ProcessMonitor getMonitor() {
        return MONITOR;
    }

    /**
     * 注册内置插件进程（由 ModernCanvasApp 初始化完成后调用）。
     *
     * @param pluginId 插件标识
     * @param pid      进程 ID
     */
    public static void register(String pluginId, int pid) {
        MONITOR.register(pluginId, pid);
    }

    /**
     * 移除注册。
     */
    public static void unregister(String pluginId) {
        MONITOR.unregister(pluginId);
    }

    /**
     * 采样所有已注册进程读数。
     *
     * @return pluginId → Reading(memoryKB, cpuPercent)
     */
    public static Map<String, ProcessMonitor.Reading> sample() {
        return MONITOR.sample();
    }
}
