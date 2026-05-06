package com.luoke.app.test;

import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.macher.miniMap.CircleMaskApplier;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.PointerScope;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.InputStream;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 纯 SIFT 匹配测试 — 从 SIFT 地图原图随机截取区域进行匹配，不加载 WGC 采集。
 * <p>
 * 运行方式: 直接运行本类 main() 方法。
 * <p>
 * 行为:
 * 1. 加载 SIFT 地图 + 训练 FLANN + 加载 ONNX 箭头模型
 * 2. 在地图中随机选取位置，截取 ROI 尺寸区域（模拟小地图视口）
 * 3. 应用圆形遮罩后执行 SIFT 匹配 + 方向检测
 * 4. 对比匹配坐标与真实截取坐标，检测精度和内存趋势
 * <p>
 * 不加载: CaptureService, WgcCaptureLib, WindowFinder, JavaFX 任何类
 */
@Slf4j
public class MatchOnlyTest {

    // 截取区域大小（模拟 1920x1080 窗口下的小地图 ROI）
    private static final int CROP_W = 192;
    private static final int CROP_H = 200;

    // 圆形遮罩半径比例（模拟真实小地图）
    private static final double CIRCLE_RADIUS_RATIO = 0.42;

    private static SwitchMapMatcher matcher;
    private static ArrowDetector arrowDetector;
    private static Mat mapGray;
    private static long startTime;
    private static int matchCount;
    private static int successCount;
    private static long totalMatchMs;

    public static void main(String[] args) {
        log.info("========================================");
        log.info("  纯 SIFT 匹配测试 — 静态图片随机截取");
        log.info("  不加载 WGC 采集管线");
        log.info("========================================");

        // 1. 初始化 OpenCV
        try {
            System.setProperty("org.bytedeco.javacpp.nopointergc", "true");
            Loader.load(opencv_core.class);
            log.info("OpenCV (JavaCPP) 初始化成功");
        } catch (Throwable e) {
            log.error("OpenCV 初始化失败", e);
            return;
        }

        // 2. 加载 SIFT 地图灰度图（用于随机截取）
        String siftMapPath = ResourceConfigContext.getSiftMap();
        log.info("加载 SIFT 地图: {}", siftMapPath);
        try (InputStream is = ResourceUtils.getResourceStream(siftMapPath)) {
            byte[] bytes = is.readAllBytes();
            try (PointerScope scope = new PointerScope()) {
                Mat rawData = new Mat(bytes.length, 1, opencv_core.CV_8UC1);
                rawData.data().put(bytes);
                Mat color = opencv_imgcodecs.imdecode(rawData, opencv_imgcodecs.IMREAD_UNCHANGED);
                if (color.empty()) {
                    log.error("无法解码 SIFT 地图");
                    return;
                }
                mapGray = new Mat();
                opencv_imgproc.cvtColor(color, mapGray, opencv_imgproc.COLOR_BGR2GRAY);
                log.info("SIFT 地图尺寸: {}x{}", mapGray.cols(), mapGray.rows());
            }
        } catch (Exception e) {
            log.error("加载 SIFT 地图失败", e);
            return;
        }

        // 3. 初始化匹配器
        log.info("初始化 SIFT 匹配器...");
        try {
            matcher = SwitchMapMatcher.getInstance();
            boolean ok = matcher.init(siftMapPath);
            if (!ok) {
                log.error("SIFT 匹配器初始化失败");
                return;
            }
            log.info("SIFT 匹配器就绪");
        } catch (Exception e) {
            log.error("SIFT 匹配器初始化异常", e);
            return;
        }

        // 4. 初始化箭头检测器
        log.info("初始化箭头检测器 (ONNX)...");
        try {
            arrowDetector = ArrowDetector.getInstance();
            arrowDetector.init();
            log.info("箭头检测器就绪");
        } catch (Exception e) {
            log.error("箭头检测器初始化失败", e);
            arrowDetector = null;
        }

        // 5. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在清理...");
            cleanup();
            log.info("清理完成");
        }, "shutdown-hook"));

        // 6. 匹配循环
        int mapW = mapGray.cols();
        int mapH = mapGray.rows();
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

            // 截取区域（OpenCV Rect: x, y, w, h）
            byte[] cropData = new byte[CROP_W * CROP_H];
            double cx = CROP_W / 2.0;
            double cy = CROP_H / 2.0;
            int radius = (int) (Math.min(CROP_W, CROP_H) * CIRCLE_RADIUS_RATIO);
            try (PointerScope scope = new PointerScope()) {
                Mat crop = mapGray.apply(new org.bytedeco.opencv.opencv_core.Rect(cropX, cropY, CROP_W, CROP_H));
                Mat cropCopy = new Mat();
                crop.copyTo(cropCopy);
                cropCopy.data().get(cropData);
            }
            CircleMaskApplier.applyMask(cropData, CROP_W, CROP_H, cx, cy, radius);

            // 执行匹配
            long t0 = System.currentTimeMillis();
            // 截取区域中心点在地图中的真实坐标
            double trueCenterX = cropX + CROP_W / 2.0;
            double trueCenterY = cropY + CROP_H / 2.0;

            double[][] result = matcher.match(cropData, CROP_W, CROP_H);

            // 方向检测
            Double angle = null;
            if (arrowDetector != null) {
                try {
                    angle = arrowDetector.detectPlayer(cropData, CROP_W, CROP_H);
                } catch (Exception e) {
                    // 忽略方向检测失败
                }
            }

            long matchMs = System.currentTimeMillis() - t0;
            totalMatchMs += matchMs;

            // 输出结果
            if (result != null && result.length > 0) {
                successCount++;
                double matchX = result[0][0];
                double matchY = result[0][1];
                double error = Math.hypot(matchX - trueCenterX, matchY - trueCenterY);

                if (loopCount % 20 == 0) {
                    System.out.printf("[匹配 #%d] 耗时=%dms  真实=(%.0f,%.0f)  匹配=(%.1f,%.1f)  误差=%.1fpx  成功率=%d/%d%n",
                            matchCount, matchMs, trueCenterX, trueCenterY,
                            matchX, matchY, error, successCount, matchCount);
                }
            } else {
                if (loopCount % 20 == 0) {
                    System.out.printf("[匹配 #%d] 耗时=%dms  真实=(%.0f,%.0f)  **匹配失败**  成功率=%d/%d%n",
                            matchCount, matchMs, trueCenterX, trueCenterY, successCount, matchCount);
                }
            }

            // 每 100 次输出统计和内存
            if (loopCount % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double avgMs = (double) totalMatchMs / matchCount;
                double matchRate = (double) successCount / matchCount * 100;
                Runtime rt = Runtime.getRuntime();
                long usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

                System.out.printf("[统计] %d次 | 成功率=%.1f%% | 平均耗时=%.1fms | 总耗时=%ds | 堆内存=%dMB%n",
                        matchCount, matchRate, avgMs, elapsed / 1000, usedMem);
            }

            // 小延迟，避免刷屏
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
        if (matcher != null) {
            try {
                matcher.destroy();
            } catch (Exception e) {
                log.error("关闭匹配器失败", e);
            }
        }
        if (arrowDetector != null) {
            try {
                arrowDetector.release();
            } catch (Exception e) {
                log.error("释放箭头检测器失败", e);
            }
        }
        if (mapGray != null && !mapGray.isNull()) {
            mapGray.close();
        }
    }
}
