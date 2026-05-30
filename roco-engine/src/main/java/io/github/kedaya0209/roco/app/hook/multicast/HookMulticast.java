package io.github.kedaya0209.roco.app.hook.multicast;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.hook.IHook;
import io.github.kedaya0209.roco.app.hook.HookEventType;
import io.github.kedaya0209.roco.app.hook.container.HookContainer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@ThreadSafe
@Slf4j
class HookMulticast {

    private static final HookMulticast INSTANCE = new HookMulticast();

    private final HookContainer container;

    private final BlockingQueue<HookEventTask> eventQueue;

    private final ExecutorService virtualExecutor;

    private volatile boolean running;

    private HookMulticast() {
        // 获取钩子容器单例，用于查找订阅的钩子
        this.container = HookContainer.getInstance();
        // 创建无界阻塞队列，用于暂存待处理的事件
        // LinkedBlockingQueue的默认容量是Integer.MAX_VALUE，实际上是无界的
        this.eventQueue = new LinkedBlockingQueue<>();
        // 创建虚拟线程执行器，每个任务创建一个虚拟线程
        // 虚拟线程是轻量级的，适合IO密集型任务
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        // 设置运行标志为true，启动消费者线程
        this.running = true;
        // 启动消费者线程，开始从队列中取出事件并分发
        startConsumeLoop();
    }

    protected static HookMulticast getInstance() {
        return INSTANCE;
    }

    protected void enqueue(HookEventType eventType, Object data) {
        // 检查运行标志，避免在分发器关闭后继续入队
        if (!running) {
            return;
        }
        // 使用非阻塞的'offer()'方法将事件任务加入队列
        // 相比'put()'方法，'offer()'不会阻塞，如果队列满则返回false
        // 由于队列是无界的，通常不会满，这个检查主要是防御性编程
        eventQueue.offer(new HookEventTask(eventType, data));
    }

    private void startConsumeLoop() {
        // 在虚拟线程中提交消费者任务
        // 虚拟线程是轻量级的，创建开销很小
        virtualExecutor.submit(() -> {
            // 消费者循环，一直运行直到running为false
            while (running) {
                try {
                    // 从队列中取出事件任务，如果队列为空则阻塞等待
                    // take()方法是阻塞的，会释放虚拟线程，允许JVM调度其他任务
                    HookEventTask task = eventQueue.take();
                    // 分发事件给所有订阅的钩子
                    dispatch(task.eventType(), task.data());
                } catch (InterruptedException e) {
                    // 线程被中断，恢复中断状态并退出循环
                    // 通常在shutdown()时会调用interrupt()中断线程
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // 多种异常来源（钩子回调），保留通用捕获
                    log.error("钩子事件消费异常", e);
                }
            }
        });
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatch(HookEventType eventType, Object data) {
        // 从容器中获取订阅该事件类型的钩子列表
        // getHookList()返回的是线程安全的CopyOnWriteArrayList
        List<IHook<?>> hookList = container.getHookList(eventType);
        // 如果没有订阅的钩子，直接返回，避免不必要的操作
        if (hookList.isEmpty()) {
            return;
        }

        // 遍历所有订阅的钩子，逐个调用回调方法
        // 使用增强for循环遍历CopyOnWriteArrayList，是线程安全的
        for (IHook<?> hook : hookList) {
            try {
                // 调用钩子的onEvent方法，传递事件类型和数据
                // 由于泛型擦除，需要强制转换，使用@SuppressWarnings压制警告
                // 运行时可能出现类型转换异常，但依赖钩子的泛型声明保证正确性
                ((IHook) hook).onEvent(eventType, data);
            } catch (Throwable t) {
                // 捕获所有异常（包括Error），记录错误日志
                // 异常隔离确保单个钩子的失败不影响其他钩子
                // 使用日志记录异常详情和事件类型，便于问题排查
                log.error("钩子执行异常，event:{}", eventType, t);
            }
        }
    }

    protected void shutdown() {
        // 设置运行标志为false，消费者线程会检测到并退出循环
        // 使用volatile确保写操作对消费者线程立即可见
        running = false;
        // 关闭线程池，中断所有虚拟线程
        // shutdownNow()会尝试中断所有正在执行的任务
        // 返回未开始执行的任务列表（这里不处理，因为会清空队列）
        virtualExecutor.shutdownNow();
        // 清空事件队列，丢弃所有待处理的事件
        // 这会释放内存，但队列中待处理的事件不会分发给钩子
        eventQueue.clear();
    }
}
