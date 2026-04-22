package com.luoke.app.map.core;

import com.luoke.app.config.AppConfig;
import com.luoke.app.map.LoadInfo;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.map.entity.DownloadResult;
import com.luoke.app.map.entity.Tile;
import com.luoke.app.map.util.MapFileMover;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

@Slf4j
public class MapDownloader {

    private static final Queue<int[]> taskQueue = new ConcurrentLinkedQueue<>();
    private static final Set<String> visited = ConcurrentHashMap.newKeySet();
    private static final List<Tile> validTiles = Collections.synchronizedList(new ArrayList<>());
    private static final List<byte[]> chunkBuffer = Collections.synchronizedList(new ArrayList<>());

    private static int chunkIndex = 0;
    private static int tileW = -1;
    private static int tileH = -1;

    public static void updateMap() {
        try {
            log.info("=====================================");
            log.info("开始执行地图瓦片更新任务");
            log.info("=====================================");

            downloadAllMaps();
            MapFileMover.moveMapsToResource();

            log.info("=====================================");
            log.info("✅ 地图资源全部更新完成！");
            log.info("=====================================");
        } catch (Exception e) {
            log.error("❌ 更新地图失败", e);
        }
    }

    private static void downloadAllMaps() {
        try {
            log.info("创建临时目录：{}", MapResourceUpdater.DOWNLOAD_MAP_DIR);
            Files.createDirectories(FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR).toPath());
            Files.createDirectories(FileUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR).toPath());

            if (AppConfig.MAP_REMOTE_URLS == null || AppConfig.MAP_REMOTE_URLS.length == 0) {
                log.info("未配置地图地址，自动从远程加载配置...");
                LoadInfo.remoteResolveConfig();
            }

            log.info("共加载到 {} 个地图需要处理", AppConfig.MAP_REMOTE_URLS.length);

