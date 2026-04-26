package com.luoke.app.context;

import com.luoke.app.model.OcrService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * OCR异步任务管理器
 *
 * <p>负责管理OCR服务的线程池和任务队列，提供异步OCR识别能力。
 * 使用虚拟线程实现轻量级并发，支持高吞吐量的OCR任务处理。
 *
 * <p>设计模式：
 * <ul>
 *   <li>单例模式：全局唯一实例，管理共享的OCR服务池</li>
 *   <li>对象池管理：维护OcrService对象池，复用Native资源</li>
 *   <li>任务队列：通过BlockingQueue管理服务实例</li>
 * </ul>
 *
 * <p>性能优化策略：
 * <ul>
 *   <li>虚拟线程：使用Project Loom虚拟线程，减少线程创建开销</li>
 *   <li>任务去重：限制pendingTasks数量，避免重复处理同一帧</li>
 *   <li>陈旧帧丢弃：超过500ms未执行的任务自动丢弃</li>
   *   <li>资源池：复用OcrService实例，减少初始化开销</li>
 * </ul>
 *
 * <p>Native资源管理：
 * <ul>
 *   <li>每个OcrService实例管理独立的Native资源</li>
 *   <li>close()方法会释放所有服务实例的Native资源</li>
 *   <li>使用ArrayBlockingQueue确保资源池大小可控</li>
 * </ul>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class OcrAsyncManager implements AutoCloseable {
    /**
     * 单例实例，引用volatile确保多线程可见性
     * 双重检查锁定模式保证线程安全的懒加载
     */
    private static volatile OcrAsyncManager INSTANCE;

    /**
     * 虚拟线程执行器
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用虚拟线程替代传统线程，大幅减少内存占用</li>
     *   <li>虚拟线程调度开销极低，适合高并发短任务</li>
     *   <li>每个OCR任务在独立的虚拟线程中执行</li>
     * </ul>
     */
    private final ExecutorService vtExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * OCR服务池，使用有界阻塞队列
     *
     * <p>设计意图：
     * <ul>
     *   <li>复用OcrService实例，减少Native资源分配</li>
     *   <li>有界队列防止资源无限增长</li>
     *   <li>ArrayBlockingQueue基于数组实现，性能优于链表</li>
     * </ul>
     */
    private final BlockingQueue<OcrService> servicePool;

    /**
     * 待处理任务计数器，使用AtomicInteger保证线程安全
     *
     * <p>用途：
     * <ul>
     *   <li>限制并发任务数量，避免系统过载</li>
     *   <li>任务提交时递增，任务完成时递减</li>
     *   <li>通过threshold判断是否接受新任务</li>
     * </ul>
     */
    private final AtomicInteger pendingTasks = new AtomicInteger(0);

    /**
     * 私有构造函数，防止外部实例化
     *
     * <p>初始化流程：
     * <ol>
     *   <li>创建有界服务池（大小由poolSize参数指定）</li>
     *   <li>初始化指定数量的OcrService实例</li>
     *   <li>将所有服务实例放入服务池</li>
     * </ol>
     *
     * <p>Native资源管理：
     * <ul>
     *   <li>每个OcrService实例在初始化时加载Native资源</li>
     *   <li>close()方法会释放所有服务实例的Native资源</li>
     * </ul>
     *
     * @param poolSize 服务池大小，同时决定初始创建的OcrService实例数量
     * @throws Exception 当OcrService初始化失败时抛出异常
     */
    private OcrAsyncManager(int poolSize) throws Exception {
        // 创建有界服务池，容量为poolSize
        this.servicePool = new ArrayBlockingQueue<>(poolSize);

        // 初始化指定数量的OcrService实例
        for (int i = 0; i < poolSize; i++) {
            OcrService service = new OcrService();
            service.init(); // 加载Native资源

            // 将服务实例放入服务池
            // 如果队列满，put()方法会阻塞，但此时poolSize等于初始数量，不会满
            servicePool.put(service);
        }
    }

    /**
     * 初始化OCR异步管理器（单例模式）
     *
     * <p>设计意图：
     * <ul>
     *   <li>使用双重检查锁定模式，保证线程安全</li>
     *   <li>volatile确保INSTANCE在多线程间可见</li>
     *   <li>只初始化一次，后续调用直接返回已有实例</li>
     * </ul>
     *
     * <p>线程安全保证：
     * <ol>
     *   <li>第一次检查（无锁）：快速判断是否已初始化</li>
     *   <li>类级别锁：确保只有一个线程执行初始化</li>
     *   <li>第二次检查（有锁）：防止多个线程同时通过第一次检查</li>
     * </ol>
     *
     * @param poolSize 服务池大小，决定可并发处理的OCR任务数量
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
     *
     * <p>注意：
     * <ul>
     *   <li>调用前必须先调用initialize()</li>
     *   <li>如果未初始化，返回null</li>
     * </ul>
     *
     * @return 单例实例，如果未初始化则返回null
     */
    public static OcrAsyncManager getInstance() {
        return INSTANCE;
    }

    /**
     * 提交异步OCR任务
     *
     * <p>任务处理流程：
     * <ol>
     *   <li>检查pendingTasks数量，超过阈值则拒绝任务</li>
     *   <li>记录任务提交时间</li>
     *   <li>在虚拟线程中执行OCR识别</li>
     *   <li>从服务池获取OcrService实例</li>
     *   <li>检查任务是否陈旧（超过500ms未执行）</li>
     *   <li>执行OCR识别并调用回调函数</li>
     *   <li>归还服务实例到服务池</li>
     * </ol>
     *
     * <p>性能优化策略：
     * <ul>
     *   <li>任务去重：限制pendingTasks <= 8，避免重复处理同一帧</li>
     *   <li>陈旧帧丢弃：超过500ms未执行的任务自动丢弃</li>
     *   <li>极速翻页支持：放宽阈值到8，给翻页留出缓冲空间</li>
     * </ul>
     *
     * <p>Native资源管理：
     * <ul>
     *   <li>从服务池获取OcrService实例（超时1秒）</li>
     *   <li>使用完毕后归还到服务池</li>
     *   <li>close()方法会释放所有服务实例的Native资源</li>
     * </ul>
     *
     * @param bytes 图像字节数组
     * @param callback 回调函数，接收OCR识别结果（文本列表）
     */
    public void submitTask(byte[] bytes, Consumer<List<String>> callback) {
        // 1. 稍微放宽到 8，给极速翻页留出缓冲空间
        // 设计意图：限制并发任务数，避免系统过载
        if (pendingTasks.get() >= 8) {
            return; // 拒绝新任务
        }

        // 任务计数器递增
        pendingTasks.incrementAndGet();

        // 记录任务提交时间，用于陈旧帧检测
        long submitTime = System.currentTimeMillis();

        // 在虚拟线程中执行OCR任务
        vtExecutor.submit(() -> {
            OcrService service = null;
            try {
                // 2. 超时判定：如果这个任务在队列里待了超过 500ms 还没被执行，
                // 说明它已经是"陈旧帧"了，直接放弃它，把算力留给后来的"新鲜帧"
                if (System.currentTimeMillis() - submitTime > 500) {
                    return; // 丢弃陈旧帧
                }

                // 从服务池获取OcrService实例，超时1秒
                service = servicePool.poll(1, TimeUnit.SECONDS);

                if (service != null) {
                    // 执行OCR识别
                    List<String> result = service.recognizeAll(bytes);

                    // 调用回调函数传递结果
                    callback.accept(result);
                }
            } catch (Exception e) {
                log.error("OCR 执行异常", e);
            } finally {
                // 确保服务实例归还到服务池
                if (service != null) {
                    servicePool.offer(service);
                }

                // 任务计数器递减
                pendingTasks.decrementAndGet();
            }
        });
    }

    /**
     * 释放OCR异步管理器占用的所有资源
     *
     * <p>资源清理流程：
     * <ol>
     *   <li>关闭虚拟线程执行器（停止接受新任务）</li>
     *   <li>中断正在执行的任务（shutdownNow）</li>
     *   <li>遍历服务池，关闭每个OcrService实例</li>
     *   <li>释放所有Native资源（OpenCV、ONNX等）</li>
     * </ol>
     *
     * <p>注意：
     * <ul>
     *   <li>调用后不能再使用此对象</li>
     *   <li>正在执行的任务会被中断</li>
     *   <li>Native资源会全部释放</li>
     * </ul>
     */
    @Override
    public void close() {
        // 关闭虚拟线程执行器，中断所有正在执行
        vtExecutor.shutdownNow();

        // 遍历服务池，关闭每个OcrService实例
        // 这会释放Native资源（OpenCV、ONNX等）
        servicePool.forEach(s -> {
            try {
                s.close();
            } catch (Exception ignored) {
                // 忽略关闭异常，确保所有服务实例都被处理
            }
        });
    }
}
