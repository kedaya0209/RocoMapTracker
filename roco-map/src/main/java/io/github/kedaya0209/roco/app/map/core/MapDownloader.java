package io.github.kedaya0209.roco.app.map.core;

import io.github.kedaya0209.roco.app.config.DownloadConfig;
import io.github.kedaya0209.roco.app.map.loader.LoadInfo;
import io.github.kedaya0209.roco.app.map.MapResourceUpdater;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.map.dto.DownloadResult;
import io.github.kedaya0209.roco.app.map.dto.Tile;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.PngUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 地图瓦片下载器
 * 四向探测矩形边界 + 批量入队 + Java 21 虚拟线程并发 + 进度监控
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

                // 四向探测法找到矩形边界，然后填充所有瓦片
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(MapResourceUpdater.CONNECT_TIMEOUT))
                        .build();
                if (validTiles.isEmpty() && taskQueue.isEmpty()) {
                    detectBounds(client, urlTpl, tag);
                }

                log.info("启动虚拟线程下载池，并发数: {}", MapResourceUpdater.THREAD_COUNT);
                try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Void>> futures = new ArrayList<>();
                    for (int j = 0; j < MapResourceUpdater.THREAD_COUNT; j++) {
                        HttpClient c = client;
                        futures.add(CompletableFuture.runAsync(() -> worker(c, urlTpl, tag), exec));
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
                        f.getParentFile().mkdirs();
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
            // validTiles 中所有 tile byte[] 和全图拼接 BufferedImage，全部回收
            System.gc();
        } catch (IOException | InterruptedException e) {
            log.error("下载流程中断", e);
        }
    }

    public static void stopDownload() {
        isStopRequested.set(true);
        log.info("用户请求停止下载任务");
    }

    private static void worker(HttpClient client, String url, String tag) {
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

            DownloadResult res = download(client, x, y, url);
            visited.add(key);

            if (res.isSuccess()) {
                detectSize(res.getData());
                validTiles.add(new Tile(x, y, res.getData()));
                chunkBuffer.add(res.getData());

                if (chunkBuffer.size() >= MapResourceUpdater.CHUNK_SIZE) {
                    saveChunk(tag);
                }
            }

            progress.finishTask();
        }
    }

    /**
     * 四向并行探测地图矩形边界，将所有瓦片加入下载队列。
     * 指数探测 + 二分查找确定边界，再填充全部瓦片坐标到 taskQueue。
     */
    private static void detectBounds(HttpClient client, String urlTpl, String tag) {
        // 四个方向并行探测
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        int maxX, minX, maxY, minY;
        try {
            CompletableFuture<Integer> fMaxX = CompletableFuture.supplyAsync(() -> probeAxis(client, urlTpl, 1, 0, exec), exec);
            CompletableFuture<Integer> fMinX = CompletableFuture.supplyAsync(() -> -probeAxis(client, urlTpl, -1, 0, exec), exec);
            CompletableFuture<Integer> fMaxY = CompletableFuture.supplyAsync(() -> probeAxis(client, urlTpl, 0, 1, exec), exec);
            CompletableFuture<Integer> fMinY = CompletableFuture.supplyAsync(() -> -probeAxis(client, urlTpl, 0, -1, exec), exec);
            CompletableFuture.allOf(fMaxX, fMinX, fMaxY, fMinY).join();
            maxX = fMaxX.join();
            minX = fMinX.join();
            maxY = fMaxY.join();
            minY = fMinY.join();
        } finally {
            exec.shutdown();
        }

        log.info("地图 [{}] 边界: X[{},{}] Y[{},{}] 总计 {} 瓦片",
                tag, minX, maxX, minY, maxY,
                (maxX - minX + 1L) * (maxY - minY + 1));

        // 将矩形内所有未下载瓦片加入队列
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                if (!visited.contains(x + "," + y)) {
                    taskQueue.offer(new int[]{x, y});
                    progress.addTask();
                }
            }
        }
    }

    /**
     * 沿 (dx,dy) 方向指数探测 + 并行三叉搜索边界。
     * 指数阶段从 1 开始翻倍直到 404；三叉阶段每轮同时探 3 个点，范围缩小 75%。
     * 返回从原点出发沿该方向的最大有效偏移量（正数）。
     */
    private static int probeAxis(HttpClient client, String urlTpl, int dx, int dy, Executor exec) {
        int lo = 0;  // 最后一个已知有效偏移
        int hi = 1;  // 第一个可能无效的偏移
        while (probeOne(client, urlTpl, dx * hi, dy * hi)) {
            lo = hi;
            hi *= 2;
        }
        // 并行三叉搜索：每轮 3 个点同时探，快速缩小范围
        while (hi - lo > 1) {
            int span = hi - lo;
            if (span <= 4) {
                // 小范围退化线性扫描
                for (int i = lo + 1; i < hi; i++) {
                    if (!probeOne(client, urlTpl, dx * i, dy * i)) break;
                    lo = i;
                }
                break;
            }
            int m1 = lo + span / 4;
            int m2 = lo + span / 2;
            int m3 = lo + span * 3 / 4;
            // 3 点并行探测
            CompletableFuture<Boolean> f1 = CompletableFuture.supplyAsync(
                    () -> probeOne(client, urlTpl, dx * m1, dy * m1), exec);
            CompletableFuture<Boolean> f3 = CompletableFuture.supplyAsync(
                    () -> probeOne(client, urlTpl, dx * m3, dy * m3), exec);
            boolean r2 = probeOne(client, urlTpl, dx * m2, dy * m2);
            if (!f1.join()) {
                hi = m1;
            } else if (!r2) {
                lo = m1; hi = m2;
            } else if (!f3.join()) {
                lo = m2; hi = m3;
            } else {
                lo = m3;
            }
        }
        return lo;
    }

    /** 探测单个瓦片：下载并记录，返回 true 表示该瓦片存在 */
    private static boolean probeOne(HttpClient client, String urlTpl, int x, int y) {
        String key = x + "," + y;
        if (visited.contains(key)) return true;
        DownloadResult res = download(client, x, y, urlTpl);
        if (!res.isSuccess()) return false;
        visited.add(key);
        detectSize(res.getData());
        validTiles.add(new Tile(x, y, res.getData()));
        return true;
    }
    private static DownloadResult download(HttpClient client, int x, int y, String tpl) {
        try {
            String u = tpl.replace("{x}", String.valueOf(x)).replace("{y}", String.valueOf(y));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(u))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofMillis(MapResourceUpdater.READ))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = resp.statusCode();
            if (code == 404) return DownloadResult.notFound();
            if (code == 200) return DownloadResult.success(resp.body());
        } catch (IOException e) {
            // 单次失败不重试
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return DownloadResult.failed();
    }

    private static void saveChunk(String tag) {
        if (chunkBuffer.isEmpty()) return;
        File f = FilePathUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR, tag + "_" + chunkIndex + ".chunk");
        f.getParentFile().mkdirs();
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
        f.getParentFile().mkdirs();
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

    private static void add(int x, int y) {
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

}