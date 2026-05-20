package com.luoke.app.test;

import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.process.NativeProcess;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 纯 SIFT 匹配测试 — 通过 C++ sift_match.exe 进行 SIFT 匹配，无 JavaCPP 依赖。
 * <p>
 * 架构: SocketServer → SiftMatchClient → sift_match.exe (独立进程)
 * <p>
 * 行为:
 * 1. 启动 SocketServer + sift_match.exe，加载地图特征
 * 2. 在地图中随机选取位置，截取 ROI 尺寸区域（模拟小地图视口）
 * 3. 应用圆形遮罩后通过 Socket 发送给 C++ 进行 SIFT 匹配 + 方向检测
 * 4. 对比匹配坐标与真实截取坐标，检测精度
 */
@Slf4j
public class MatchOnlyTest {

    private static final int CROP_W = 192;
    private static final int CROP_H = 200;
    private static final double CIRCLE_RADIUS_RATIO = 0.42;

    private static SiftMatchHandler client;
    private static BufferedImage mapImage;
    private static long startTime;
    private static int matchCount;
    private static int successCount;
    private static long totalMatchMs;

    public static void main(String[] args) throws Exception {
        log.info("========================================");
        log.info("  纯 SIFT 匹配测试 — C++ sift_match.exe");
        log.info("  无 JavaCPP 依赖");
        log.info("========================================");

        // 1. 启动 SocketServer
        int port = SocketServer.instance().start();
        log.info("SocketServer 已启动, 端口: {}", port);

        // 2. 加载 SIFT 地图 (使用内置资源，保证 classpath 可加载)
        String siftMapPath = ResourceConfigContext.getSiftMap();
        log.info("加载 SIFT 地图: {}", siftMapPath);
        try (InputStream is = ResourceUtils.getResourceStream(siftMapPath)) {
            mapImage = ImageIO.read(is);
            if (mapImage == null) {
                log.error("无法解码 SIFT 地图");
                return;
            }
            log.info("SIFT 地图尺寸: {}x{}", mapImage.getWidth(), mapImage.getHeight());
        }

        // 3. 启动 SIFT 匹配
        AtomicBoolean matchReady = new AtomicBoolean(false);
        CountDownLatch readyLatch = new CountDownLatch(1);

        client = new SiftMatchHandler(SocketServer.instance(), NativeProcess::create);
        SocketServer.instance().register(client);

        boolean started = client.start((ready, detail) -> {
            log.info("SIFT 状态: ready={} detail={}", ready, detail);
            if (ready) {
                matchReady.set(true);
                readyLatch.countDown();
            }
        });

        if (!started) {
            log.error("sift_match.exe 启动失败");
            cleanup();
            return;
        }

        // 等待握手完成 (最多 30 秒)
        if (!readyLatch.await(30, TimeUnit.SECONDS)) {
            log.error("sift_match.exe 握手超时");
            cleanup();
            return;
        }
        log.info("sift_match.exe 就绪, 开始匹配循环");

        // 4. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在清理...");
            cleanup();
            log.info("清理完成");
        }, "shutdown-hook"));

        // 5. 匹配循环
        int mapW = mapImage.getWidth();
        int mapH = mapImage.getHeight();
        int maxX = mapW - CROP_W;
        int maxY = mapH - CROP_H;

        log.info("地图有效截取范围: [0,{}] x [0,{}]", maxX, maxY);
        log.info("开始匹配循环（Ctrl+C 停止）...");

        startTime = System.currentTimeMillis();
        int loopCount = 0;

        while (!Thread.currentThread().isInterrupted()) {
            loopCount++;
            matchCount++;

            // 随机截取位置
            int cropX = ThreadLocalRandom.current().nextInt(maxX);
            int cropY = ThreadLocalRandom.current().nextInt(maxY);

            // 截取 BGRA 全彩数据 (C++ 侧接收后自行转为灰度，并用 HSV 检测箭头)
            byte[] bgraData = new byte[CROP_W * CROP_H * 4];
            for (int y = 0; y < CROP_H; y++) {
                for (int x = 0; x < CROP_W; x++) {
                    int rgb = mapImage.getRGB(cropX + x, cropY + y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int pos = (y * CROP_W + x) * 4;
                    bgraData[pos]     = (byte) b;
                    bgraData[pos + 1] = (byte) g;
                    bgraData[pos + 2] = (byte) r;
                    bgraData[pos + 3] = (byte) 255;
                }
            }

            // 模拟小地图圆遮罩（圈外清零，使 C++ HoughCircles 能检测到圆边界）
            double cx = CROP_W / 2.0;
            double cy = CROP_H / 2.0;
            int radius = (int) (Math.min(CROP_W, CROP_H) * CIRCLE_RADIUS_RATIO);
            int r2 = radius * radius;
            for (int y = 0; y < CROP_H; y++) {
                for (int x = 0; x < CROP_W; x++) {
                    double dx = x - cx;
                    double dy = y - cy;
                    if (dx * dx + dy * dy > r2) {
                        int pos = (y * CROP_W + x) * 4;
                        bgraData[pos]     = 0;
                        bgraData[pos + 1] = 0;
                        bgraData[pos + 2] = 0;
                        bgraData[pos + 3] = 0;
                    }
                }
            }

            // 真实中心坐标 (地图截取位置)
            double trueCenterX = cropX + CROP_W / 2.0;
            double trueCenterY = cropY + CROP_H / 2.0;

            // 执行匹配
            long t0 = System.currentTimeMillis();
            SiftMatchHandler.MatchResult result;
            try {
                result = client.sendFrameAndWait(bgraData, CROP_W, CROP_H,
                        Double.NaN, Double.NaN, 500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            long matchMs = System.currentTimeMillis() - t0;
            totalMatchMs += matchMs;

            if (result.success()) {
                successCount++;
                double error = Math.hypot(result.x() - trueCenterX, result.y() - trueCenterY);

                if (loopCount % 20 == 0) {
                    System.out.printf("[匹配 #%d] 耗时=%dms  真实=(%.0f,%.0f)  匹配=(%.1f,%.1f)  误差=%.1fpx  成功率=%d/%d%n",
                            matchCount, matchMs, trueCenterX, trueCenterY,
                            result.x(), result.y(), error,
                            successCount, matchCount);
                }
            } else {
                if (loopCount % 20 == 0) {
                    System.out.printf("[匹配 #%d] 耗时=%dms  真实=(%.0f,%.0f)  **匹配失败**  成功率=%d/%d%n",
                            matchCount, matchMs, trueCenterX, trueCenterY,
                            successCount, matchCount);
                }
            }

            // 每 100 次输出统计
            if (loopCount % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double avgMs = (double) totalMatchMs / matchCount;
                double matchRate = (double) successCount / matchCount * 100;
                Runtime rt = Runtime.getRuntime();
                long usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

                System.out.printf("[统计] %d次 | 成功率=%.1f%% | 平均耗时=%.1fms | 总耗时=%ds | 堆内存=%dMB%n",
                        matchCount, matchRate, avgMs, elapsed / 1000, usedMem);
            }

            // 检查连接状态
            if (!client.isReady()) {
                log.error("sift_match.exe 断连, 退出测试");
                break;
            }

            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        cleanup();
        log.info("测试结束");
    }

    private static void cleanup() {
        if (client != null) {
            client.stop();
        }
        SocketServer.instance().stop();
    }
}
