package io.github.kedaya0209.roco.app.update.plugin;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.ThreadSafe;

import io.github.kedaya0209.roco.app.process.PluginProcessHandler;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 插件更新管理器 - 单例编排扫描/检查/下载全流程. 
 * <p>
 * 状态检查流程: scanPlugins() → 检测本地状态
 * 更新检查流程: checkAllPlugins(silent) → 远程版本对比 → UI 通知
 * 更新安装流程: startUpdate(pluginId) → 下载 → 校验 → 替换
 */
@Slf4j
@ThreadSafe
public class PluginUpdateManager {

    private static volatile PluginUpdateManager instance;

    /** 已扫描的插件缓存 <pluginId, PluginInfo> */
    private final Map<String, PluginInfo> pluginCache = new ConcurrentHashMap<>();

    /** 已发现的更新 <pluginId, PluginUpdateInfo> */
    private final Map<String, PluginUpdateInfo> updateCache = new ConcurrentHashMap<>();

    /** 跳过此版本的记录 <pluginId, version> - 用户点"跳过"后不再提示 */
    private final Map<String, String> skippedVersions = new ConcurrentHashMap<>();

    /** 下载进度 <pluginId, 0.0~1.0> */
    private final Map<String, Double> downloadProgress = new ConcurrentHashMap<>();

    /** 下载队列，支持批量顺序下载 */
    private final Queue<String> downloadQueue = new ConcurrentLinkedQueue<>();

    /** 插件运行状态 <pluginId, 是否正在运行> */
    private final Map<String, Boolean> runningStatus = new ConcurrentHashMap<>();

    /** 按插件完成回调 <pluginId, Consumer<结果消息>> — 优先级高于全局 uiDelegate，一次性使用 */
    private final Map<String, Consumer<String>> updateCompletionCallbacks = new ConcurrentHashMap<>();

    private final PluginScanner scanner;
    private final PluginUpdateChecker checker;
    private final PluginDownloadManager downloader;

    private final AtomicBoolean scanning = new AtomicBoolean(false);
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    /** 插件缓存版本号，UI 轮询检测此值变更来自动刷新 */
    private final AtomicInteger cacheVersion = new AtomicInteger(0);

    private volatile boolean monitoring = false;
    @Getter
    private WatchService watchService;

    @Setter
    private volatile PluginUpdateUiDelegate uiDelegate;

    /** 插件禁用回调 — 在 .disabled 标记创建后调用，参数为 pluginId */
    @Setter
    private volatile Consumer<String> onPluginDisabled;
    /** 插件启用回调 — 在 .disabled 标记删除后调用，参数为 pluginId */
    @Setter
    private volatile Consumer<String> onPluginEnabled;
    /** 更新检查完成回调 — checkAllPlugins 后台线程结束后调用
     * -- SETTER --
     *  设置更新检查完成回调（一次性，checkAllPlugins 完成后自动清除）。
     */
    @Setter
    private volatile Runnable onCheckComplete;

    /** 插件进程处理程序注册表 — 扫描时自动从 metadata entry 注册 */
    @Getter
    private final PluginProcessHandler processHandler;

    private PluginUpdateManager() {
        this.scanner = new PluginScanner();
        this.checker = new PluginUpdateChecker();
        this.downloader = new PluginDownloadManager();
        this.processHandler = new PluginProcessHandler();
        startWatching();
        // 预加载：后台扫描一次插件，用户打开管理页面时缓存已就绪
        Thread.ofPlatform().daemon(true).name("plugin-preload").start(() -> {
            scanPlugins();
            cacheVersion.incrementAndGet();
        });
    }

    /**
     * 获取插件缓存版本号。每次后台扫描后递增，UI 可通过对比此值判断是否需要刷新。
     */
    public int getCacheVersion() {
        return cacheVersion.get();
    }

