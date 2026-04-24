package com.luoke.app.context;

import com.luoke.app.model.OcrService;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 Java 21 虚拟线程的 OCR 异步生产者-消费者模型
 * 核心机制：虚拟线程 + OcrService 对象池 + 极速预检 + 熔断限流
 */
@Slf4j
public class OcrAsyncManager implements AutoCloseable {

    // ====================== 【单例实现】 ======================
    private static volatile OcrAsyncManager INSTANCE;
    // 虚拟线程池：每个任务都会分配一个独立的虚拟线程，极其轻量
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    // OCR 服务对象池：解决 OcrService 内部转换器非线程安全的问题
    private final BlockingQueue<OcrService> servicePool;
    // 当前正在排队或执行的任务数量
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    // 最大允许的积压任务数（熔断阈值）。超过此值说明消费跟不上生产，直接丢弃新帧，防止内存溢出
    private final int maxPendingTasks;

    // 私有化构造，禁止外部 new
    private OcrAsyncManager(int poolSize, int maxPendingTasks) throws Exception {
        this.maxPendingTasks = maxPendingTasks;
        this.servicePool = new ArrayBlockingQueue<>(poolSize);

        log.info("⏳ 正在初始化 OCR 服务池，数量: {}", poolSize);
        for (int i = 0; i < poolSize; i++) {
            OcrService service = new OcrService();
            service.init();
            servicePool.put(service);
        }
        log.info("✅ OCR 异步管理器初始化完成");
    }

    /**
     * 初始化单例（全局只调用一次）
     */
    public static void initialize(int poolSize, int maxPendingTasks) {
        if (INSTANCE == null) {
            synchronized (OcrAsyncManager.class) {
                if (INSTANCE == null) {
                    try {
                        INSTANCE = new OcrAsyncManager(poolSize, maxPendingTasks);
                        log.info("✅ OcrAsyncManager 单例初始化完成");
                    } catch (Exception e) {
                        log.error("❌ OcrAsyncManager 初始化失败", e);
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    /**
     * 获取全局单例（必须先 initialize）
     */
    public static OcrAsyncManager getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("OcrAsyncManager 未初始化，请先调用 initialize()");
        }
        return INSTANCE;
    }

    // ====================== 原有逻辑不动 ======================

    /**
     * 【生产者入口】提交图片进行异步识别
     */
    public CompletableFuture<List<String>> submitTask(byte[] bytes) {
        if (pendingTasks.get() >= maxPendingTasks) {
            log.warn("⚡ OCR 处理队列已满 ({} / {})，主动丢弃当前帧", pendingTasks.get(), maxPendingTasks);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        pendingTasks.incrementAndGet();
        CompletableFuture<List<String>> future = new CompletableFuture<>();
        vtExecutor.submit(() -> {
            OcrService service = null;
            try {
                service = servicePool.take();
                List<String> result = service.recognizeAll(bytes);
                future.complete(result);
            } catch (Exception e) {
                log.error("OCR 异步执行异常", e);
                future.complete(Collections.emptyList());
            } finally {
                if (service != null) {
                    try {
                        servicePool.put(service);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                pendingTasks.decrementAndGet();
            }
        });

        return future;
    }

    @Override
    public void close() {
        try {
            vtExecutor.shutdownNow();
            for (OcrService service : servicePool) {
                service.close();
            }
            servicePool.clear();
            log.info("🛑 OCR 异步管理器已关闭");
        } catch (Exception e) {
            log.error("关闭 OCR 管理器异常", e);
        }
    }
}