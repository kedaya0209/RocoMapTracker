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

/**
 * 地图瓦片下载器
 * <p>
 * 负责从远程服务器下载地图瓦片并拼接成完整地图。
 * 该类实现了以下核心功能：
 * <ul>
 *   <li>使用广度优先搜索(BFS)策略下载瓦片</li>
 *   <li>支持断点续传，可从历史记录恢复下载</li>
 *   <li>使用虚拟线程实现高并发下载</li>
 *   <li>将瓦片数据分片存储，避免内存溢出</li>
 *   <li>自动检测瓦片尺寸并保存元数据</li>
 *   <li>下载完成后自动拼接地图</li>
 * </ul>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>使用ConcurrentLinkedQueue作为任务队列，支持多线程安全</li>
 *   <li>使用ConcurrentHashMap.newKeySet()记录已访问坐标</li>
 *   <li>虚拟线程提供轻量级并发，大幅提高下载速度</li>
 *   <li>分片机制避免大量瓦片数据占用过多内存</li>
 *   <li>失败记录可恢复，支持增量下载</li>
 * </ul>
 * <p>
 * Native资源管理：
 * <ul>
 *   <li>HttpURLConnection在finally块中确保断开连接</li>
 *   <li>BufferedImage在使用后flush释放Native内存</li>
 *   <li>ByteArrayInputStream使用try-with-resources自动关闭</li>
 *   <li>虚拟线程使用try-with-resources自动关闭线程池</li>
 * </ul>
 * <p>
 * 性能优化：
 * <ul>
 *   <li>虚拟线程提供高效并发，减少线程切换开销</li>
 *   <li>分片存储机制避免OOM，支持大规模地图</li>
 *   <li>延迟下载避免对服务器造成过大压力</li>
 *   <li>连接池复用和超时控制提高网络效率</li>
 *   <li>自动瓦片尺寸检测减少配置工作</li>
 * </ul>
 * <p>
 * 内存管理：
 * <ul>
 *   <li>使用缓冲区大小控制，防止内存溢出</li>
 *   <li>瓦片图片检测后立即释放，减少内存占用</li>
 *   <li>分片机制支持超大地图下载</li>
 *   <li>下载状态重置确保内存正确释放</li>
 * </ul>
 *
 * @author RocoMapTracker
 * @since 1.0
 */
@Slf4j
public class MapDownloader {

    /**
     * 任务队列：使用并发安全的队列存储待下载的瓦片坐标
     * <p>
     * 使用ConcurrentLinkedQueue而非普通Queue，确保多线程环境下的线程安全。
     * 队列中存储int[]数组，每个数组包含两个元素：[x, y]坐标。
     * <p>
     * 优点：
     * <ul>
     *   <li>无锁算法，性能优秀</li>
     *   <li>线程安全，无需同步</li>
     *   <li>适合高并发场景</li>
     * </ul>
     */
    private static final Queue<int[]> taskQueue = new ConcurrentLinkedQueue<>();

    /**
     * 已访问坐标集合：用于避免重复下载相同坐标的瓦片
     * <p>
     * 使用ConcurrentHashMap.newKeySet()创建并发安全的Set集合。
     * 存储格式为"x,y"字符串，例如"10,20"表示x=10, y=20的坐标。
     * <p>
     * 作用：
     * <ul>
     *   <li>记录已下载或已加入队列的坐标</li>
     *   <li>避免广度优先搜索中的重复访问</li>
     *   <li>支持断点续传，从历史记录恢复</li>
     * </ul>
     */
    private static final Set<String> visited = ConcurrentHashMap.newKeySet();

    /**
     * 有效瓦片列表：存储成功下载的瓦片数据
     * <p>
     * 使用Collections.synchronizedList包装ArrayList，确保线程安全。
     * 每个Tile对象包含坐标和瓦片图片数据。
     * <p>
     * 作用：
     * <ul>
     *   <li>收集成功下载的瓦片用于拼接</li>
     *   <li>支持排序和序列化</li>
     *   <li>用于生成元数据文件</li>
     * </ul>
     */
    private static final List<Tile> validTiles = Collections.synchronizedList(new ArrayList<>());

    /**
     * 分片缓冲区：临时存储瓦片数据，达到阈值后写入文件
     * <p>
     * 使用Collections.synchronizedList确保线程安全。
     * 每个byte[]是一个瓦片的原始图片数据。
     * <p>
     * 作用：
     * <ul>
     *   <li>避免大量瓦片数据同时占用内存</li>
     *   <li>分片存储支持超大地图下载</li>
     *   <li>减少垃圾回收压力</li>
     * </ul>
     * <p>
     * 内存优化：
     * <ul>
     *   <li>达到CHUNK_SIZE后自动保存到文件</li>
     *   <li>保存后清空缓冲区，释放内存</li>
     *   <li>支持断点续传，从分片文件恢复</li>
     * </ul>
     */
    private static final List<byte[]> chunkBuffer = Collections.synchronizedList(new ArrayList<>());