    /**
     * 启动 WatchService 文件监控，监听 plugins/ 目录变更后自动扫描.
     */
    private void startWatching() {
        if (monitoring) return;
        try {
            WatchService ws = FileSystems.getDefault().newWatchService();
            Path pluginsDir = FilePathUtil.getRelativeFile("plugins").toPath();
            Files.createDirectories(pluginsDir);

            // 注册 plugins/ 目录及所有子目录
            registerAllDirectories(pluginsDir, ws);

            monitoring = true;
            this.watchService = ws;

            Thread.ofPlatform().daemon(true).name("plugin-watch").start(() -> {
                while (monitoring && !Thread.currentThread().isInterrupted()) {
                    try {
                        WatchKey key = ws.take();
                        registerNewSubdirectories(key, ws);
                        key.reset();

                        // 防抖：连发事件（如文件复制）只触发一次扫描
                        while ((key = ws.poll(500, TimeUnit.MILLISECONDS)) != null) {
                            registerNewSubdirectories(key, ws);
                            key.reset();
                        }

                        // 下载/解压进行中时跳过扫描，避免读到不完整的插件目录
                        if (!downloading.get()) {
                            scanPlugins();
                            cacheVersion.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        log.warn("文件监控异常", e);
                    }
                }
            });
        } catch (IOException e) {
            log.error("无法启动插件文件监控", e);
        }
    }

    private static void registerAllDirectories(Path root, WatchService ws) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                try {
                    dir.register(ws,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                } catch (IOException e) {
                    log.warn("注册目录监控失败: {}", dir, e);
                }
            });
        }
    }

    private static void registerNewSubdirectories(WatchKey key, WatchService ws) {
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;
            Path dir = (Path) key.watchable();
            Path child = dir.resolve((Path) event.context());
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                try {
                    if (Files.isDirectory(child)) {
                        registerAllDirectories(child, ws);
                    }
                } catch (IOException e) {
                    log.warn("注册新目录监控失败: {}", child, e);
                }
            }
        }
    }

    public static PluginUpdateManager getInstance() {
        if (instance == null) {
            synchronized (PluginUpdateManager.class) {
                if (instance == null) {
                    instance = new PluginUpdateManager();
                }
            }
        }
        return instance;
    }

    // ==================== 扫描 ====================

    /**
     * 扫描 plugins/ 目录, 解析 metadata.json, 校验本地文件 sha256. 
     *
     * @return 插件列表
     */
    public List<PluginInfo> scanPlugins() {
        if (!scanning.compareAndSet(false, true)) {
            log.debug("扫描正在进行中，返回缓存");
            return List.copyOf(pluginCache.values());
        }
        try {
            List<PluginInfo> plugins = scanner.scanPlugins();
            pluginCache.clear();
            for (PluginInfo p : plugins) {
                pluginCache.put(p.id(), p);
                // 有入口可执行文件的插件自动注册进程启停
                if (p.entry() != null && !p.entry().isEmpty()
                        && !processHandler.hasStopHandler(p.id())) {
                    String entryPath = new File(p.pluginDir(), p.entry()).getAbsolutePath();
                    processHandler.registerEntry(p.id(), entryPath, p.pluginDir().getAbsolutePath());
                }
            }
            return plugins;
        } finally {
            scanning.set(false);
        }
    }

    /**
     * 获取缓存的插件列表. 
     */
    public List<PluginInfo> getCachedPlugins() {
        return List.copyOf(pluginCache.values());
    }

    /**
     * 获取指定插件信息. 
     */
    public Optional<PluginInfo> getPlugin(String pluginId) {
        return Optional.ofNullable(pluginCache.get(pluginId));
    }

    // ==================== 更新检查 ====================

    /**
     * 检查单个插件是否有更新. 
     */
    public Optional<PluginUpdateInfo> checkPluginUpdate(PluginInfo plugin) {
        if (plugin.source() == null) {
            return Optional.empty();
        }

        Optional<PluginUpdateInfo> result = checker.checkUpdate(plugin);
        result.ifPresent(update -> updateCache.put(plugin.id(), update));
        return result;
    }

    /**
     * 检查所有插件更新(后台线程). 
     *
     * @param silent 静默模式, 无新版本时不通知
     */
    public void checkAllPlugins(boolean silent) {
        if (!checking.compareAndSet(false, true)) {
            log.debug("更新检查正在进行中");
            return;
        }

        Thread.ofPlatform().daemon(true).name("plugin-update-check").start(() -> {
            try {
                List<PluginInfo> plugins = scanPlugins();
                Map<PluginInfo, PluginUpdateInfo> foundUpdates = new HashMap<>();

                for (PluginInfo plugin : plugins) {
                    if (plugin.source() == null) continue;
                    if (plugin.status() == PluginStatus.UNKNOWN) continue;

                    try {
                        Optional<PluginUpdateInfo> result = checker.checkUpdate(plugin);
                        if (result.isEmpty()) continue;

                        PluginUpdateInfo update = result.get();

                        // 跳过已忽略的版本
                        String skipped = skippedVersions.get(plugin.id());
                        if (skipped != null && skipped.equals(update.version())) {
                            continue;
                        }

                        // 比较版本
                        if (!PluginUpdateChecker.isNewer(plugin.version(), update.version())) {
                            continue;
                        }

                        // 更新缓存
                        updateCache.put(plugin.id(), update);
                        // 标记状态
                        pluginCache.put(plugin.id(),
                                new PluginInfo(plugin.id(), plugin.name(), plugin.title(),
                                        plugin.version(), plugin.description(), plugin.icon(),
                                        plugin.entry(), plugin.source(),
                                        plugin.assets(), PluginStatus.HAS_UPDATE, plugin.pluginDir()));

                        foundUpdates.put(pluginCache.get(plugin.id()), update);

                    } catch (Exception e) {
                        log.warn("检查插件 {} 更新失败", plugin.id(), e);
                    }
                }

                // 批量通知 UI（silent 模式下不弹窗）
                if (!foundUpdates.isEmpty() && !silent && uiDelegate != null) {
                    uiDelegate.showPluginUpdatesAvailable(foundUpdates,
                            selectedIds -> selectedIds.forEach(this::startUpdate));
                }

                if (!silent && foundUpdates.isEmpty()) {
                    log.info("所有插件已是最新");
                }

            } finally {
                checking.set(false);
                Runnable cb = onCheckComplete;
                if (cb != null) {
                    cb.run();
                }
            }
        });
    }

    /**
     * 是否正在检查更新.
     */
    public boolean isCheckingUpdates() {
        return checking.get();
    }

    // ==================== 下载更新 ====================

    /**
     * 注册按插件完成回调。优先级高于全局 uiDelegate.showUpdateReady()，一次性消费。
     * 回调在后台 daemon 线程调用，需自行包装 Platform.runLater()。
     */
    public void setUpdateCompletionCallback(String pluginId, Consumer<String> onComplete) {
        if (onComplete != null) {
            updateCompletionCallbacks.put(pluginId, onComplete);
        } else {
            updateCompletionCallbacks.remove(pluginId);
        }
    }

    /**
     * 开始下载并安装插件更新. 支持批量调用，内部排队依次下载.
     */
    public void startUpdate(String pluginId) {
        if (!updateCache.containsKey(pluginId)) {
            log.warn("未找到 {} 的更新信息", pluginId);
            return;
        }

        downloadQueue.add(pluginId);
        processDownloadQueue();
    }

    /** 从队列取出下一个插件执行下载 */
    private void processDownloadQueue() {
        if (!downloading.compareAndSet(false, true)) {
            return; // 当前有下载进行中，队列会在完成时继续处理
        }

        String pluginId = downloadQueue.poll();
        if (pluginId == null) {
            downloading.set(false);
            return;
        }

        PluginUpdateInfo update = updateCache.get(pluginId);
        if (update == null) {
            log.warn("队列中 {} 的更新信息已丢失", pluginId);
            downloading.set(false);
            processDownloadQueue(); // 尝试下一个
            return;
        }

        if (uiDelegate != null) {
            uiDelegate.showDownloadProgress(pluginId, update.version(), 0);
        }

        Thread.ofPlatform().daemon(true).name("plugin-download-" + pluginId).start(() -> {
            try {
                boolean wasRunning = isPluginRunning(pluginId);

                // 如果插件正在运行，先关闭再更新
                if (wasRunning) {
                    PluginProcessHandler ph = processHandler;
                    if (ph != null && ph.hasStopHandler(pluginId)) {
                        log.info("插件 {} 正在运行，更新前先关闭...", pluginId);
                        ph.stopPlugin(pluginId);
                        // 短等待确保进程资源释放
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    } else {
                        log.warn("插件 {} 正在运行，但未配置停止回调，跳过更新", pluginId);
                        downloading.set(false);
                        processDownloadQueue();
                        return;
                    }
                }

                downloader.downloadAndInstall(update,
                        progress -> {
                            downloadProgress.put(pluginId, progress);
                            if (uiDelegate != null) {
                                uiDelegate.showDownloadProgress(pluginId, update.version(), progress);
                            }
                        },
                        () -> {
                            // 成功
                            downloadProgress.remove(pluginId);
                            if (uiDelegate != null) {
                                uiDelegate.hideDownloadProgress(pluginId);
                            }
                            String msg = "插件 " + pluginId + " 已更新到 " + update.version();
                            Consumer<String> cb = updateCompletionCallbacks.remove(pluginId);
                            if (cb != null) {
                                cb.accept(msg);
                            } else if (uiDelegate != null) {
                                uiDelegate.showUpdateReady(pluginId, msg, () -> {});
                            }
                            scanPlugins();
                            cacheVersion.incrementAndGet();
                            log.info("插件 {} 更新成功: {}", pluginId, update.version());
                            // 更新前在运行中，更新后自动重启
                            if (wasRunning) {
                                PluginProcessHandler ph = processHandler;
                                if (ph != null) {
                                    log.info("插件 {} 更新完成，重新启动...", pluginId);
                                    ph.onPluginEnabled(pluginId);
                                }
                            }
                            // 下载涉及大量文件 I/O 和 JSON 解析，Serial GC 惰性收缩，
                            // 显式触发 full GC 回收临时分配的堆内存
                            System.gc();
                        },
                        error -> {
                            // 失败
                            downloadProgress.remove(pluginId);
                            if (uiDelegate != null) {
                                uiDelegate.hideDownloadProgress(pluginId);
                            }
                            Consumer<String> cb = updateCompletionCallbacks.remove(pluginId);
                            if (cb != null) {
                                cb.accept(error);
                            } else if (uiDelegate != null) {
                                uiDelegate.showUpdateReady(pluginId, error, () -> {});
                            }
                            log.error("插件 {} 更新失败: {}", pluginId, error);
                        });
            } finally {
                downloading.set(false);
                processDownloadQueue(); // 继续处理队列中的下一个
            }
        });
    }

    // ==================== 下载进度 ====================

    /**
     * 获取插件下载进度.
     *
     * @return 0.0 ~ 1.0, 未在下载时返回 0.0
     */
    public double getDownloadProgress(String pluginId) {
        return downloadProgress.getOrDefault(pluginId, 0.0);
    }

    /**
     * 插件是否正在下载.
     */
    public boolean isDownloading(String pluginId) {
        double p = downloadProgress.getOrDefault(pluginId, 0.0);
        return p > 0 && p < 1;
    }

    // ==================== 运行状态 ====================

    /**
     * 设置插件运行状态（由进程管理器调用）.
     */
    public void setPluginRunning(String pluginId, boolean running) {
        if (running) {
            runningStatus.put(pluginId, true);
        } else {
            runningStatus.remove(pluginId);
        }
    }

    /**
     * 插件当前是否正在运行.
     */
    public boolean isPluginRunning(String pluginId) {
        return runningStatus.getOrDefault(pluginId, false);
    }

    // ==================== 路径解析 & 首次安装 ====================

    /**
     * 获取插件入口可执行文件的绝对路径.
     *
     * @return 插件 entry 的绝对路径, 插件不存在或状态为 DAMAGED 时返回 empty
     */
    public Optional<String> getPluginEntryPath(String pluginId) {
        return getPlugin(pluginId)
                .filter(p -> p.status() != PluginStatus.DAMAGED && p.status() != PluginStatus.DISABLED)
                .map(p -> new File(p.pluginDir(), p.entry()).getAbsolutePath());
    }

    /**
     * 检查远程仓库是否有可用更新（无需本地安装）.
     *
     * @param pluginId 插件标识
     * @param source   远程源配置
     * @return 远程更新信息
     */
    public Optional<PluginUpdateInfo> checkRemotePlugin(String pluginId, PluginSource source) {
        PluginInfo dummy = new PluginInfo(pluginId, "", "", "0.0.0", "", "", "", source,
                List.of(), PluginStatus.UNKNOWN, null);
        return checker.checkUpdate(dummy);
    }

    /**
     * 下载并安装插件（不依赖 updateCache，用于首次安装）.
     *
     * @param update   更新信息
     * @param progress 进度回调 (0.0 ~ 1.0)
     * @param onSuccess 成功后回调
     * @param onError   失败后回调
     */
    public void downloadPlugin(PluginUpdateInfo update,
                               Consumer<Double> progress,
                               Runnable onSuccess,
                               Consumer<String> onError) {
        if (!downloading.compareAndSet(false, true)) {
            log.info("正在下载中...");
            return;
        }
        Thread.ofPlatform().daemon(true).name("plugin-download-" + update.pluginId()).start(() -> {
            try {
                downloader.downloadAndInstall(update, progress, onSuccess, onError);
            } finally {
                downloading.set(false);
            }
        });
    }

    // ==================== 跳过版本 ====================

    /**
     * 跳过指定版本, 不再提示. 
     */
    public void skipVersion(String pluginId, String version) {
        skippedVersions.put(pluginId, version);
        log.info("跳过插件 {} 版本 {}", pluginId, version);
    }

    // ==================== 插件管理 ====================

    /**
     * 启用/禁用插件(通过重命名 metadata.json 或标记). 
     * <p>
     * 简化实现：在插件目录创建 .disabled 标记文件. 
     */
    public boolean setPluginEnabled(String pluginId, boolean enabled) {
        Optional<PluginInfo> opt = getPlugin(pluginId);
        if (opt.isEmpty()) return false;

        File flagFile = new File(opt.get().pluginDir(), ".disabled");
        if (enabled) {
            boolean removed = flagFile.delete() || !flagFile.exists();
            if (removed) {
                pluginCache.put(pluginId, new PluginInfo(opt.get().id(), opt.get().name(),
                        opt.get().title(), opt.get().version(), opt.get().description(), opt.get().icon(),
                        opt.get().entry(),
                        opt.get().source(), opt.get().assets(), PluginStatus.NORMAL,
                        opt.get().pluginDir()));
                if (onPluginEnabled != null) onPluginEnabled.accept(pluginId);
                PluginProcessHandler ph = processHandler;
                if (ph != null) ph.onPluginEnabled(pluginId);
            }
            return removed;
        } else {
            try {
                flagFile.createNewFile();
                pluginCache.put(pluginId, new PluginInfo(opt.get().id(), opt.get().name(),
                        opt.get().title(), opt.get().version(), opt.get().description(), opt.get().icon(),
                        opt.get().entry(),
                        opt.get().source(), opt.get().assets(), PluginStatus.DISABLED,
                        opt.get().pluginDir()));
                if (onPluginDisabled != null) onPluginDisabled.accept(pluginId);
                PluginProcessHandler ph = processHandler;
                if (ph != null) ph.onPluginDisabled(pluginId);
                return true;
            } catch (Exception e) {
                log.warn("禁用插件 {} 失败", pluginId, e);
                return false;
            }
        }
    }

    /**
     * 插件是否已禁用.
     */
    public boolean isPluginDisabled(String pluginId) {
        Optional<PluginInfo> opt = getPlugin(pluginId);
        return opt.map(pluginInfo -> new File(pluginInfo.pluginDir(), ".disabled").exists()).orElse(true);
    }

    /**
     * 卸载插件 — 删除插件目录和下载缓存.
     *
     * @return true 表示卸载成功, false 表示插件不存在或正在运行
     */
    public boolean uninstallPlugin(String pluginId) {
        if (isPluginRunning(pluginId)) {
            log.warn("插件 {} 正在运行，无法卸载", pluginId);
            return false;
        }
        Optional<PluginInfo> opt = getPlugin(pluginId);
        if (opt.isEmpty()) return false;

        File pluginDir = opt.get().pluginDir();
        try {
            deleteRecursively(pluginDir.toPath());
            // 同时删除已下载的 zip
            File zip = FilePathUtil.getRelativeFile("plugins", "download", pluginId + ".zip");
            if (zip.isFile()) zip.delete();

            pluginCache.remove(pluginId);
            updateCache.remove(pluginId);
            downloadProgress.remove(pluginId);
            runningStatus.remove(pluginId);
            log.info("插件 {} 已卸载", pluginId);
            return true;
        } catch (Exception e) {
            log.warn("卸载插件 {} 失败", pluginId, e);
            return false;
        }
    }

    private void deleteRecursively(java.nio.file.Path path) throws java.io.IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (java.io.IOException e) {
                            log.warn("删除失败: {}", p, e);
                        }
                    });
        }
    }

}
