package com.luoke.app.map.core;

import com.luoke.app.config.DownloadConfig;
import com.luoke.app.map.loader.LoadInfo;
import com.luoke.app.map.MapResourceUpdater;
import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.map.dto.DownloadResult;
import com.luoke.app.map.dto.Tile;
import com.luoke.app.utils.FilePathUtil;
import com.luoke.app.utils.PngUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 地图瓦片下载器
 * 采用 BFS 自动探测边界 + Java 21 虚拟线程并发 + 进度监控
 */
@Slf4j
@NotThreadSafe
public class MapDownloader {

    private static final Queue<int[]> taskQueue = new ConcurrentLinkedQueue<>();
    private static final Set<String> visited = ConcurrentHashMap.newKeySet();
    private static final List<Tile> validTiles = Collections.synchronizedList(new ArrayList<>());
    private static final List<byte[]> chunkBuffer = Collections.synchronizedList(new ArrayList<>());
    @Getter
    private static final AtomicBoolean isStopRequested = new AtomicBoolean(false);
    private static final DownloadProgressContext progress = DownloadProgressContext.getInstance();
    private static int chunkIndex = 0;
    private static int tileW = -1;
    private static int tileH = -1;

    public static boolean updateMap() {
        try {
            log.info("开始地图资源更新流程...");
            downloadAllMaps();
            if (isStopRequested.get()) {
                return false;
            }
            log.info("✅ 所有任务已圆满完成！");
            return true;
        } catch (RuntimeException e) {
            log.error("❌ 更新地图流程发生崩溃", e);
            return false;
        }
    }

    private static void downloadAllMaps() {
        try {
            // 1. 准备目录
            Files.createDirectories(FilePathUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR).toPath());
            Files.createDirectories(FilePathUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR).toPath());

            if (DownloadConfig.MAP_REMOTE_URLS == null || DownloadConfig.MAP_REMOTE_URLS.length == 0) {
                LoadInfo.remoteResolveConfig();
            }

