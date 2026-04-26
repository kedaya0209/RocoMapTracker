package com.luoke.app.hook.multicast;

import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.container.HookContainer;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 钩子事件多播分发器
 * <p>
 * 负责将发布的事件异步地分发给所有订阅的钩子。使用生产者-消费者模式，
 * 通过阻塞队列实现事件的异步处理和多播分发。
 * <p>
 * 设计模式说明：
 * <ul>
 *   <li>单例模式：确保全局唯一的分发器实例</li>
 *   <li>生产者-消费者模式：解耦事件发布和处理</li>
 *   <li>观察者模式：将事件分发给多个观察者（钩子）</li>
 * </ul>
 * <p>
 * 架构设计：
 * <ul>
 *   <li>使用虚拟线程作为消费者，降低线程创建和上下文切换的开销</li>
 *   <li>使用LinkedBlockingQueue作为事件队列，支持无界增长</li>
 *   <li>使用volatile running标志确保线程可见性</li>
 * </ul>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>分发器不直接持有Native资源</li>
 *   <li>事件数据可能包含Native资源，由钩子负责释放</li>
 *   <li>关闭时会清理线程池和队列，释放系统资源</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>使用虚拟线程减少系统线程创建开销</li>
 *   <li>使用非阻塞的offer()方法，避免生产者阻塞</li>
 *   <li>异常隔离确保单个钩子失败不影响其他钩子</li>
 *   <li>批量分发减少同步开销</li>
 * </ul>
 */
@Slf4j
public class HookMulticaster {

    /**
     * 单例实例
     * <p>
     * 使用饿汉式初始化，确保实例在类加载时就创建。
     * 这种方式简单且线程安全，因为Java保证类加载的线程安全性。
     */
    private static final HookMulticaster INSTANCE = new HookMulticaster();

    /**
     * 钩子容器
     * <p>
     * 负责存储和管理所有注册的钩子，按事件类型组织。
     * 分发器通过容器查找订阅特定事件类型的所有钩子。
     * <p>
     * 使用说明：
     * 容器内部使用ConcurrentHashMap和CopyOnWriteArrayList，
     * 保证了线程安全的查询操作。
     */
    private final HookContainer container;

    /**
     * 事件队列
     * <p>
     * 使用LinkedBlockingQueue实现阻塞队列，支持生产者-消费者模式。
     * 队列是无界的（Integer.MAX_VALUE容量），能够处理突发的大量事件。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用LinkedBlockingQueue而非ArrayBlockingQueue，避免容量限制</li>
     *   <li>无界队列在高并发下可能导致内存溢出，需要监控队列长度</li>
     *   <li>使用HookEventTask封装事件信息，便于扩展</li>
     * </ul>
     * <p>
     * 线程安全说明：
     * LinkedBlockingQueue内部使用可重入锁（ReentrantLock），
     * 提供了线程安全的put、take和offer操作。
     */
    private final BlockingQueue<HookEventTask> eventQueue;

    /**
     * 虚拟线程执行器
     * <p>
     * 使用Java 21引入的虚拟线程执行器，为每个任务创建一个虚拟线程。
     * 虚拟线程由JVM管理，不绑定到操作系统线程，大大降低了线程创建成本。
     * <p>
     *   * 设计说明：
     * <ul>
     *   <li>虚拟线程轻量级，可以创建成千上万个而不会耗尽系统资源</li>
     *   <li>适合IO密集型任务，如事件分发和钩子执行</li>
     *   <li>不适用于CPU密集型任务，可能导致调度开销过大</li>
     * </ul>
     * <p>
     * Native Image说明：
     * 在GraalVM Native Image环境中，虚拟线程支持需要特定的配置。
     * 需要确保JVM运行时支持虚拟线程（JDK 21+）。
     */
    private final ExecutorService virtualExecutor;

    /**
     * 运行标志
     * <p>
     * 使用volatile关键字确保多线程之间的可见性。
     * 当running为false时，消费者线程会停止处理事件。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>volatile保证写操作对其他线程立即可见</li>
     *   <li>避免线程本地缓存导致的数据不一致</li>
     *   <li>使用boolean而非AtomicBoolean，简化逻辑</li>
     * </ul>
     */
    private volatile boolean running;

