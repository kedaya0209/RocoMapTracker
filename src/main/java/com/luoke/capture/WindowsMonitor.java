package com.luoke.capture;

import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 窗口监视器（虚拟线程后台监控）
 * 专为 洛克王国：世界 设计
 */
@Slf4j
public class WindowsMonitor {

    private final WindowCaptureContext context;
    private ExecutorService monitorExecutor;

    // 使用 volatile 保证线程可见性
    private volatile boolean isMonitoring = false;

    public WindowsMonitor(String windowKeyword) {
        context = new WindowCaptureContext(windowKeyword);
    }

    /**
     * 【核心】启动监控
     *
     * @param delayMs  采样间隔
     * @param callBack 回调处理逻辑
     */
    public void startMonitor0(int delayMs, WindowCaptureEventCallBack<CaptureFrameRecord> callBack) {
        // 1. 防抖：防止重复启动
        if (isMonitoring) {
            log.warn("监视器已经在运行中...");
            return;
        }

        // 2. 初始化上下文（寻找窗口句柄等）
        if (!context.start()) {
            log.error("❌ 无法定位目标窗口，请确认游戏是否运行");
            return;
        }

        isMonitoring = true;
        // 使用虚拟线程池，适合这种阻塞式的等待（sleep）
        monitorExecutor = Executors.newVirtualThreadPerTaskExecutor();

        monitorExecutor.submit(() -> {
            log.info("🚀 后台线程启动成功");
            try {
                while (isMonitoring && !Thread.currentThread().isInterrupted()) {
                    long frameStart = System.currentTimeMillis();

                    // 3. 核心步骤：执行截屏
                    // 建议在 context 内部实现窗口存活检查，如果窗口没了，这里应返回 null
                    CaptureFrameRecord frame = context.captureFrameBytes();

                    if (frame != null) {
                        callBack.call(frame);
                    } else {
                        log.warn("⚠️ 未能获取到帧数据（窗口可能最小化或关闭）");
                        // 如果窗口关闭，可以选择停止监控
                        // stopMonitor(); break;
                    }

                    // 4. 动态计算等待时间（防止任务积压）
                    long cost = System.currentTimeMillis() - frameStart;
                    long actualDelay = Math.max(0, delayMs - cost);

                    TimeUnit.MILLISECONDS.sleep(actualDelay);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("监控线程被中断");
            } catch (Exception e) {
                log.error("💥 监控过程中发生异常:", e);
                stopMonitor(); // 发生严重错误时停止
            }
        });
        log.info("✅ 窗口监控已启动（虚拟线程），采样间隔：{}ms", delayMs);
    }

    /**
     * 包装方法：自动处理像素到图片的转换
     */
    public void startMonitor1(int delayMs, WindowCaptureEventCallBack<BufferedImage> callBack) {
        startMonitor0(delayMs, record -> {
            if (record != null && record.bytes() != null) {
                // 像素转换：BGRA 转换为 BufferedImage
                BufferedImage img = ImageConverter.convertBgraToImage(
                        record.bytes(),
                        record.width(),
                        record.height()
                );
                if (img != null) {
                    callBack.call(img);
                }
            }
        });
    }

    /**
     * 停止监控
     */
    public synchronized void stopMonitor() {
        if (!isMonitoring) return;

        isMonitoring = false;
        if (monitorExecutor != null) {
            monitorExecutor.shutdownNow();
            try {
                // 等待虚拟线程优雅退出
                if (!monitorExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    log.warn("监控线程未能在规定时间内停止");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        context.close();
        log.info("❌ 窗口监控已停止");
    }

    // 提供给外部获取当前状态
    public boolean isRunning() {
        return isMonitoring;
    }
}