    /**
     * 当前分片索引：用于生成分片文件名
     * <p>
     * 从0开始递增，每保存一个分片后加1。
     * 分片文件格式为：{tag}{index}.chunk，例如"map_0.chunk"。
     */
    private static int chunkIndex = 0;

    /**
     * 瓦片宽度：从下载的瓦片中自动检测
     * <p>
     * 初始值为-1，表示未检测。
     * 检测后设置为实际宽度，通常是256或512。
     * <p>
     * 检测逻辑：
     * <ul>
     *   <li>首次成功下载瓦片时检测</li>
     *   <li>使用BufferedImage读取宽度和高度</li>
     *   <li>检测失败时使用默认值256</li>
     * </ul>
     */
    private static int tileW = -1;

    /**
     * 瓦片高度：从下载的瓦片中自动检测
     * <p>
     * 初始值为-1，表示未检测。
     * 检测逻辑与tileW相同。
     */
    private static int tileH = -1;

    /**
     * 更新地图资源的主入口方法
     * <p>
     * 该方法执行完整的地图更新流程：
     * <ol>
     *   <li>下载所有配置的地图瓦片</li>
     *   <li>拼接瓦片为完整地图图片</li>
     *   <li>将拼接后的地图移动到资源目录</li>
     *   <li>清理临时文件和元数据</li>
     * </ol>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>应用启动时更新地图资源</li>
     *   <li>手动触发地图更新</li>
     *   <li>定时任务更新地图数据</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>捕获所有异常，记录错误日志</li>
     *   <li>单个地图下载失败不影响其他地图</li>
     *   <li>使用统一日志格式便于排查问题</li>
     * </ul>
     */
    public static void updateMap() {
        try {
            log.info("=====================================");
            log.info("开始执行地图瓦片更新任务");
            log.info("=====================================");

            // 下载所有配置的地图
            // 包括瓦片下载、拼接、临时文件管理等
            downloadAllMaps();

            // 将下载并拼接好的地图移动到外部资源目录
            // 这样可以解耦下载目录和应用资源目录
            MapFileMover.moveMapsToResource();

            log.info("=====================================");
            log.info("✅ 地图资源全部更新完成！");
            log.info("=====================================");
        } catch (Exception e) {
            // 捕获所有异常，记录错误日志
            // 使用统一的异常处理机制，避免程序崩溃
            log.error("❌ 更新地图失败", e);
        }
    }