    /**
     * 私有构造函数
     * <p>
     * 初始化所有组件并启动消费者线程。
     * 使用私有构造函数确保单例模式。
     * <p>
     * 初始化顺序：
     * <ol>
     *   <li>获取钩子容器单例</li>
     *   <li>创建无界事件队列</li>
     *   <li>创建虚拟线程执行器</li>
     *   <li>设置运行标志为true</li>
     *   <li>启动消费者线程</li>
     * </ol>
     */
    private HookMulticaster() {
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

    /**
     * 获取单例实例
     * <p>
     * 返回HookMulticaster的唯一实例。
     * 使用饿汉式初始化，线程安全且无锁。
     * <p>
     * 使用示例：
     * <pre>
     * HookMulticaster multicaster = HookMulticaster.getInstance();
     * multicaster.enqueue(HookEventType.PLAYER_UPDATE, playerData);
     * </pre>
     *
     * @return HookMulticaster单例实例，永不为null
     */
    public static HookMulticaster getInstance() {
        return INSTANCE;
    }

    /**
     * 将事件加入队列（生产者操作）
     * <p>
     * 将指定的事件和数据封装为任务，加入事件队列。
     * 使用非阻塞的offer()方法，如果队列已满则立即返回false，不会阻塞调用线程。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>非阻塞操作，确保生产者不会被阻塞</li>
     *   <li>队列无界（Integer.MAX_VALUE），通常不会满</li>
     *   <li>检查running标志，避免关闭后继续入队</li>
     * </ul>
     * <p>
     * 线程安全说明：
     * LinkedBlockingQueue的offer()方法是线程安全的，可以在多线程中并发调用。
     * <p>
     * 性能说明：
     * 时间复杂度为O(1)，是无锁操作（大部分情况下）。
     * 调用线程不会被阻塞，可以立即返回，适合高频事件发布。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>如果分发器已关闭，事件会被丢弃</li>
     *   <li>无界队列在内存不足时会抛出OutOfMemoryError</li>
     *   <li>高频发布可能导致队列长度增长，需要监控</li>
     * </ul>
     *
     * @param eventType 事件类型，指示要发布的事件
     *                  该参数必须是HookEventType枚举的一个值。
     *                  分发器会查找订阅该事件类型的所有钩子。
     * @param data 事件数据，可以是任意对象
     *             该数据会被传递给所有订阅该事件类型的钩子。
     *             钩子需要知道数据的具体类型并进行处理。
     */
    public void enqueue(HookEventType eventType, Object data) {
        // 检查运行标志，避免在分发器关闭后继续入队
        if (!running) {
            return;
        }
        // 使用非阻塞的'offer()'方法将事件任务加入队列
        // 相比'put()'方法，'offer()'不会阻塞，如果队列满则返回false
        // 由于队列是无界的，通常不会满，这个检查主要是防御性编程
        eventQueue.offer(new HookEventTask(eventType, data));
    }

    /**
     * 启动消费者循环
     * <p>
     * 在虚拟线程中启动消费者循环，从队列中取出事件并分发。
     * 使用阻塞的take()方法等待事件，在没有事件时线程会挂起，不消耗CPU。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用虚拟线程减少系统资源消耗</li>
     *   <li>使用take()方法阻塞等待，避免忙等待</li>
     *   <li>异常处理确保线程稳定运行</li>
     * </ul>
     * <p>
     * 线程安全说明：
     * 此方法在虚拟线程中执行，与生产者线程通过队列通信，是线程安全的。
     * <p>
     * 性能说明：
     * 消费者线程在没有事件时会挂起，不消耗CPU。
     * 有事件时会立即处理，延迟极低。
     * <p>
     * 异常处理：
     * <ul>
     *   <li>InterruptedException：线程被中断，恢复中断状态并退出</li>
     *   <li>Exception：分发过程中异常，记录日志后继续消费</li>
     *   <li>Throwable：其他严重错误，记录日志后继续消费</li>
     * </ul>
     */
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
                    // 分发过程中发生异常，记录错误日志后继续消费
                    // 使用日志记录异常详情，便于问题排查
                    log.error("钩子事件消费异常", e);
                }
            }
        });
    }

    /**
     * 事件分发核心逻辑
     * <p>
     * 查找订阅指定事件类型的所有钩子，并调用它们的onEvent方法。
     * 实现了事件的广播（一对多）分发模式。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>从容器中查找订阅的钩子列表</li>
     *   <li>遍历钩子列表，逐个调用回调方法</li>
     *   <li>异常隔离确保单个钩子失败不影响其他钩子</li>
     *   <li>使用@SuppressWarnings抑制rawtypes和unchecked警告</li>
     * </ul>
     * <p>
     * 类型安全说明：
     * 由于使用了泛型擦除，分发器无法进行类型检查。
     * 依赖钩子的泛型声明保证类型安全，运行时可能出现类型转换异常。
     * 使用@SuppressWarnings压制编译警告，因为这是有意为之的设计。
     * <p>
     * 异常处理：
     * 捕获Throwable而非Exception，确保即使是Error也能被捕获。
     * 这样可以防止单个钩子的错误导致整个消费者线程崩溃。
     * <p>
     * 性能说明：
     * 时间复杂度为O(n)，其中n为订阅该事件类型的钩子数量。
     * 遍历钩子列表并调用回调方法是串行的，保证顺序性。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>钩子的onEvent方法应该快速返回，避免阻塞消费者</li>
     *   <li>如需异步处理，钩子应该将任务提交到独立线程池</li>
     *   <li>钩子'的异常会被记录日志，但不会影响其他钩子</li>
     * </ul>
     *
     * @param eventType 事件类型，指示要分发的事件
     *                  用于从容器中查找订阅该事件类型的钩子。
     * @param data 事件数据，将被传递给所有订阅的钩子
     *             数据类型由钩子的泛型参数决定，分发器不做类型检查。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatch(HookEventType eventType, Object data) {
        // 从容器中获取订阅该事件类型的钩子列表
        // getHookList()返回的是线程安全的CopyOnWriteArrayList
        List<AbstractGenericHook<?>> hookList = container.getHookList(eventType);
        // 如果没有订阅的钩子，直接返回，避免不必要的操作
        if (hookList.isEmpty()) {
            return;
        }

        // 遍历所有订阅的钩子，逐个调用回调方法
        // 使用增强for循环遍历CopyOnWriteArrayList，是线程安全的
        for (AbstractGenericHook<?> hook : hookList) {
            try {
                // 调用钩子的onEvent方法，传递事件类型和数据
                // 由于泛型擦除，需要强制转换，使用@SuppressWarnings压制警告
                // 运行时可能出现类型转换异常，但依赖钩子的泛型声明保证正确性
                ((AbstractGenericHook) hook).onEvent(eventType, data);
            } catch (Throwable t) {
                // 捕获所有异常（包括Error），记录错误日志
                // 异常隔离确保单个钩子的失败不影响其他钩子
                // 使用日志记录异常详情和事件类型，便于问题排查
                log.error("钩子执行异常，event:{}", eventType, t);
            }
        }
    }

    /**
     * 优雅关闭分发器
     * <p>
     * 停止消费者线程，清空事件队列，关闭线程池。
     * 通常在应用程序关闭时调用，确保资源正确释放。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>设置running标志为false，停止消费者循环</li>
     *   <li>关闭线程池，中断所有虚拟线程</li>
     *   <li>清空事件队列，释放内存</li>
     * </ul>
     * <p>
     * 工作流程：
     * <ol>
     *   <li>设置running标志为false，消费者线程会检测到并退出</li>
     *   <li>调用shutdownNow()关闭线程池，中断所有虚拟线程</li>
     *   <li>清空事件队列，丢弃所有待处理的事件</li>
     *   <li>资源清理完成，分发器不可再使用</li>
     * </ol>
     * <p>
     * 线程安全说明：
     * 此方法不是完全线程安全的，与enqueue()可能存在竞争。
     * 建议在不再需要使用分发器时调用，并且不再调用enqueue()。
     * <p>
     * 性能说明：
     * shutdownNow()的时间复杂度为O(n)，其中n为虚拟线程数量。
     * clear()的时间复杂度为O(m)，其中m为队列中待处理事件数量。
     * 通常调用频率很低（应用程序关闭时），性能不是关键。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>关闭后不应再调用enqueue()方法</li>
     *   <li>队列中待处理的事件会被丢弃，不会分发给钩子</li>
     *   <li>正在执行的钩子可能会被中断</li>
     *   <li>关闭操作不可逆，需要重新创建实例才能使用</li>
     * </ul>
     */
    public void shutdown() {
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
