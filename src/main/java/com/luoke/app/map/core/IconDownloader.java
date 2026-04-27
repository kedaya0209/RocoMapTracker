package com.luoke.app.map.core;

import com.luoke.app.map.LoadInfo;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.dto.MapCategoryItem;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图标下载器 - 增强版
 * 集成了进度上报与用户中断检测逻辑
 */
@Slf4j
public class IconDownloader {

    // 共享 MapDownloader 的停止标记，实现全局一键取消
    private static final AtomicBoolean isStopRequested = new AtomicBoolean(false);
    private static final DownloadProgressContext progress = DownloadProgressContext.getInstance();

    public static AtomicBoolean getIsStopRequested() {
        return isStopRequested;
    }


    /**
     * 设置停止标记
     */
    public static void stopDownload() {
        isStopRequested.set(true);
    }

    public static void downloadIcons() {
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

        // 初始化进度：图标下载是已知总量的，所以直接设置总数
        int total = urls.size();
        progress.reset("图标资源");
        // 这里的逻辑与 BFS 不同，图标是静态列表，我们手动模拟 addTask
        for (int i = 0; i < total; i++) progress.addTask();

        int success = 0, skip = 0, fail = 0;

        for (String url : urls) {
            // --- 中断检测 ---
            if (isStopRequested.get()) {
                log.warn("图标下载任务被用户取消");
                break;
            }

            try {
                String name = url.substring(url.lastIndexOf("/") + 1);
                progress.setStatusText("正在下载图标: " + name);

                File file = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR, name);

                if (file.exists()) {
                    skip++;
                    progress.finishTask(); // 跳过也算完成一个任务
                    continue;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
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

                success++;
                log.info("⬇️  {}", name);

                // 每完成一个图标，更新进度条
                progress.finishTask();

                Thread.sleep(MapResourceUpdater.ICON_DELAY_MS);

            } catch (Exception e) {
                fail++;
                progress.finishTask(); // 失败也要推进进度条，否则会卡在 99%
                log.error("❌ 下载失败: {}", url);
            }
        }

        log.info("=====================================");
        log.info("图标下载完成 | 成功：{} 跳过：{} 失败：{}", success, skip, fail);
        log.info("=====================================");
    }
}