            // 2. 遍历地图配置
            CountDownLatch latch = new CountDownLatch(DownloadConfig.MAP_REMOTE_URLS.length);
            for (int i = 0; i < DownloadConfig.MAP_REMOTE_URLS.length; i++) {
                String tag = DownloadConfig.MAP_REMOTE_URL_NAME[i];
                String urlTpl = DownloadConfig.MAP_REMOTE_URLS[i];
                File targetImg = FilePathUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));

                if (targetImg.exists()) {
                    log.info("跳过已存在的地图: {}", tag);
                    latch.countDown();
                    continue;
                }

                // 重置状态（下载阶段使用静态字段，拼接阶段使用快照，互不干扰）
                resetState();
                progress.reset("正在下载地图: " + tag);

                // 加载历史数据（断点续传支持）
                loadMeta(tag);

                // 确定 BFS 起点
                if (validTiles.isEmpty() && taskQueue.isEmpty()) {
                    add(0, 0);
                    progress.addTask();
                }

                log.info("启动虚拟线程下载池，并发数: {}", MapResourceUpdater.THREAD_COUNT);
                try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (int j = 0; j < MapResourceUpdater.THREAD_COUNT; j++) {
                        futures.add(CompletableFuture.runAsync(() -> worker(urlTpl, tag), exec));
                    }
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                }
                if (isStopRequested.get()) {
                    return;
                }

                // 3. 快照拼接所需状态，异步拼接（与下一张地图下载重叠）
                ArrayList<Tile> clone = new ArrayList<>(validTiles);
                int fTileW = tileW, fTileH = tileH;
                ArrayList<byte[]> fChunk = new ArrayList<>(chunkBuffer);
                chunkBuffer.clear();
                int fChunkIdx = chunkIndex;
                String fTag = tag;

                Thread.ofVirtual().start(() -> {
                    if (!fChunk.isEmpty()) {
                        File f = FilePathUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR, fTag + "_" + fChunkIdx + ".chunk");
                        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
                            oos.writeObject(fChunk);
                        } catch (IOException e) {
                            log.error("分片写入失败", e);
                        }
                    }
                    saveMeta(clone, fTag);
                    MapStitcher.stitch(clone, fTag, fTileW, fTileH);
                    latch.countDown();
                });
            }
            latch.await();
            // 4. 清理
            cleanTempFiles();
            resetState(); // 释放下载过程中积累的集合内存
        } catch (IOException | InterruptedException e) {
            log.error("下载流程中断", e);
        }
    }

    public static void stopDownload() {
        isStopRequested.set(true);
        log.info("用户请求停止下载任务");
    }

    private static void worker(String url, String tag) {
        while (true) {
            if (isStopRequested.get()) {
                log.warn("检测到停止请求，worker 线程退出");
                break;
            }

            int[] pos = taskQueue.poll();
            if (pos == null) break;

            int x = pos[0], y = pos[1];
            String key = x + "," + y;

            if (visited.contains(key)) {
                progress.finishTask();
                continue;
            }

            DownloadResult res = download(x, y, url);
            visited.add(key);

            if (res.isSuccess()) {
                detectSize(res.getData());
                validTiles.add(new Tile(x, y, res.getData()));
                chunkBuffer.add(res.getData());

                if (chunkBuffer.size() >= MapResourceUpdater.CHUNK_SIZE) {
                    saveChunk(tag);
                }

                // BFS 向四周探测
                expand(x + 1, y);
                expand(x - 1, y);
                expand(x, y + 1);
                expand(x, y - 1);
            }

            progress.finishTask();
            sleep(MapResourceUpdater.TILE_DELAY_MS);
        }
    }

    private static void expand(int x, int y) {
        String key = x + "," + y;
        if (!visited.contains(key)) {
            add(x, y);
            progress.addTask();
        }
    }

    private static DownloadResult download(int x, int y, String tpl) {
        for (int i = 0; i < MapResourceUpdater.MAX_RETRY; i++) {
            HttpURLConnection conn = null;
            try {
                String u = tpl.replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
                conn = (HttpURLConnection) new URL(u).openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                conn.setConnectTimeout(MapResourceUpdater.CONNECT_TIMEOUT);
                conn.setReadTimeout(MapResourceUpdater.READ);

                int code = conn.getResponseCode();
                if (code == 404) return DownloadResult.notFound();
                if (code != 200) continue;

                try (InputStream in = conn.getInputStream()) {
                    return DownloadResult.success(in.readAllBytes());
                }
            } catch (IOException e) {
                sleep(MapResourceUpdater.TILE_DELAY_MS * 2);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return DownloadResult.failed();
    }

    private static void saveChunk(String tag) {
        if (chunkBuffer.isEmpty()) return;
        File f = FilePathUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR, tag + "_" + chunkIndex + ".chunk");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject(new ArrayList<>(chunkBuffer));
            chunkBuffer.clear();
            chunkIndex++;
        } catch (IOException e) {
            log.error("分片写入失败", e);
        }
    }

    private static void loadMeta(String tag) {
        File f = FilePathUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, tag));
        if (!f.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                visited.add(line.trim());
            }
        } catch (IOException ignored) {
        }
    }

    private static void saveMeta(List<Tile> tiles, String tag) {
        File f = FilePathUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, tag));
        try (PrintWriter pw = new PrintWriter(f)) {
            for (Tile t : tiles) pw.println(t.x() + "," + t.y());
        } catch (IOException e) {
            log.error("元数据保存失败", e);
        }
    }

    private static void cleanTempFiles() {
        File chunkDir = FilePathUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR);
        deleteDir(chunkDir);
        log.info("临时缓存已清理");
    }

    private static void deleteDir(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static synchronized void add(int x, int y) {
        taskQueue.offer(new int[]{x, y});
    }

    private static void detectSize(byte[] data) {
        if (tileW > 0) return;
        int[] size = PngUtil.parseSize(data);
        if (size != null) {
            tileW = size[0];
            tileH = size[1];
        } else {
            tileW = tileH = 256;
        }
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