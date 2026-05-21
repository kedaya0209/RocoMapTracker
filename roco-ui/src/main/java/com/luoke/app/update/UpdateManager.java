package com.luoke.app.update;

import lombok.Setter;
import net.jcip.annotations.ThreadSafe;
import com.luoke.app.config.BuildConfig;
import com.luoke.app.config.UpdateConfig;
import com.luoke.app.hook.event.NotificationType;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** jsDelivr CDN 加速前缀（对应 patches 分支上的 updates/ 目录） */
    private static final String CDN_BASE = "https://cdn.jsdelivr.net/gh/kedaya0209/RocoMapTracker@patches/updates/";

    private final UpdateChecker checker;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    private final AtomicReference<VersionInfo> pendingUpdate = new AtomicReference<>(null);
    private volatile String appDir;
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
        this.appDir = detectAppDir();
        Thread.ofPlatform().daemon(true).name("update-checker").start(() -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { return; }
            while (!Thread.currentThread().isInterrupted()) {
                try { checkAndNotify(); } catch (Exception e) { log.warn("Periodic update check failed", e); } // 后台周期性检查，捕获所有异常避免线程终止
                try { Thread.sleep(intervalHours * 3600_000L); } catch (InterruptedException ignored) { break; }
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
            if (UpdateConfig.AUTO_DOWNLOAD) {
                notify("发现新版本 " + latest.version() + "，开始自动下载", NotificationType.INFO);
                startDownload(latest);
            } else {
                if (uiDelegate != null) {
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
        if (!downloading.compareAndSet(false, true)) {
            notify("正在下载中，请稍候", NotificationType.INFO);
            return;
        }
        final boolean[] isPatchDone = {false};
        final Path[] downloadPath = {null};
        Thread.ofPlatform().daemon(true).name("update-download").start(() -> {
            try {
                String exeDir = detectAppDir();

                // ── 按下载源优先级顺序（github / jsdelivr） ──
                boolean githubFirst = !"jsdelivr".equals(UpdateConfig.DOWNLOAD_SOURCE);

                // ── 尝试补丁下载 ──
                if (info.patchDownloadUrl() != null && isPatchVersionMatch(info)) {
                    Path patchPath = Path.of(exeDir, "update_" + info.version() + ".hdiff");
                    try {
                        uiDelegate.showDownloadProgress(info.version(), 0);

                        String primaryPatch = githubFirst ? info.patchDownloadUrl() : toCdnUrl(info.patchDownloadUrl());
                        String secondaryPatch = githubFirst ? toCdnUrl(info.patchDownloadUrl()) : info.patchDownloadUrl();
                        try {
                            downloadFile(primaryPatch, patchPath, p ->
                                    uiDelegate.showDownloadProgress(info.version(), p));
                        } catch (IOException | InterruptedException e) {
                            log.warn("首选源补丁下载失败，降级: {}", e.getMessage());
                            Files.deleteIfExists(patchPath);
                            if (secondaryPatch != null) {
                                downloadFile(secondaryPatch, patchPath, p ->
                                        uiDelegate.showDownloadProgress(info.version(), p));
                            } else {
                                throw e;
                            }
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

                    String primaryExe = githubFirst ? info.exeDownloadUrl() : toCdnUrl(info.exeDownloadUrl());
                    String secondaryExe = githubFirst ? toCdnUrl(info.exeDownloadUrl()) : info.exeDownloadUrl();
                    try {
                        downloadFile(primaryExe, exePath, p ->
                                uiDelegate.showDownloadProgress(info.version(), p));
                    } catch (IOException | InterruptedException e) {
                        log.warn("首选源 exe 下载失败，降级: {}", e.getMessage());
                        Files.deleteIfExists(exePath);
                        if (secondaryExe != null) {
                            downloadFile(secondaryExe, exePath, p ->
                                    uiDelegate.showDownloadProgress(info.version(), p));
                        } else {
                            throw e;
                        }
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
        String exeDir = detectAppDir();
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
            // 等待 updater 初始化完成再退出（确保 WMI 已创建独立进程）
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            if (uiDelegate != null) uiDelegate.restartApplication();
        } catch (IOException e) {
            log.error("Failed to start updater script", e);
            notify("启动更新程序失败：" + e.getMessage(), NotificationType.ERROR);
        }
    }

    private void installAndRestartExe(Path newExePath) {
        String exeDir = detectAppDir();
        String exeName = "RocoMapTracker.exe";
        String targetExe = exeDir + File.separator + exeName;

        String script = generateReplaceScript(newExePath.toString(), targetExe, exeName);
        try {
            Path scriptPath = Path.of(exeDir, "updater_" + UUID.randomUUID().toString().substring(0, 8) + ".bat");
            Files.writeString(scriptPath, script);
            log.info("Starting updater script: {}", scriptPath);
            startScriptDetached(scriptPath.toString(), exeDir);
            // 等待 updater 初始化完成再退出（确保 WMI 已创建独立进程）
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
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
     * 策略：优先通过 WMI (wmic) 创建独立进程（不在 JobObject 内），
     * 降级使用 cmd /c start /min。
     */
    private void startScriptDetached(String scriptPath, String workDir) {
        // 优先尝试 wmic（创建独立进程，脱离 JobObject）
        String wmicCmd = "wmic process call create \"cmd.exe /c " + scriptPath + "\"";
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", wmicCmd)
                    .directory(new File(workDir))
                    .start();
            // wmic 启动较慢，最多等 5 秒让脚本启动
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            log.info("Updater script started via wmic");
            return;
        } catch (IOException | InterruptedException e) {
            log.warn("wmic start failed, fallback to start /min", e);
        }

        // 降级：start /min 可能在 JobObject 内被终止，但仍有几率成功
        try {
            new ProcessBuilder("cmd.exe", "/c", "start", "/min", scriptPath)
                    .directory(new File(workDir))
                    .start();
            log.info("Updater script started via start /min (fallback)");
        } catch (IOException e) {
            log.error("Failed to start updater script (fallback)", e);
        }
    }

    private String generateUpdaterScript(String exePath, String patchPath, String hpatchzPath, String exeName) {
        String backupPath = exePath + ".bak";
        String newExePath = exePath + ".new";

        return "@echo off\r\n"
                + "setlocal enabledelayedexpansion\r\n"
                + "title RocoMapTracker Updater\r\n"
                + "cd /D \"%~dp0\"\r\n"
                + "\r\n"
                + ":WAIT\r\n"
                + "tasklist /FI \"IMAGENAME eq " + exeName + "\" 2>nul | find /I \"" + exeName + "\" >nul\r\n"
                + "if not errorlevel 1 (\r\n"
                + "    timeout /t 2 /nobreak >nul\r\n"
                + "    goto WAIT\r\n"
                + ")\r\n"
                + "\r\n"
                + ":: 备份原 exe\r\n"
                + "copy /Y \"" + exePath + "\" \"" + backupPath + "\" >nul\r\n"
                + "if errorlevel 1 goto FAIL\r\n"
                + "\r\n"
                + ":: 打补丁\r\n"
                + "\"" + hpatchzPath + "\" \"" + exePath + "\" \"" + patchPath + "\" \"" + newExePath + "\"\r\n"
                + "if errorlevel 1 (\r\n"
                + "    :: 补丁失败，回滚\r\n"
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
                + "\r\n"
                + ":: 启动新版本\r\n"
                + "start \"\" \"" + exePath + "\"\r\n"
                + "del \"%~f0\"\r\n"
                + "exit /b 0\r\n"
                + "\r\n"
                + ":FAIL\r\n"
                + "del \"" + newExePath + "\" 2>nul\r\n"
                + "del \"" + backupPath + "\" 2>nul\r\n"
                + "msg \"RocoMapTracker\" \"更新失败，请手动下载新版本\"\r\n"
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

    /** 最大重试次数（瞬态网络错误时自动重试） */
    private static final int MAX_DOWNLOAD_RETRIES = 3;

    private void downloadFile(String url, Path targetPath, Consumer<Double> progressCallback) throws IOException, InterruptedException {
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "RocoMapTracker")
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
                    try { Thread.sleep(delay); } catch (InterruptedException ie) { throw ie; }
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

    /** 检查补丁的源版本号是否匹配当前本地版本 */
    private boolean isPatchVersionMatch(VersionInfo info) {
        return info.patchFromVersion() != null
                && info.patchFromVersion().equals(BuildConfig.APP_VERSION);
    }

    /** 将 GitHub Release 下载 URL 转换为 jsDelivr CDN URL */
    private String toCdnUrl(String githubUrl) {
        if (githubUrl == null) return null;
        int lastSlash = githubUrl.lastIndexOf('/');
        if (lastSlash < 0) return null;
        return CDN_BASE + githubUrl.substring(lastSlash + 1);
    }

    /** 下载 SHA256 校验文件并验证文件完整性 */
    private void verifySha256(Path filePath, String sha256Url) throws IOException, InterruptedException {
        Path sha256Path = Path.of(filePath + ".sha256");
        try {
            downloadFile(sha256Url, sha256Path);
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

    private String detectAppDir() {
        if (appDir != null) return appDir;
        try {
            String path = UpdateManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().getPath();
            File jarFile = new File(path);
            appDir = jarFile.getParent();
            if (appDir == null) appDir = ".";
        } catch (Exception e) { // getProtectionDomain().getCodeSource() 可能因环境不同抛出多种异常
            appDir = ".";
        }
        return appDir;
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
}
