package com.luoke.app.update;

import com.luoke.app.config.BuildConfig;
import com.luoke.app.config.UpdateConfig;
import com.luoke.app.hook.event.NotificationType;
import com.luoke.app.utils.FileUtil;
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

/**
 * 更新管理器 — 检查/下载/安装全流程编排。
 * <p>
 * 状态机: IDLE → CHECKING → DOWNLOADING → READY → INSTALLING → RESTARTING
 */
@Slf4j
public class UpdateManager {

    private static volatile UpdateManager instance;

    private final UpdateChecker checker;
    private final HttpClient httpClient;
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private final AtomicBoolean downloading = new AtomicBoolean(false);

    private final AtomicReference<VersionInfo> pendingUpdate = new AtomicReference<>(null);
    private volatile String appDir;
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

    public void setUiDelegate(UpdateUiDelegate delegate) {
        this.uiDelegate = delegate;
    }

    /**
     * 启动周期性更新检查（后台虚拟线程）
     */
    public void startPeriodicCheck(int intervalHours) {
        this.appDir = detectAppDir();
        Thread.ofVirtual().name("update-checker").start(() -> {
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { return; }
            while (!Thread.currentThread().isInterrupted()) {
                try { checkAndNotify(); } catch (Exception e) { log.warn("Periodic update check failed", e); }
                try { Thread.sleep(intervalHours * 3600_000L); } catch (InterruptedException ignored) { break; }
            }
        });
    }

    /**
     * 手动检查更新
     */
    public void manualCheck(Runnable onResult) {
        Thread.ofVirtual().name("update-manual-check").start(() -> {
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
            if (uiDelegate != null) {
                if (UpdateConfig.AUTO_DOWNLOAD) {
                    notify("正在下载 " + latest.version() + " ...", NotificationType.INFO);
                    startDownload(latest);
                } else {
                    uiDelegate.showUpdateAvailable(latest);
                }
            }
        } finally {
            checking.set(false);
        }
    }

    /**
     * 开始下载更新
     */
    public void startDownload(VersionInfo info) {
        if (!downloading.compareAndSet(false, true)) {
            notify("正在下载中，请稍候", NotificationType.INFO);
            return;
        }
        Thread.ofVirtual().name("update-download").start(() -> {
            try {
                String exeDir = detectAppDir();
                Path patchPath = Path.of(exeDir, "update_" + info.version() + ".hdiff");

                boolean patchOk = false;
                if (info.patchDownloadUrl() != null && isPatchVersionMatch(info)) {
                    try {
                        downloadFile(info.patchDownloadUrl(), patchPath);
                        if (info.patchSha256Url() != null) {
                            verifySha256(patchPath, info.patchSha256Url());
                        }
                        if (Files.size(patchPath) > 0) patchOk = true;
                        else Files.deleteIfExists(patchPath);
                    } catch (Exception e) {
                        log.warn("Patch download/verify failed, will fallback to full exe", e);
                        Files.deleteIfExists(patchPath);
                    }
                } else if (info.patchDownloadUrl() != null) {
                    log.info("Patch source {} != current {}, skipping patch",
                            info.patchFromVersion(), BuildConfig.APP_VERSION);
                }

                if (patchOk) {
                    pendingUpdate.set(info);
                    notify("补丁下载完成，准备更新 " + info.version(), NotificationType.SUCCESS);
                    installAndRestart(patchPath);
                } else if (info.exeDownloadUrl() != null) {
                    Path exePath = Path.of(exeDir, "RocoMapTracker_" + info.version() + ".exe");
                    notify("正在下载完整安装包 ...", NotificationType.INFO);
                    downloadFile(info.exeDownloadUrl(), exePath);
                    if (info.exeSha256Url() != null) {
                        verifySha256(exePath, info.exeSha256Url());
                    }
                    pendingUpdate.set(info);
                    notify("下载完成，准备更新 " + info.version(), NotificationType.SUCCESS);
                    installAndRestartExe(exePath);
                } else {
                    notify("更新失败：未找到可下载的文件", NotificationType.ERROR);
                }
            } catch (Exception e) {
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
            new ProcessBuilder("cmd.exe", "/c", "start", "/min", scriptPath.toString())
                    .directory(new File(exeDir))
                    .start();
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
            new ProcessBuilder("cmd.exe", "/c", "start", "/min", scriptPath.toString())
                    .directory(new File(exeDir))
                    .start();
            if (uiDelegate != null) uiDelegate.restartApplication();
        } catch (IOException e) {
            log.error("Failed to start updater script", e);
            notify("启动更新程序失败：" + e.getMessage(), NotificationType.ERROR);
        }
    }

    private String generateUpdaterScript(String exePath, String patchPath, String hpatchzPath, String exeName) {
        String backupPath = exePath + ".bak";
        String newExePath = exePath + ".new";

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
        Path parent = targetPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", "RocoMapTracker")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("Download failed with status: " + response.statusCode());
        }

        try (InputStream is = response.body();
             OutputStream os = new BufferedOutputStream(new FileOutputStream(targetPath.toFile()))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        }
    }

    /** 检查补丁的源版本号是否匹配当前本地版本 */
    private boolean isPatchVersionMatch(VersionInfo info) {
        return info.patchFromVersion() != null
                && info.patchFromVersion().equals(BuildConfig.APP_VERSION);
    }

    /** 下载 SHA256 校验文件并验证文件完整性 */
    private void verifySha256(Path filePath, String sha256Url) throws IOException, InterruptedException {
        Path sha256Path = Path.of(filePath + ".sha256");
        try {
            downloadFile(sha256Url, sha256Path);
            String expected = Files.readString(sha256Path).trim();
            String actual = FileUtil.computeFileSHA256(filePath.toFile());
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
        } catch (Exception e) {
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
