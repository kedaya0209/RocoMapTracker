package com.luoke.app.context;

import com.luoke.app.model.OcrService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Slf4j
public class OcrAsyncManager implements AutoCloseable {
    private static volatile OcrAsyncManager INSTANCE;
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final BlockingQueue<OcrService> servicePool;
    private final AtomicInteger pendingTasks = new AtomicInteger(0);

    private OcrAsyncManager(int poolSize) throws Exception {
        this.servicePool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            OcrService service = new OcrService();
            service.init();
            servicePool.put(service);
        }
    }

    public static void initialize(int poolSize) {
        if (INSTANCE == null) {
            synchronized (OcrAsyncManager.class) {
                if (INSTANCE == null) {
                    try {
                        INSTANCE = new OcrAsyncManager(poolSize);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    public static OcrAsyncManager getInstance() {
        return INSTANCE;
    }

    public void submitTask(byte[] bytes, Consumer<List<String>> callback) {
        // 1. 稍微放宽到 8，给极速翻页留出缓冲空间
        if (pendingTasks.get() >= 8) {
            return;
        }

        pendingTasks.incrementAndGet();
        long submitTime = System.currentTimeMillis(); // 记录任务出生时间

        vtExecutor.submit(() -> {
            OcrService service = null;
            try {
                // 2. 超时判定：如果这个任务在队列里待了超过 500ms 还没被执行，
                // 说明它已经是“陈旧帧”了，直接放弃它，把算力留给后来的“新鲜帧”
                if (System.currentTimeMillis() - submitTime > 500) {
                    return;
                }

                service = servicePool.poll(1, TimeUnit.SECONDS);
                if (service != null) {
                    List<String> result = service.recognizeAll(bytes);
                    callback.accept(result);
                }
            } catch (Exception e) {
                log.error("OCR 执行异常", e);
            } finally {
                if (service != null) servicePool.offer(service);
                pendingTasks.decrementAndGet();
            }
        });
    }

    @Override
    public void close() {
        vtExecutor.shutdownNow();
        servicePool.forEach(s -> {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        });
    }
}