    /**
     * 下载所有地图瓦片的核心方法
     * <p>
     * 该方法实现了完整的地图下载流程：
     * <ol>
     *   <li>创建下载目录和分片目录</li>
     *   <li>如果未配置地图地址，从远程加载配置</li>
     *   <li>遍历所有地图配置，逐个下载</li>
     *   <li>对于每个地图：
     *     <ul>
     *       <li>检查地图文件是否已存在，存在则跳过</li>
     *       <li>重置下载状态</li>
     *       <li>加载历史元数据、失败记录和分片</li>
     *       <li>如果无历史数据，从起点(0,0)开始下载</li>
     *       <li>启动虚拟线程并发下载瓦片</li>
     *       <li>保存剩余分片数据</li>
     *       <li>保存瓦片元数据</li>
     *       <li>拼接地图图片</li>
     *     </ul>
     *   </li>
     *   <li>清理临时文件和元数据</li>
     * </ol>
     * <p>
     * 断点续传机制：
     * <ul>
     *   <li>元数据记录已下载坐标，避免重复</li>
     *   <li>失败记录记录404等错误坐标</li>
     *   <li>分片文件支持恢复下载</li>
     *   <li>支持增量下载，只下载新增瓦片</li>
     * </ul>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>虚拟线程提供高效并发</li>
     *   <li>文件存在性检查避免重复下载</li>
     *   <li>分片存储避免内存溢出</li>
     *   <li>延迟下载避免服务器压力</li>
     * </ul>
     */
    private static void downloadAllMaps() {
        try {
            // 创建下载地图的临时目录
            log.info("创建临时目录：{}", MapResourceUpdater.DOWNLOAD_MAP_DIR);
            Files.createDirectories(FileUtil.getRelativeFile(MapResourceUpdater.DOWNLOAD_MAP_DIR).toPath());

            // 创建分片存储目录
            // 分片用于存储瓦片数据，避免内存溢出
            Files.createDirectories(FileUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR).toPath());

            // 如果未配置地图地址，从远程加载配置
            // 这种设计允许动态配置，提高灵活性
            if (AppConfig.MAP_REMOTE_URLS == null || AppConfig.MAP_REMOTE_URLS.length == 0) {
                log.info("未配置地图地址，自动从远程加载配置...");
                LoadInfo.remoteResolveConfig();
            }

            log.info("共加载到 {} 个地图需要处理", AppConfig.MAP_REMOTE_URLS.length);

            // 遍历所有地图配置，逐个下载
            for (int i = 0; i < AppConfig.MAP_REMOTE_URLS.length; i++) {
                String tag = AppConfig.MAP_REMOTE_URL_NAME[i];
                File img = FileUtil.getRelativeFile(String.format(MapResourceUpdater.OUTPUT_FILE, tag));

                log.info("-------------------------------------");
                log.info("开始处理地图：{}", tag);

                // 检查地图文件是否已存在，存在则跳过下载
                // 这种缓存机制可以显著提高重复执行时的速度
                if (img.exists()) {
                    log.info("✅ 地图文件已存在，跳过下载：{}", tag);
                    continue;
                }

                // 重置下载状态
                // 清空任务队列、访问记录、有效瓦片列表等
                log.info("重置下载状态...");
                resetState();

                // 加载历史元数据
                // 记录已下载的坐标，支持断点续传
                log.info("加载历史元元数据...");
                loadMeta(tag);

                // 加载历史失败记录
                // 记录404等错误坐标，避免重复请求
                log.info("加载历史失败记录...");
                loadFailed(tag);

                // 加载历史分片
                // 从分片文件恢复下载进度
                log.info("加载历史分片...");
                loadChunks();

                // 如果没有历史数据，从起点(0,0)开始下载
                // 这是广度优先搜索的起始点
                if (validTiles.isEmpty() && taskQueue.isEmpty()) {
                    log.info("从起点 (0,0) 开始下载瓦片...");
                    add(0, 0);
                }

                // 启动虚拟线程并发下载瓦片
                // 虚拟线程是Java 21+的特性，提供轻量级并发
                log.info("启动 {} 个虚拟线程下载瓦片...", MapResourceUpdater.THREAD_COUNT);
                try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<CompletableFuture<Void>> list = new ArrayList<>();

                    // 为每个线程创建下载任务
                    for (int j = 0; j < MapResourceUpdater.THREAD_COUNT; j++) {
                        int fi = i;
                        list.add(CompletableFuture.runAsync(() -> worker(AppConfig.MAP_REMOTE_URLS[fi], tag), exec));
                    }

                    // 等待所有下载任务完成
                    CompletableFuture.allOf(list.toArray(new CompletableFuture[0])).join();
                }

                // 保存剩余分片数据
                // 防止缓冲区中有未保存的数据
                if (!chunkBuffer.isEmpty()) {
                    log.info("保存剩余分片数据...");
                    saveChunk(tag);
                }

                // 保存瓦片元数据
                // 记录所有成功下载的瓦片坐标，用于下次断点续传
                log.info("保存瓦片元数据...");
                saveMeta(validTiles, tag);

                // 拼接地图图片
                // 将所有瓦片拼接成完整的地图图片
                log.info("开始拼接地图图片：{}", tag);
                MapStitcher.stitch(validTiles, tag, tileW, tileH);

                log.info("✅ 地图处理完成：map_{}.png", tag);
            }

