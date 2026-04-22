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

@Slf4j
public class IconDownloader {

    public static void downloadIcons() {
        List<MapCategoryItem> list = LoadInfo.parseCategoryData();
        Set<String> urls = new HashSet<>();

        for (MapCategoryItem item : list) {
            String icon = item.getIcon();
            if (icon != null && !icon.isBlank()) {
                urls.add(icon);
            }
        }

        int success = 0, skip = 0, fail = 0;

        for (String url : urls) {
            try {
                String name = url.substring(url.lastIndexOf("/") + 1);
                File file = FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_ICON_DIR, name);

                if (file.exists()) {
                    skip++;
                    continue;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                conn.setRequestProperty("Referer", "https://wiki.biligame.com/");
                conn.setConnectTimeout(MapResourceUpdater.CONNECT_TIMEOUT);
                conn.setReadTimeout(MapResourceUpdater.READ_TIMEOUT);

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(file)) {

                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
                conn.disconnect();
                success++;
                log.info("⬇️  {}", name);

                Thread.sleep(MapResourceUpdater.ICON_DELAY_MS);

            } catch (Exception e) {
                fail++;
                log.error("❌ 下载失败: {}", url);
            }
        }

        log.info("=====================================");
        log.info("图标下载完成 | 成功：{} 跳过：{} 失败：{}", success, skip, fail);
        log.info("=====================================");
    }
}