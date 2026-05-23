package com.luoke.app.update;

import lombok.Setter;
import net.jcip.annotations.ThreadSafe;
import com.luoke.app.config.BuildConfig;
import com.luoke.app.config.UpdateConfig;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.utils.FilePathUtil;
import com.luoke.app.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 更新管理器 — 检查/下载/安装全流程编排。
 * <p>
 * 状态机: IDLE → CHECKING → DOWNLOADING → READY → INSTALLING → RESTARTING
 */
@Slf4j
@ThreadSafe
public class UpdateManager {

    private static volatile UpdateManager instance;

    /**
     * jsDelivr CDN 加速前缀（对应 patches 分支上的 updates/ 目录）
     */
    private static final String CDN_BASE = "https://cdn.jsdelivr.net/gh/kedaya0209/RocoMapTracker@patches/updates/";

    /**
     * gh-proxy.org 加速前缀（代理 GitHub 原始链接）
     */
    private static final String GHPROXY_PREFIX = "https://gh-proxy.org/";

    /**
     * 通过 schtasks 创建一次性计划任务启动脚本，进程独立于当前 JobObject。
     * 任务在当前用户会话中立即执行，杀毒软件不会将其标记为可疑行为。
     */
    private static final String UPDATE_TASK_NAME = "RocoMapTracker_Update";