            for (int i = 0; i < AppConfig.MAP_REMOTE_URLS.length; i++) {
                String tag = AppConfig.MAP_REMOTE_URL_NAME[i];
                File img = FileUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));

                log.info("-------------------------------------");
                log.info("开始处理地图：{}", tag);

                if (img.exists()) {
                    log.info("✅ 地图文件已存在，跳过下载：{}", tag);
                    continue;
                }

                log.info("重置下载状态...");
                resetState();

                log.info("加载历史元数据...");
                loadMeta(tag);

                log.info("加载历史失败记录...");
                loadFailed(tag);

                log.info("加载历史分片...");
                loadChunks();

                if (validTiles.isEmpty() && taskQueue.isEmpty()) {
                    log.info("从起点 (0,0) 开始下载瓦片...");
                    add(0, 0);
                }

                log.info("启动 {} 个虚拟线程下载瓦片...", MapResourceUpdater.THREAD_COUNT);
                try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Void>> list = new ArrayList<>();
                    for (int j = 0; j < MapResourceUpdater.THREAD_COUNT; j++) {
                        int fi = i;
                        list.add(CompletableFuture.runAsync(() -> worker(AppConfig.MAP_REMOTE_URLS[fi], tag), exec));
                    }
                    CompletableFuture.allOf(list.toArray(new CompletableFuture[0])).join();
                }

                if (!chunkBuffer.isEmpty()) {
                    log.info("保存剩余分片数据...");
                    saveChunk(tag);
                }

                log.info("保存瓦片元数据...");
                saveMeta(validTiles, tag);

                log.info("开始拼接地图图片：{}", tag);
                MapStitcher.stitch(validTiles, tag, tileW, tileH);

                log.info("✅ 地图处理完成：map_{}.png", tag);
            }

            log.info("=====================================");
            log.info("所有地图下载拼接完成，开始清理临时文件...");
            cleanTempFiles();

        } catch (Exception e) {
            log.error("❌ 地图下载流程发生异常", e);
        }
    }

    private static void cleanTempFiles() {
        try {
            File chunkDir = FileUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR);
            deleteDirectory(chunkDir);
            log.info("✅ 已清理临时分片目录：{}", chunkDir.getAbsolutePath());

            for (String name : AppConfig.MAP_REMOTE_URL_NAME) {
                File meta = FileUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, name));
                if (meta.exists()) meta.delete();

                File failed = FileUtil.getRelativeFile(String.format(MapResourceUpdater.FAILED_FILE, name));
                if (failed.exists()) failed.delete();
            }
            log.info("✅ 已清理所有元数据与失败记录");

        } catch (Exception e) {
            log.error("❌ 清理临时文件失败", e);
        }
    }

    private static void deleteDirectory(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteDirectory(file);
            }
        }
        dir.delete();
    }

    private static void worker(String url, String tag) {
        while (true) {
            int[] pos = taskQueue.poll();
            if (pos == null) break;

            int x = pos[0], y = pos[1];
            String key = x + "," + y;
            if (visited.contains(key)) continue;

            log.debug("下载瓦片：({}, {})", x, y);
            DownloadResult res = download(x, y, url);
            visited.add(key);

            if (res.isSuccess()) {
                detectSize(res.getData());
                validTiles.add(new Tile(x, y, res.getData()));
                chunkBuffer.add(res.getData());

                if (chunkBuffer.size() >= MapResourceUpdater.CHUNK_SIZE) {
                    log.debug("分片缓存已满，自动保存分片...");
                    saveChunk(tag);
                }

                add(x + 1, y);
                add(x - 1, y);
                add(x, y + 1);
                add(x, y - 1);
            } else if (!res.isNotFound()) {
                log.warn("下载失败：({}, {})", x, y);
            } else {
                log.debug("瓦片不存在(404)：({}, {})", x, y);
            }

            sleep(MapResourceUpdater.TILE_DELAY_MS);
        }
    }

    private static DownloadResult download(int x, int y, String tpl) {
        for (int i = 0; i < MapResourceUpdater.MAX_RETRY; i++) {
            HttpURLConnection conn = null;
            try {
                String url = tpl.replace("{x}", x + "").replace("{y}", y + "");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setConnectTimeout(MapResourceUpdater.CONNECT_TIMEOUT);
                conn.setReadTimeout(MapResourceUpdater.READ_TIMEOUT);

                int code = conn.getResponseCode();
                if (code == 404) {
                    return DownloadResult.notFound();
                }
                if (code != 200) {
                    log.warn("瓦片请求异常，响应码：{}，坐标({}, {})", code, x, y);
                    continue;
                }

                try (InputStream in = conn.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    return DownloadResult.success(data);
                }
            } catch (Exception e) {
                log.warn("瓦片下载异常，重试 {}/{}，坐标({}, {})", i + 1, MapResourceUpdater.MAX_RETRY, x, y);
                sleep(MapResourceUpdater.TILE_DELAY_MS * 2);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return DownloadResult.failed();
    }

    private static synchronized void add(int x, int y) {
        if (!visited.contains(x + "," + y)) {
            taskQueue.offer(new int[]{x, y});
        }
    }

    private static void detectSize(byte[] data) {
        if (tileW > 0) return;
        try {
            BufferedImage img = javax.imageio.ImageIO.read(new ByteArrayInputStream(data));
            if (img != null) {
                tileW = img.getWidth();
                tileH = img.getHeight();
                log.info("自动探测瓦片大小：{}x{}", tileW, tileH);
            }
        } catch (Exception e) {
            tileW = tileH = 256;
            log.warn("无法探测瓦片大小，使用默认值 256x256");
        }
    }

    private static void saveChunk(String tag) {
        if (chunkBuffer.isEmpty()) return;
        try {
            File f = FileUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR, tag + chunkIndex + ".chunk");
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
                oos.writeObject(chunkBuffer);
            }
            chunkBuffer.clear();
            chunkIndex++;
            log.debug("分片保存成功：{}", f.getName());
        } catch (Exception e) {
            log.error("❌ 分块保存失败", e);
        }
    }

    private static void loadMeta(String tag) {
        try {
            File f = FileUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, tag));
            if (!f.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    visited.add(line.trim());
                    count++;
                }
                log.info("加载历史元数据完成，共 {} 条记录", count);
            }
        } catch (Exception ignored) {
            log.warn("加载元数据失败");
        }
    }

    private static void loadFailed(String tag) {
        try {
            File f = FileUtil.getRelativeFile(String.format(MapResourceUpdater.FAILED_FILE, tag));
            if (!f.exists()) return;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                int count = 0;
                while ((line = br.readLine()) != null) {
                    visited.add(line.trim());
                    count++;
                }
                log.info("加载历史失败记录完成，共 {} 条", count);
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadChunks() {
        log.info("历史分片加载完成（无历史分片）");
    }

    private static void saveMeta(List<Tile> tiles, String tag) throws Exception {
        Collections.sort(tiles, Comparator.comparingInt(Tile::getX).thenComparingInt(Tile::getY));
        File f = FileUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, tag));
        try (PrintWriter pw = new PrintWriter(f)) {
            for (Tile t : tiles) pw.println(t.getX() + "," + t.getY());
        }
        log.info("元数据保存完成，共 {} 个瓦片", tiles.size());
    }

    private static void resetState() {
        taskQueue.clear();
        visited.clear();
        validTiles.clear();
        chunkBuffer.clear();
        chunkIndex = 0;
        tileW = tileH = -1;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}