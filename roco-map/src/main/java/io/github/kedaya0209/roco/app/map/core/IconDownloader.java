package io.github.kedaya0209.roco.app.map.core;

import io.github.kedaya0209.roco.app.map.loader.LoadInfo;
import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import io.github.kedaya0209.roco.app.map.dto.MapCategoryItem;
import io.github.kedaya0209.roco.app.map.util.MapFileMover;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图标下载器
 * 集成进度上报与用户中断检测逻辑
 */
@Slf4j
@NotThreadSafe
public class IconDownloader {

    // 共享停止标记，实现全局一键取消
    @Getter
    private static final AtomicBoolean isStopRequested = new AtomicBoolean(false);
    private static final DownloadProgressContext progress = DownloadProgressContext.getInstance();


    /**
     * 设置停止标记
     */
    public static void stopDownload() {
        isStopRequested.set(true);
    }

    public static boolean downloadIcons() {
        isStopRequested.set(false); // 每次开始前重置状态

        progress.setStatusText("正在解析图标列表...");
        List<MapCategoryItem> list = LoadInfo.parseCategoryData();
        Set<String> urls = new HashSet<>();

        for (MapCategoryItem item : list) {
            String icon = item.getIcon();
            if (icon != null && !icon.isBlank()) {
                urls.add(icon);
            }
        }

        int total = urls.size();
        progress.reset("图标资源");
        for (int i = 0; i < total; i++) progress.addTask();

        Semaphore semaphore = new Semaphore(MapResourceUpdater.THREAD_COUNT);

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String url : urls) {
                futures.add(CompletableFuture.runAsync(() -> downloadIcon(url, semaphore), exec));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        return true;
    }

    private static void downloadIcon(String url, Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            return;               // 未获得许可，直接退出，不执行 finally
        }
        if (isStopRequested.get()) {
            log.warn("图标下载任务被用户取消");
            return;
        }
        try {
            String name = url.substring(url.lastIndexOf("/") + 1);
            progress.setStatusText("正在下载图标: " + name);

            File file = FilePathUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR, name);
            file.getParentFile().mkdirs();

            if (file.exists()) {
                progress.finishTask();
                return;
            }

            HttpURLConnection conn = (HttpURLConnection) new URI(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setRequestProperty("Referer", "https://wiki.biligame.com/");
            conn.setConnectTimeout(MapResourceUpdater.CONNECT_TIMEOUT);
            conn.setReadTimeout(MapResourceUpdater.READ);

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(file)) {

                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) != -1) {
                    // 下载大文件时也可以在这里加中断检测，但图标通常很小，没必要
                    out.write(buf, 0, len);
                }
            } finally {
                conn.disconnect();
            }

            log.info("⬇️  {}", name);
            MapFileMover.recordIconUrl(name, url);

            // 每完成一个图标，更新进度条
            progress.finishTask();

            Thread.sleep(MapResourceUpdater.ICON_DELAY_MS);

        } catch (IOException | InterruptedException | URISyntaxException e) {
            progress.finishTask(); // 失败也要推进进度条，否则会卡在 99%
            log.error("❌ 下载失败: {}", url);
        } finally {
            semaphore.release();
        }
    }
}