    private final UpdateChecker checker;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);
    private final AtomicBoolean updateDialogShowing = new AtomicBoolean(false);

    private final AtomicReference<VersionInfo> pendingUpdate = new AtomicReference<>(null);
    @Setter
    private volatile UpdateUiDelegate uiDelegate;

    private UpdateManager() {
        this.checker = new UpdateChecker();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static UpdateManager getInstance() {
        if (instance == null) {
            synchronized (UpdateManager.class) {
                if (instance == null) {
                    instance = new UpdateManager();
                }
            }
        }
        return instance;
    }

    /**
     * 启动周期性更新检查（后台虚拟线程）
     */
    public void startPeriodicCheck(int intervalHours) {
        Thread.ofPlatform().daemon(true).name("update-checker").start(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                return;
            }
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    checkAndNotify();
                } catch (Exception e) {
                    log.warn("Periodic update check failed", e);
                } // 后台周期性检查，捕获所有异常避免线程终止
                try {
                    Thread.sleep(intervalHours * 3600_000L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });
    }

    /**
     * 手动检查更新
     */
    public void manualCheck(Runnable onResult) {
        Thread.ofPlatform().daemon(true).name("update-manual-check").start(() -> {
            checkAndNotify();
            if (onResult != null) onResult.run();
        });
    }

    private void checkAndNotify() {
        if (!checking.compareAndSet(false, true)) return;
        try {
            Optional<VersionInfo> result = checker.checkLatest();
            if (result.isEmpty()) {
                notify("检查更新失败，无法访问 GitHub", NotificationType.ERROR);
                return;
            }
            VersionInfo latest = result.get();
            if (!UpdateChecker.isNewer(BuildConfig.APP_VERSION, latest.version())) {
                notify("当前已是最新版本 (" + BuildConfig.APP_VERSION + ")", NotificationType.INFO);
                return;
            }
            pendingUpdate.set(latest);
            if (downloading.get()) {
                return;
            }
            if (UpdateConfig.AUTO_DOWNLOAD) {
                notify("发现新版本 " + latest.version() + "，开始自动下载", NotificationType.INFO);
                startDownload(latest);
            } else {
                if (uiDelegate != null && updateDialogShowing.compareAndSet(false, true)) {
                    uiDelegate.showUpdateAvailable(latest);
                }
            }
        } finally {
            checking.set(false);
        }
    }

    /**
     * 开始下载更新 — 带进度条 + 下载完毕弹窗（立即更新 / 下次再说）
     */
    public void startDownload(VersionInfo info) {
        updateDialogShowing.set(false);
        if (!downloading.compareAndSet(false, true)) {
            notify("正在下载中，请稍候", NotificationType.INFO);
            return;
        }
        final boolean[] isPatchDone = {false};
        final Path[] downloadPath = {null};
        Thread.ofPlatform().daemon(true).name("update-download").start(() -> {
            try {
                // 立即显示进度弹框，避免竞速/下载异常时用户看不到任何反馈
                uiDelegate.showDownloadProgress(info.version(), 0);

                String exeDir = FilePathUtil.getAppRootDir().toString();

                // ── 三源竞速，选出最快下载源 ──
                String raceUrl = info.exeDownloadUrl() != null
                        ? info.exeDownloadUrl() : info.patchDownloadUrl();
                List<String> sources = raceUrl != null
                        ? raceSources(raceUrl)
                        : List.of("gh-proxy", "jsdelivr", "github");

                // ── 尝试补丁下载 ──
                if (info.patchDownloadUrl() != null && isPatchVersionMatch(info)) {
                    Path patchPath = Path.of(exeDir, "update_" + info.version() + ".hdiff");
                    try {
                        if (!downloadWithFallback(info.patchDownloadUrl(), sources, patchPath, info.version())) {
                            throw new IOException("所有下载源均失败");
                        }
                        if (info.patchSha256Url() != null) {
                            verifySha256(patchPath, info.patchSha256Url());
                        }
                        if (Files.size(patchPath) > 0) {
                            downloadPath[0] = patchPath;
                            isPatchDone[0] = true;
                        } else {
                            Files.deleteIfExists(patchPath);
                        }
                    } catch (IOException | InterruptedException e) {
                        log.warn("补丁下载/校验失败，降级为完整 exe", e);
                        Files.deleteIfExists(patchPath);
                    }
                } else if (info.patchDownloadUrl() != null) {
                    log.info("补丁源版本 {} != 当前 {}, 跳过补丁",
                            info.patchFromVersion(), BuildConfig.APP_VERSION);
                }

                // ── 补丁失败 / 无补丁 → 完整 exe 下载 ──
                if (downloadPath[0] == null && info.exeDownloadUrl() != null) {
                    Path exePath = Path.of(exeDir, "RocoMapTracker_" + info.version() + ".exe");
                    uiDelegate.showDownloadProgress(info.version(), 0);

                    if (!downloadWithFallback(info.exeDownloadUrl(), sources, exePath, info.version())) {
                        throw new IOException("所有下载源均失败");
                    }
                    if (info.exeSha256Url() != null) {
                        verifySha256(exePath, info.exeSha256Url());
                    }
                    downloadPath[0] = exePath;
                }

                uiDelegate.hideDownloadProgress();

                // ── 下载完毕 → 弹窗让用户选择 ──
                if (downloadPath[0] != null) {
                    pendingUpdate.set(info);
                    Path dPath = downloadPath[0];
                    boolean isPatch = isPatchDone[0];
                    uiDelegate.showUpdateReadyDialog(info,
                            () -> Thread.ofPlatform().daemon(true).name("update-install").start(() -> {
                                if (isPatch) installAndRestart(dPath);
                                else installAndRestartExe(dPath);
                            }),
                            () -> log.info("用户选择稍后安装更新 {}", info.version()));
                } else {
                    notify("更新失败：未找到可下载的文件", NotificationType.ERROR);
                }
            } catch (IOException | InterruptedException e) {
                uiDelegate.hideDownloadProgress();
                log.error("Download failed", e);
                notify("下载更新失败：" + e.getMessage(), NotificationType.ERROR);
            } finally {
                downloading.set(false);
            }
        });
    }

    private void installAndRestart(Path patchPath) {
        String exeDir = FilePathUtil.getAppRootDir().toString();
        String exeName = "RocoMapTracker.exe";
        String exePath = exeDir + File.separator + exeName;
        String hpatchzPath = exeDir + File.separator + "update" + File.separator + "hpatchz.exe";

        File hpatchzFile = new File(hpatchzPath);
        if (!hpatchzFile.exists()) {
            hpatchzPath = exeDir + File.separator + "hpatchz.exe";
        }

        String script = generateUpdaterScript(exePath, patchPath.toString(), hpatchzPath, exeName);
        try {
            Path scriptPath = Path.of(exeDir, "updater_" + UUID.randomUUID().toString().substring(0, 8) + ".bat");
            Files.writeString(scriptPath, script);
            log.info("Starting updater script: {}", scriptPath);
            startScriptDetached(scriptPath.toString(), exeDir);
            if (uiDelegate != null) uiDelegate.restartApplication();
        } catch (IOException e) {
            log.error("Failed to start updater script", e);
            notify("启动更新程序失败：" + e.getMessage(), NotificationType.ERROR);
        }
    }

    private void installAndRestartExe(Path newExePath) {
        String exeDir = FilePathUtil.getAppRootDir().toString();
        String exeName = "RocoMapTracker.exe";
        String targetExe = exeDir + File.separator + exeName;

        String script = generateReplaceScript(newExePath.toString(), targetExe, exeName);
        try {
            Path scriptPath = Path.of(exeDir, "updater_" + UUID.randomUUID().toString().substring(0, 8) + ".bat");
            Files.writeString(scriptPath, script);
            log.info("Starting updater script: {}", scriptPath);
            startScriptDetached(scriptPath.toString(), exeDir);
            // 等待 updater 初始化完成再退出（确保 WMI 已创建独立进程）
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            if (uiDelegate != null) uiDelegate.restartApplication();
        } catch (IOException e) {
            log.error("Failed to start updater script", e);
            notify("启动更新程序失败：" + e.getMessage(), NotificationType.ERROR);
        }
    }

    /**
     * 以脱离 JobObject 的方式启动 updater 脚本，防止 Java 进程退出时
     * JobObject 的 KILL_ON_JOB_CLOSE 将脚本一同终止。
     * <p>
     * 策略：通过 schtasks 创建一次性计划任务（独立于 JobObject），
     * 降级使用 cmd /c start /min。
     */
    private void startScriptDetached(String scriptPath, String workDir) {
        // 通过 schtasks 创建一次性任务，进程独立于 JobObject
        try {
            if (startViaSchtasks(scriptPath, workDir)) {
                log.info("Updater script started via schtasks (independent process)");
                return;
            }
        } catch (Exception e) {
            log.warn("schtasks failed, fallback", e);
        }

        // 降级：start /min（可能被 JobObject 终止，但仍有几率成功）
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "/min", "\"\"", scriptPath)
                    .directory(new File(workDir))
                    .start();
            log.info("Updater script started via start /min (fallback)");
        } catch (IOException e) {
            log.error("Failed to start updater script (fallback)", e);
        }
    }


    private boolean startViaSchtasks(String scriptPath, String workDir) throws IOException, InterruptedException {
        // 1. 创建一次性任务（/f 覆盖同名任务，不会累积）
        LocalDateTime future = LocalDateTime.now().plusSeconds(5);
        String startTime = String.format("%02d:%02d", future.getHour(), future.getMinute());

        ProcessBuilder createPb = new ProcessBuilder(
                "schtasks.exe", "/create",
                "/tn", UPDATE_TASK_NAME,
                "/tr", "cmd.exe /c \"" + scriptPath + "\"",
                "/sc", "once",
                "/st", startTime,
                "/f",
                "/rl", "LIMITED"
        );
        if (workDir != null) {
            createPb.directory(new File(workDir));
        }
        if (createPb.start().waitFor() != 0) {
            log.warn("schtasks /create 失败");
            return false;
        }

        // 2. 立即触发任务（一次性任务执行完自动失效，不主动删除）
        new ProcessBuilder("schtasks.exe", "/run", "/tn", UPDATE_TASK_NAME)
                .start().waitFor();
        return true;
    }

    private String generateUpdaterScript(String exePath, String patchPath, String hpatchzPath, String exeName) {
        String backupPath = exePath + ".bak";
        String newExePath = exePath + ".new";
        String logPath = exePath + ".update.log";

        return "@echo off\r\n"
                + "setlocal enabledelayedexpansion\r\n"
                + "title RocoMapTracker Updater\r\n"
                + "set LOG=\"" + logPath + "\"\r\n"
                + "echo [%date% %time%] 脚本启动 > %LOG%\r\n"
                + "echo exePath=" + exePath + " >> %LOG%\r\n"
                + "echo patchPath=" + patchPath + " >> %LOG%\r\n"
                + "echo hpatchzPath=" + hpatchzPath + " >> %LOG%\r\n"
                + "cd /D \"%~dp0\"\r\n"
                + "echo [%date% %time%] 工作目录=%cd% >> %LOG%\r\n"
                + "\r\n"
                + ":WAIT\r\n"
                + "tasklist /FI \"IMAGENAME eq " + exeName + "\" 2>nul | find /I \"" + exeName + "\" >nul\r\n"
                + "if not errorlevel 1 (\r\n"
                + "    timeout /t 2 /nobreak >nul\r\n"
                + "    goto WAIT\r\n"
                + ")\r\n"
                + "echo [%date% %time%] 主程序已退出 >> %LOG%\r\n"
                + "\r\n"
                + ":: 备份原 exe\r\n"
                + "copy /Y \"" + exePath + "\" \"" + backupPath + "\" >nul\r\n"
                + "if errorlevel 1 (\r\n"
                + "    echo [%date% %time%] 备份失败 >> %LOG%\r\n"
                + "    goto FAIL\r\n"
                + ")\r\n"
                + "\r\n"
                + ":: 打补丁\r\n"
                + "echo [%date% %time%] 开始打补丁... >> %LOG%\r\n"
                + "\"" + hpatchzPath + "\" \"" + exePath + "\" \"" + patchPath + "\" \"" + newExePath + "\"\r\n"
                + "echo [%date% %time%] hpatchz 退出码=%errorlevel% >> %LOG%\r\n"
                + "if errorlevel 1 (\r\n"
                + "    copy /Y \"" + backupPath + "\" \"" + exePath + "\" >nul\r\n"
                + "    goto FAIL\r\n"
                + ")\r\n"
                + "\r\n"
                + ":: 替换\r\n"
                + "move /Y \"" + newExePath + "\" \"" + exePath + "\" >nul\r\n"
                + "if errorlevel 1 (\r\n"
                + "    copy /Y \"" + backupPath + "\" \"" + exePath + "\" >nul\r\n"
                + "    goto FAIL\r\n"
                + ")\r\n"
                + "\r\n"
                + ":: 清理\r\n"
                + "del \"" + patchPath + "\" 2>nul\r\n"
                + "del \"" + backupPath + "\" 2>nul\r\n"
                + "echo [%date% %time%] 更新成功 >> %LOG%\r\n"
                + "\r\n"
                + ":: 启动新版本\r\n"
                + "start \"\" \"" + exePath + "\"\r\n"
                + "del \"%~f0\"\r\n"
                + "exit /b 0\r\n"
                + "\r\n"
                + ":FAIL\r\n"
                + "echo [%date% %time%] 更新失败 >> %LOG%\r\n"
                + "del \"" + newExePath + "\" 2>nul\r\n"
                + "del \"" + backupPath + "\" 2>nul\r\n"
                + "pause\r\n"
                + "del \"%~f0\"\r\n"
                + "exit /b 1\r\n";
    }

    private String generateReplaceScript(String newExePath, String targetExe, String exeName) {
        String backupPath = targetExe + ".bak";

        return "@echo off\r\n"
                + "setlocal enabledelayedexpansion\r\n"
                + "title RocoMapTracker Updater\r\n"
                + "\r\n"
                + ":WAIT\r\n"
                + "tasklist /FI \"IMAGENAME eq " + exeName + "\" 2>nul | find /I \"" + exeName + "\" >nul\r\n"
                + "if not errorlevel 1 (\r\n"
                + "    timeout /t 2 /nobreak >nul\r\n"
                + "    goto WAIT\r\n"
                + ")\r\n"
                + "\r\n"
                + ":: 备份原 exe\r\n"
                + "copy /Y \"" + targetExe + "\" \"" + backupPath + "\" >nul\r\n"
                + "\r\n"
                + ":: 替换\r\n"
                + "move /Y \"" + newExePath + "\" \"" + targetExe + "\" >nul\r\n"
                + "if errorlevel 1 (\r\n"
                + "    copy /Y \"" + backupPath + "\" \"" + targetExe + "\" >nul\r\n"
                + "    msg \"RocoMapTracker\" \"更新失败，请手动下载新版本\"\r\n"
                + "    goto CLEANUP\r\n"
                + ")\r\n"
                + "\r\n"
                + ":: 启动新版本\r\n"
                + "start \"\" \"" + targetExe + "\"\r\n"
                + "\r\n"
                + ":CLEANUP\r\n"
                + "del \"" + backupPath + "\" 2>nul\r\n"
                + "del \"%~f0\"\r\n"
                + "exit /b 0\r\n";
    }

    private void downloadFile(String url, Path targetPath) throws IOException, InterruptedException {
        downloadFile(url, targetPath, null);
    }

    /**
     * 最大重试次数（瞬态网络错误时自动重试）
     */
    private static final int MAX_DOWNLOAD_RETRIES = 3;

    private void downloadFile(String url, Path targetPath, Consumer<Double> progressCallback) throws IOException, InterruptedException {
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                // 完整的浏览器 User-Agent
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                // 通用的 Accept
                .header("Accept", "*/*")
                // 支持压缩（服务器可能会拒绝不支持的客户端）
                .header("Accept-Encoding", "gzip, deflate, br")
                // 支持的语言
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                // 不缓存（下载大文件时推荐）
                .header("Cache-Control", "no-cache")
                .GET()
                .build();

        // 对瞬态网络错误（Connection reset / SocketException 等）自动重试
        HttpResponse<InputStream> response = null;
        IOException lastException = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_RETRIES; attempt++) {
            try {
                response = httpClient.send(request,
                        HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    throw new IOException("Download failed with status: " + response.statusCode());
                }

                // 成功
                lastException = null;
                break;
            } catch (IOException e) {
                lastException = e;
                if (attempt < MAX_DOWNLOAD_RETRIES) {
                    long delay = (long) Math.pow(2, attempt) * 1000L; // 2s, 4s
                    log.warn("下载失败（第 {}/{} 次），{} 秒后重试: {}", attempt, MAX_DOWNLOAD_RETRIES, delay / 1000, e.getMessage());
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        throw ie;
                    }
                } else {
                    throw e; // 最后一次仍失败，向上抛
                }
            }
        }
        if (lastException != null) throw lastException;

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        long bytesReadSoFar = 0;
        long lastUpdateTime = 0;
        // 最多每秒 20 次更新，避免 Platform.runLater 队列积压
        long minInterval = 50;

        try (InputStream is = response.body();
             OutputStream os = new BufferedOutputStream(new FileOutputStream(targetPath.toFile()))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                bytesReadSoFar += bytesRead;
                if (totalBytes > 0 && progressCallback != null) {
                    long now = System.nanoTime();
                    if (now - lastUpdateTime >= minInterval * 1_000_000L || bytesReadSoFar == totalBytes) {
                        lastUpdateTime = now;
                        progressCallback.accept((double) bytesReadSoFar / totalBytes);
                    }
                }
            }
            os.flush();
        }
    }

    /**
     * 检查补丁的源版本号是否匹配当前本地版本
     */
    private boolean isPatchVersionMatch(VersionInfo info) {
        return info.patchFromVersion() != null
                && info.patchFromVersion().equals(BuildConfig.APP_VERSION);
    }

    /**
     * 将 GitHub Release 下载 URL 转换为 jsDelivr CDN URL
     */
    private String toCdnUrl(String githubUrl) {
        if (githubUrl == null) return null;
        int lastSlash = githubUrl.lastIndexOf('/');
        if (lastSlash < 0) return null;
        return CDN_BASE + githubUrl.substring(lastSlash + 1);
    }

    /**
     * 将 GitHub Release 下载 URL 转换为 gh-proxy.org 代理 URL
     */
    private String toGhProxyUrl(String githubUrl) {
        if (githubUrl == null) return null;
        return GHPROXY_PREFIX + githubUrl;
    }

    /**
     * 三源同时下载 5 秒竞速，选出吞吐量最高的源，按速度降序返回。
     * 若所有源均失败，返回默认顺序。
     */
    private List<String> raceSources(String githubUrl) {
        List<String> allSources = List.of("gh-proxy", "jsdelivr", "github");
        String[] urls = {
                toGhProxyUrl(githubUrl),
                toCdnUrl(githubUrl),
                githubUrl
        };
        AtomicLong[] downloaded = {new AtomicLong(), new AtomicLong(), new AtomicLong()};
        Future<?>[] futures = new Future[3];

        ExecutorService pool = Executors.newFixedThreadPool(3);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            futures[i] = pool.submit(() -> {
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(urls[idx]))
                            .timeout(Duration.ofSeconds(10))
                            .GET()
                            .build();
                    HttpResponse<InputStream> resp = httpClient.send(req,
                            HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() / 100 != 2) return;
                    try (InputStream is = resp.body()) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            downloaded[idx].addAndGet(n);
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }

        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 取消所有下载
        for (Future<?> f : futures) {
            f.cancel(true);
        }
        pool.shutdownNow();

        // 按已下载字节数降序排列
        List<String> sorted = new ArrayList<>();
        List<int[]> indexed = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            indexed.add(new int[]{i, (int) downloaded[i].get()});
        }
        indexed.sort((a, b) -> Integer.compare(b[1], a[1]));
        for (int[] entry : indexed) {
            sorted.add(allSources.get(entry[0]));
            log.info("测速结果: {} — {}KB/5s", allSources.get(entry[0]), entry[1] / 1024);
        }
        return sorted;
    }

    /**
     * 竞速选出最快源后依次尝试下载，任一成功即返回 true。
     */
    private boolean downloadWithFallback(String githubUrl, List<String> sources,
            Path target, String version) throws IOException, InterruptedException {
        for (String source : sources) {
            String url = switch (source) {
                case "gh-proxy" -> toGhProxyUrl(githubUrl);
                case "jsdelivr" -> toCdnUrl(githubUrl);
                default -> githubUrl;
            };
            if (url == null) continue;
            try {
                log.info("尝试下载源 {}: {}", source, url);
                downloadFile(url, target, p ->
                        uiDelegate.showDownloadProgress(version, p));
                return true;
            } catch (IOException | InterruptedException e) {
                log.warn("下载源 {} 失败: {}", source, e.getMessage());
                Files.deleteIfExists(target);
            }
        }
        return false;
    }

    /**
     * 下载 SHA256 校验文件并验证文件完整性（通过代理加速）
     */
    private void verifySha256(Path filePath, String sha256Url) throws IOException, InterruptedException {
        Path sha256Path = Path.of(filePath + ".sha256");
        try {
            String proxyUrl = toGhProxyUrl(sha256Url);
            try {
                downloadFile(proxyUrl, sha256Path);
            } catch (IOException e) {
                log.warn("SHA256 通过 gh-proxy 下载失败，降级直连: {}", e.getMessage());
                Files.deleteIfExists(sha256Path);
                downloadFile(sha256Url, sha256Path);
            }
            String expected = Files.readString(sha256Path).trim();
            String actual = HashUtil.computeFileSHA256(filePath.toFile());
            if (!expected.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(filePath);
                throw new IOException("SHA256 校验失败：期望 " + expected + "，实际 " + actual);
            }
            log.info("SHA256 校验通过：{}", filePath.getFileName());
        } finally {
            Files.deleteIfExists(sha256Path);
        }
    }

    private void notify(String message, NotificationType type) {
        if (uiDelegate != null) {
            uiDelegate.showNotification(message, type);
        } else {
            log.info("[{}] {}", type, message);
        }
    }

    public VersionInfo getPendingUpdate() {
        return pendingUpdate.get();
    }

    public boolean hasPendingUpdate() {
        return pendingUpdate.get() != null;
    }

    public void resetUpdateDialogShowing() {
        updateDialogShowing.set(false);
    }
}
