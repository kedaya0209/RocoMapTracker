package com.luoke.app.context;

import com.luoke.app.model.ocr.OcrService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * OCR异步任务管理器
 * 负责管理OCR服务的线程池和任务队列
 */
@Slf4j
public class OcrAsyncManager implements AutoCloseable {
    private static volatile OcrAsyncManager INSTANCE;

    private final ExecutorService executorService = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger index = new AtomicInteger(0);

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "OCR-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    /**
     * OCR服务池，使用有界阻塞队列
     */
    private final BlockingQueue<OcrService> servicePool;

    /**
     * 私有构造函数
     * @param poolSize 服务池大小
     * @throws Exception 当OcrService初始化失败时抛出异常
     */
    private OcrAsyncManager(int poolSize) throws Exception {
        this.servicePool = new ArrayBlockingQueue<>(poolSize);

        for (int i = 0; i < poolSize; i++) {
            OcrService service = new OcrService();
            service.init();
            servicePool.put(service);
        }
    }

    /**
     * 初始化OCR异步管理器（单例模式）
     * @param poolSize 服务池大小
     */
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

    /**
     * 获取单例实例
     * @return 单例实例，如果未初始化则返回null
     */
    public static OcrAsyncManager getInstance() {
        return INSTANCE;
    }

    /**
     * 提交异步OCR任务
     * @param bytes 图像字节数组
     * @param width 图像宽度
     * @param height 图像高度
     * @param callback 回调函数，接收OCR识别结果
     */
    public void submitTask(byte[] bytes, int width, int height, Consumer<List<String>> callback) {
        long submitTime = System.currentTimeMillis();

        executorService.submit(() -> {
            OcrService service = null;
            try {
                if (System.currentTimeMillis() - submitTime > 500) {
                    return;
                }

                service = servicePool.poll();

                if (service != null) {
                    List<String> result = service.recognizeAll(bytes, width, height);
                    callback.accept(result);
                }
            } catch (Exception e) {
                log.error("OCR 执行异常", e);
            } finally {
                if (service != null) {
                    servicePool.offer(service);
                }
            }
        });
    }

    /**
     * 释放OCR异步管理器占用的所有资源
     */
    @Override
    public void close() {
        executorService.shutdownNow();

        servicePool.forEach(s -> {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        });
    }
}