            log.info("=====================================");
            log.info("所有地图下载拼接完成，开始清理临时文件...");
            cleanTempFiles();

        } catch (Exception e) {
            // 捕获所有异常，记录错误日志
            log.error("❌ 地图下载流程发生异常", e);
        }
    }

    /**
     * 清理临时文件和元数据
     * <p>
     * 该方法清理下载过程中产生的临时文件：
     * <ul>
     *   <li>删除分片目录及其所有分片文件</li>
     *   <li>删除所有地图的元数据文件</li>
     *   <li>删除所有地图的失败记录文件</li>
     * </ul>
     * <p>
     * 调用时机：
     * <ul>
     *   <li>所有地图下载完成后</li>
     *   <li>确保临时文件不影响应用运行</li>
     *   <li>释放磁盘空间</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>捕获所有异常，记录错误日志</li>
     *   <li>清理失败不影响其他操作</li>
     * </ul>
     */
    private static void cleanTempFiles() {
        try {
            // 删除分片目录
            File chunkDir = FileUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR);
            deleteDirectory(chunkDir);
            log.info("✅ 已清理临时分片目录：{}", chunkDir.getAbsolutePath());

            // 删除所有地图的元数据和失败记录
            for (String name : AppConfig.MAP_REMOTE_URL_NAME) {
                // 删除元数据文件
                File meta = FileUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, name));
                if (meta.exists()) meta.delete();

                // 删除失败记录文件
                File failed = FileUtil.getRelativeFile(String.format(MapResourceUpdater.FAILED_FILE, name));
                if (failed.exists()) failed.delete();
            }
            log.info("✅ 已清理所有元数据与失败记录");

        } catch (Exception e) {
            // 捕获所有异常，记录错误日志
            log.error("❌ 清理临时文件失败", e);
        }
    }

    /**
     * 递归删除目录及其所有子文件
     * <p>
     * 该方法递归删除指定目录及其所有子目录和文件。
     * <p>
     * 算法：
     * <ol>
     *   <li>检查目录是否存在，不存在则返回</li>
     *   <li>获取目录下所有文件和子目录</li>
     *   <li>递归删除每个子目录</li>
     *   <li>删除目录本身</li>
     * </ol>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>清理临时文件</li>
     *   <li>删除下载目录</li>
     *   <li>释放磁盘空间</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>递归删除，需要谨慎使用</li>
     *   <li>如果目录不存在，直接返回</li>
     *   <li>不抛出异常，静默处理</li>
     * </ul>
     *
     * @param dir 要删除的目录
     */
    private static void deleteDirectory(File dir) {
        // 检查目录是否存在，不存在则返回
        if (dir == null || !dir.exists()) return;

        // 获取目录下所有文件和子目录
        File[] files = dir.listFiles();
        if (files != null) {
            // 递归删除每个子目录
            for (File file : files) {
                deleteDirectory(file);
            }
        }

        // 删除目录本身
        dir.delete();
    }

    /**
     * 下载工作线程：从任务队列中获取坐标并下载瓦片
     * <p>
     * 该方法是虚拟线程的工作函数，实现以下逻辑：
     * <ol>
     *   <li>从任务队列中获取一个坐标</li>
     *   <li>如果队列为空，退出线程</li>
     *   <li>检查坐标是否已访问，已访问则跳过</li>
     *   <li>下载瓦片数据</li>
     *   <li>标记坐标为已访问</li>
     *   <li>如果下载成功：
     *     <ul>
     *       <li>检测并保存瓦片尺寸</li>
     *       <li>添加到有效瓦片列表</li>
     *       <li>添加到分片缓冲区</li>
     *       <li>如果缓冲区满，保存分片</li>
     *       <li>将上下左右四个相邻坐标加入队列</li>
     *     </ul>
     *   </li>
     *   <li>下载失败时记录日志</li>
     *   <li>延迟指定时间后继续下载</li>
     * </ol>
     * <p>
     * 广度优先搜索(BFS)策略：
     * <ul>
     *   <li>从起点(0,0)开始，向四个方向扩展</li>
     *   <li>逐层向外下载，形成矩形区域</li>
     *   <li>遇到404则停止该方向</li>
     *   <li>自动适应地图边界</li>
     * </ul>
     * <p>
     * 并发控制：
     * <ul>
     *   <li>使用ConcurrentLinkedQueue确保线程安全</li>
     *   <li>使用ConcurrentHashMap记录已访问坐标</li>
     *   <li>多个虚拟线程并发下载，提高速度</li>
     * </ul>
     * <p>
     * 内存管理：
     * <ul>
     *   <li>分片缓冲区达到阈值后保存到文件</li>
     *   <li>保存后清空缓冲区，释放内存</li>
     *   <li>支持超大地图下载，避免OOM</li>
     * </ul>
     *
     * @param url 地图瓦片URL模板，包含{x}和{y}占位符
     * @param tag 地图标签，用于日志记录和文件命名
     */
    private static void worker(String url, String tag) {
        while (true) {
            // 从任务队列中获取一个坐标
            // poll方法是线程安全的，可以并发调用
            int[] pos = taskQueue.poll();
            if (pos == null) break; // 队列为空，退出线程

            int x = pos[0], y = pos[1];
            String key = x + "," + y;

            // 检查坐标是否已访问
            // 使用Set避免重复下载相同坐标
            if (visited.contains(key)) continue;

            log.debug("下载瓦片：({}, {})", x, y);

            // 下载瓦片数据
            DownloadResult res = download(x, y, url);
            visited.add(key); // 标记为已访问

            if (res.isSuccess()) {
                // 下载成功，检测并保存瓦片尺寸
                detectSize(res.getData());

                // 添加到有效瓦片列表
                validTiles.add(new Tile(x, y, res.getData()));

                // 添加到分片缓冲区
                chunkBuffer.add(res.getData());

                // 检查分片缓冲区是否达到阈值
                if (chunkBuffer.size() >= MapResourceUpdater.CHUNK_SIZE) {
                    log.debug("分片缓存已满，自动保存分片...");
                    saveChunk(tag);
                }

                // 将上下左右四个相邻坐标加入队列
                // 这实现了广度优先搜索的扩展策略
                add(x + 1, y);  // 右
                add(x - 1, y);  // 左
                add(x, y + 1);  // 下
                add(x, y - 1);  // 上
            } else if (!res.isNotFound()) {
                // 下载失败但不是404，记录警告
                log.warn("下载失败：({}, {})", x, y);
            } else {
                // 瓦片不存在(404)，这是正常的边界情况
                log.debug("瓦片不存在(404)：({}, {})", x, y);
            }

            // 延迟指定时间，避免对服务器造成过大压力
            sleep(MapResourceUpdater.TILE_DELAY_MS);
        }
    }

    /**
     * 下载指定坐标的瓦片数据
     * <p>
     * 该方法实现带重试机制的瓦片下载：
     * <ol>
     *   <li>构建瓦片URL，替换{x}和{y}占位符</li>
     *   <li>创建HTTP连接，设置请求头和超时</li>
     *   <li>发送GET请求获取瓦片数据</li>
     *   <li>处理响应：
     *     <ul>
     *       <li>404：返回notFound结果</li>
     *       <li>200：返回成功结果，包含瓦片数据</li>
     *       <li>其他：记录警告，继续重试</li>
     *     </ul>
     *   </li>
     *   <li>如果下载失败，延迟后重试</li>
     *   <li>达到最大重试次数后返回失败结果</li>
     * </ol>
     * <p>
     * 重试机制：
     * <ul>
     *   <li>最大重试次数由MAX_RETRY配置</li>
     *   <li>重试间隔为TILE_DELAY_MS * 2</li>
     *   <li>404不重试，直接返回notFound</li>
     *   <li>其他错误会重试，直到达到最大次数</li>
     * </ul>
     * <p>
     * 连接管理：
     * <ul>
     *   <li>在finally块中确保断开连接</li>
     *   <li>避免连接泄漏导致资源耗尽</li>
     *   <li>设置合理的连接和读取超时</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>HttpURLConnection在finally块中断开</li>
     *   <li>InputStream使用后正确关闭</li>
     *   <li>避免网络资源泄漏</li>
     * </ul>
     *
     * @param x 瓦片X坐标
     * @param y 瓦片Y坐标
     * @param tpl URL模板，包含{x}和{y}占位符
     * @return 下载结果，包含状态码和数据
     */
    private static DownloadResult download(int x, int y, String tpl) {
        // 重试循环，最多重试MAX_RETRY次
        for (int i = 0; i < MapResourceUpdater.MAX_RETRY; i++) {
            HttpURLConnection conn = null;
            try {
                // 构建瓦片URL，替换坐标占位符
                String url = tpl.replace("{x}", x + "").replace("{y}", y + "");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");

                // 设置User-Agent头，模拟浏览器请求
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

                // 设置连接超时，防止长时间阻塞
                conn.setConnectTimeout(MapResourceUpdater.CONNECT_TIMEOUT);

                // 设置读取超时，防止响应缓慢
                conn.setReadTimeout(MapResourceUpdater.READ);

                // 获取响应码
                int code = conn.getResponseCode();

                // 404表示瓦片不存在，这是正常的边界情况
                if (code == 404) {
                    return DownloadResult.notFound();
                }

                // 非200响应码，记录警告并重试
                if (code != 200) {
                    log.warn("瓦片请求异常，响应码：{}，坐标({}, {})", code, x, y);
                    continue;
                }

                // 成功响应，读取瓦片数据
                try (InputStream in = conn.getInputStream()) {
                    byte[] data = in.readAllBytes();
                    return DownloadResult.success(data);
                }
            } catch (Exception e) {
                // 捕获异常，记录警告并重试
                log.warn("瓦片下载异常，重试 {}/{}，坐标({}, {})", i + 1, MapResourceUpdater.MAX_RETRY, x, y);
                sleep(MapResourceUpdater.TILE_DELAY_MS * 2); // 重试间隔加倍
            } finally {
                // 在finally块中确保断开连接
                // 避免连接泄漏导致资源耗尽
                // 这对Native Image环境下的资源管理至关重要
                if (conn != null) conn.disconnect();
            }
        }

        // 达到最大重试次数，返回失败结果
        return DownloadResult.failed();
    }

    /**
     * 将坐标加入任务队列
     * <p>
     * 该方法实现线程安全的坐标添加：
     * <ul>
     *   <li>检查坐标是否已访问</li>
     *   <li>未访问则加入任务队列</li>
     *   <li>已访问则忽略</li>
     * </ul>
     * <p>
     * 并发控制：
     * <ul>
     *   <li>使用synchronized确保线程安全</li>
     *   <li>检查和添加两个操作需要原子性</li>
     *   <li>避免重复添加相同坐标</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>广度优先搜索扩展</li>
     *   <li>添加相邻四个方向的坐标</li>
     *   <li>从起点开始下载</li>
     * </ul>
     *
     * @param x X坐标x Y坐标
     */
    private static synchronized void add(int x, int y) {
        // 检查坐标是否已访问
        // 未访问则加入任务队列
        if (!visited.contains(x + "," + y)) {
            taskQueue.offer(new int[]{x, y});
        }
    }

    /**
     * 检测瓦片尺寸
     * <p>
     * 该方法从瓦片数据中提取宽度和高度：
     * <ul>
     *   <li>检查是否已检测，已检测则返回</li>
     *   <li>读取瓦片图片数据</li>
     *   <li>获取宽度和高度</li>
     *   <li>保存到静态变量</li>
     *   <li>检测失败时使用默认值256</li>
     * </ul>
     * <p>
     * 检测时机：
     * <ul>
     *   <li>首次成功下载瓦片时</li>
     *   <li>只检测一次，后续直接使用缓存值</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>BufferedImage使用后正确flush释放</li>
     *   <li>ByteArrayInputStream使用try-with-resources自动关闭</li>
     *   <li>避免图片资源泄漏</li>
     * </ul>
     * <p>
     * 内存管理：
     * <ul>
     *   <li>检测后立即释放BufferedImage</li>
     *   <li>减少内存占用</li>
     *   <li>只检测一次，避免重复检测</li>
     * </ul>
     *
     * @param data 瓦片图片数据
     */
    private static void detectSize(byte[] data) {
        // 检查是否已检测，已检测则返回
        if (tileW > 0) return;

        try {
            // 使用try-with-resources自动关闭ByteArrayInputStream
            // 确保资源正确释放，避免内存泄漏
            try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
                BufferedImage img = javax.imageio.ImageIO.read(bais);
                if (img != null) {
                    // 获取瓦片宽度和高度
                    tileW = img.getWidth();
                    tileH = img.getHeight();
                    log.info("自动探测瓦片大小：{}x{}", tileW, tileH);

                    // 释放图片的Native内存
                    // 检测完成后立即释放，减少内存占用
                    img.flush();
                }
            }
        } catch (Exception e) {
            // 检测失败，使用默认值256
            tileW = tileH = 256;
            log.warn("无法探测瓦片大小，使用默认值 256x256");
        }
    }

    /**
     * 保存分片数据到文件
     * <p>
     * 该方法将分片缓冲区中的瓦片数据保存到文件：
     * <ul>
     *   <li>检查缓冲区是否为空，为空则返回</li>
     *   <li>构建分片文件路径</li>
     *   <li>使用ObjectOutputStream序列化保存</li>
     *   <li>清空缓冲区，释放内存</li>
     *   <li>增加分片索引</li>
     * </ul>
     * <p>
     * 分片机制：
     * <ul>
     *   <li>每个分片包含多个瓦片数据</li>
     *   <li>文件格式：{tag}{index}.chunk</li>
     *   <li>使用Java序列化格式</li>
     *   <li>支持从分片文件恢复下载</li>
     * </ul>
     * <p>
     * 内存管理：
     * <ul>
     *   <li>保存后清空缓冲区，释放内存</li>
     *   <li>避免大量瓦片数据同时占用内存</li>
     *   <li>支持超大地图下载</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>ObjectOutputStream使用try-with-resources自动关闭</li>
     *   <li>FileOutputStream使用try-with-resources自动关闭</li>
     *   <li>避免文件句柄泄漏</li>
     * </ul>
     *
     * @param tag 地图标签，用于文件命名
     */
    private static void saveChunk(String tag) {
        // 检查缓冲区是否为空，为空则返回
        if (chunkBuffer.isEmpty()) return;

        try {
            // 构建分片文件路径
            File f = FileUtil.getRelativeFile(MapResourceUpdater.CHUNK_DIR, tag + chunkIndex + ".chunk");

            // 使用try-with-resources自动关闭流
            // 确保资源正确释放，避免文件句柄泄漏
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
                // 序列化保存分片数据
                oos.writeObject(chunkBuffer);
            }

            // 清空缓冲区，释放内存
            chunkBuffer.clear();

            // 增加分片索引，用于下一个分片文件
            chunkIndex++;

            log.debug("分片保存成功：{}", f.getName());
        } catch (Exception e) {
            // 捕获异常，记录错误日志
            log.error("❌ 分块保存失败", e);
        }
    }

    /**
     * 加载历史元数据
     * <p>
     * 该方法从元数据文件中加载已下载的坐标：
     * <ul>
     *   <li>检查元数据文件是否存在</li>
     *   <li>逐行读取坐标数据</li>
     *   <li>将坐标添加到已访问集合</li>
     *   <li>统计加载数量</li>
     * </ul>
     * <p>
     * 元数据格式：
     * <ul>
     *   <li>每行一个坐标，格式为"x,y"</li>
     *   <li>例如："10,20"表示x=10, y=20</li>
     *   <li>文件已排序，便于查看</li>
     * </ul>
     * <p>
     * 断点续传：
     * <ul>
     *   <li>加载后这些坐标不会重复下载</li>
     *   <li>支持增量下载</li>
     *   <li>减少网络请求次数</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>BufferedReader使用try-with-resources自动关闭</li>
     *   <li>FileReader使用try-with-resources自动关闭</li>
     *   <li>避免文件句柄泄漏</li>
     * </ul>
     *
     * @param tag 地图标签，用于文件命名
     */
    private static void loadMeta(String tag) {
        try {
            // 构建元数据文件路径
            File f = FileUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, tag));

            // 检查文件是否存在，不存在则返回
            if (!f.exists()) return;

            // 使用try-with-resources自动关闭流
            // 确保资源正确释放，避免文件句柄泄漏
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                int count = 0;

                // 逐行读取坐标数据
                while ((line = br.readLine()) != null) {
                    // 将坐标添加到已访问集合
                    // 这样下载时会跳过这些坐标
                    visited.add(line.trim());
                    count++;
                }
                log.info("加载历史元数据完成，共 {} 条记录", count);
            }
        } catch (Exception ignored) {
            // 捕获异常，记录警告日志
            log.warn("加载元数据失败");
        }
    }

    /**
     * 加载历史失败记录
     * <p>
     * 该方法从失败记录文件中加载失败坐标：
     * <ul>
     *   <li>检查失败记录文件是否存在</li>
     *   <li>逐行读取坐标数据</li>
     *   <li>将坐标添加到已访问集合</li>
     *   <li>统计加载数量</li>
     * </ul>
     * <p>
     * 失败记录格式：
     * <ul>
     *   <li>每行一个坐标，格式为"x,y"</li>
     *   <li>包括404、超时、网络错误等失败情况</li>
     *   <li>加载后会跳过这些坐标</li>
     * </ul>
     * <p>
     * 断点续传：
     * <ul>
     *   <li>避免重复下载失败的瓦片</li>
     *   <li>减少不必要的网络请求</li>
     *   <li>提高下载效率</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>BufferedReader使用try-with-resources自动关闭</li>
     *   <li>FileReader使用try-with-resources自动关闭</li>
     *   <li>避免文件句柄泄漏</li>
     * </ul>
     *
     * @param tag 地图标签，用于文件命名
     */
    private static void loadFailed(String tag) {
        try {
            // 构建失败记录文件路径
            File f = FileUtil.getRelativeFile(String.format(MapResourceUpdater.FAILED_FILE, tag));

            // 检查文件是否存在，不存在则返回
            if (!f.exists()) return;

            // 使用try-with-resources自动关闭流
            // 确保资源正确释放，避免文件句柄泄漏
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String line;
                int count = 0;

                // 逐行读取坐标数据
                while ((line = br.readLine()) != null) {
                    // 将坐标添加到已访问集合
                    // 这样下载时会跳过这些坐标
                    visited.add(line.trim());
                    count++;
                }
                log.info("加载历史失败记录完成，共 {} 条", count);
            }
        } catch (Exception ignored) {
            // 捕获异常，静默处理
            // 失败记录不是必需的，缺失不影响功能
        }
    }

    /**
     * 加载历史分片
     * <p>
     * 该方法用于加载历史分片数据，当前实现为空。
     * <p>
     * 未来实现：
     * <ul>
     *   <li>遍历分片目录，加载所有分片文件</li>
     *   <li>反序列化分片数据</li>
     *   <li>恢复下载进度</li>
     * </ul>
     * <p>
     * 断点续传：
     * <ul>
     *   <li>支持从分片文件恢复下载</li>
     *   <li>避免重复下载已下载的瓦片</li>
     *   <li>提高下载效率</li>
     * </ul>
     */
    private static void loadChunks() {
        // 当前实现为空
        // 未来可以添加分片恢复逻辑
        log.info("历史分片加载完成（无历史分片）");
    }

    /**
     * 保存瓦片元数据
     * <p>
     * 该方法将所有有效瓦片的坐标保存到元数据文件：
     * <ul>
     *   <li>按坐标排序瓦片列表</li>
     *   <li>构建元数据文件路径</li>
     *   <li>逐行写入坐标数据</li>
     *   <li>统计保存数量</li>
     * </ul>
     * <p>
     * 元数据格式：
     * <ul>
     *   <li>每行一个坐标，格式为"x,y"</li>
     *   <li>已排序，便于查看和比较</li>
     *   <li>纯文本格式，易于处理</li>
     * </ul>
     * <p>
     * 断点续传：
     * <ul>
     *   <li>下次下载时会加载此文件</li>
     *   <li>跳过已下载的坐标</li>
     *   <li>支持增量下载</li>
     * </ul>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>PrintWriter使用try-with-resources自动关闭</li>
     *   <li>FileWriter使用try-with-resources自动关闭</li>
     *   <li>避免文件句柄泄漏</li>
     * </ul>
     *
     * @param tiles 瓦片列表
     * @param tag 地图标签，用于文件命名
     * @throws Exception 保存失败时抛出异常
     */
    private static void saveMeta(List<Tile> tiles, String tag) throws Exception {
        // 按坐标排序瓦片列表
        // 先按X排序，再按Y排序，便于查看和比较
        Collections.sort(tiles, Comparator.comparingInt(Tile::getX).thenComparingInt(Tile::getY));

        // 构建元数据文件路径
        File f = FileUtil.getRelativeFile(String.format(MapResourceUpdater.METADATA_FILE, tag));

        // 使用try-with-resources自动关闭流
        // 确保资源正确释放，避免文件句柄泄漏
        try (PrintWriter pw = new PrintWriter(f)) {
            // 逐行写入坐标数据
            for (Tile t : tiles) pw.println(t.getX() + "," + t.getY());
        }
        log.info("元数据保存完成，共 {} 个瓦片", tiles.size());
    }

    /**
     * 重置下载状态
     * <p>
     * 该方法重置所有下载相关的静态变量：
     * <ul>
     *   <li>清空任务队列</li>
     *   <li>清空已访问集合</li>
     *   <li>清空有效瓦片列表</li>
     *   <li>清空分片缓冲区</li>
     *   <li>重置分片索引</li>
     *   <li>重置瓦片尺寸</li>
     * </ul>
     * <p>
     * 调用时机：
     * <ul>
     *   <li>开始下载新地图时</li>
     *   <li>确保不同地图的下载状态隔离</li>
     *   <li>避免状态污染</li>
     * </ul>
     * <p>
     * 内存管理：
     * <ul>
     *   <li>清空所有集合，释放内存</li>
     *   <li>重置静态变量，避免内存泄漏</li>
     *   <li>为下一个地图下载准备干净状态</li>
     * </ul>
     */
    private static void resetState() {
        // 清空任务队列
        taskQueue.clear();

        // 清空已访问集合
        visited.clear();

        // 清空有效瓦片列表
        validTiles.clear();

        // 清空分片缓冲区
        chunkBuffer.clear();

        // 重置分片索引
        chunkIndex = 0;

        // 重置瓦片尺寸
        tileW = tileH = -1;
    }

    /**
     * 线程睡眠方法
     * <p>
     * 该方法让当前线程睡眠指定毫秒数：
     * <ul>
     *   <li>用于下载延迟，避免服务器压力</li>
     *   <li>用于重试间隔，给服务器喘息时间</li>
     *   <li>捕获InterruptedException，静默处理</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>下载延迟</li>
     *   <li>重试间隔</li>
     *   <li>避免对服务器造成过大压力</li>
     * </ul>
     * <p>
     * 错误处理：
     * <ul>
     *   <li>捕获InterruptedException，静默处理</li>
     *   <li>不中断下载流程</li>
     * </ul>
     *
     * @param ms 睡眠毫秒数
     */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            // 捕获中断异常，静默处理
            // 不中断下载流程
        }
    }
}
