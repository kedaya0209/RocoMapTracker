package com.luoke.app.test;

import com.luoke.app.capture.CaptureService;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.RoiProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.socket.SocketServer;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 纯 WGC 采集测试 — 仅采集和字节拷贝，不加载任何匹配管线。
 * <p>
 * 运行方式: 直接运行本类 main() 方法。
 * <p>
 * 行为:
 * 1. 创建 CaptureService 连接到游戏窗口
 * 2. 使用最小 RoiProcessor 仅计数、不匹配
 * 3. 每 5 秒输出 FPS 和内存统计
 * <p>
 * 不加载: MapMatcherProcessor, SwitchMapMatcher, ArrowDetector,
 * ONNX 模型, SIFT, OCR, HookRegistry, 任何 JavaFX 类
 */
@Slf4j
public class CaptureOnlyTest {

    private static final AtomicInteger totalFrames = new AtomicInteger(0);
    private static final AtomicInteger roi0Frames = new AtomicInteger(0);
    private static final AtomicInteger roi1Frames = new AtomicInteger(0);
    private static final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
    private static volatile CaptureService captureService;

    public static void main(String[] args) {
        log.info("========================================");
        log.info("  纯 WGC 采集测试 — 不加载匹配管线");
        log.info("========================================");

        // 注册 JVM 关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在停止采集...");
            if (captureService != null && captureService.isRunning()) {
                captureService.stop();
            }
            SocketServer.instance().stop();
            log.info("采集已停止，共收到 {} 帧", totalFrames.get());
        }, "shutdown-hook"));

        // 启动全局 SocketServer
        try {
            int port = SocketServer.instance().start();
            log.info("SocketServer 已启动, 端口: {}", port);
        } catch (Exception e) {
            log.error("SocketServer 启动失败", e);
            return;
        }

        // 创建采集服务
        captureService = new CaptureService(AppConfig.TARGET_WINDOW_NAME);

        // 附加最小处理器：仅计数，不做任何匹配
        captureService.addProcessors(new CountingProcessor(0), new CountingProcessor(1));

        // 必须在 tryConnect 前设置 ROI (tryConnect 时携带给 capture.exe)
        List<ROIData> rois = new ArrayList<>();
        rois.add(new ROIData(8900, 700, 1000, 1800));  // 小地图
        rois.add(new ROIData(8750, 2870, 1100, 1700)); // 物品栏
        captureService.setRois(ROIData.createContiguousArray(rois));

        log.info("目标窗口: {}", AppConfig.TARGET_WINDOW_NAME);
        log.info("开始尝试连接...");

        // 连接 + 心跳循环
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!captureService.isRunning()) {
                    if (captureService.tryConnect()) {
                        log.info("连接成功!");
                    } else {
                        log.info("未找到游戏窗口 [{}]，5秒后重试...", AppConfig.TARGET_WINDOW_NAME);
                    }
                }

                // 每 5 秒输出统计
                long now = System.currentTimeMillis();
                long elapsed = now - lastReportTime.get();
                if (elapsed >= 5000) {
                    lastReportTime.set(now);
                    int roi0 = roi0Frames.getAndSet(0);
                    int roi1 = roi1Frames.getAndSet(0);
                    int total = totalFrames.get();
                    double fps0 = roi0 * 1000.0 / elapsed;
                    double fps1 = roi1 * 1000.0 / elapsed;

                    Runtime rt = Runtime.getRuntime();
                    long usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);

                    System.out.printf("[采集] ROI-0: %d帧 (%.1ffps) | ROI-1: %d帧 (%.1ffps) | 累计=%d | 堆内存=%dMB%n",
                            roi0, fps0, roi1, fps1, total, usedMem);
                }

                TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("主循环异常", e);
                try {
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (captureService != null && captureService.isRunning()) {
            captureService.stop();
        }
        log.info("测试结束，总帧数: {}", totalFrames.get());
    }

    /**
     * 最小 RoiProcessor：仅计数，不做任何匹配/OCR。
     * 不依赖 MapMatcherProcessor、ONNX、SIFT 等任何匹配管线。
     */
    private static class CountingProcessor implements RoiProcessor {
        private final int roiIndex;
        private final ROIData roi;

        CountingProcessor(int roiIndex) {
            this.roiIndex = roiIndex;
            this.roi = roiIndex == 0
                    ? new ROIData(8900, 700, 1000, 1800)
                    : new ROIData(8750, 2870, 1100, 1700);
        }

        @Override
        public int targetRoiIndex() {
            return roiIndex;
        }

        @Override
        public void onProcess(byte[] data, int width, int height) {
            totalFrames.incrementAndGet();
            if (roiIndex == 0) {
                roi0Frames.incrementAndGet();
            } else {
                roi1Frames.incrementAndGet();
            }
            // 仅计数，不处理 data
        }

        @Override
        public ROIData getRoi() {
            return roi;
        }
    }
}
