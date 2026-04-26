package com.luoke.app.hook;

import com.luoke.app.hook.container.HookContainer;
import com.luoke.app.hook.multicast.HookMulticaster;

/**
 * 钩子注册中心
 * <p>
 * 提供了钩子系统的全局访问点，负责钩子的注册和事件的发布。
 * 采用单例模式确保整个应用程序中只有一个钩子注册中心实例，
 * 实现了钩子的统一管理和事件的集中分发。
 * <p>
 * 设计模式说明：
 * <ul>
 *   <li>单例模式：确保全局唯一的注册中心，避免钩子实例重复注册</li>
 *   <li>门面模式：简化钩子系统的使用，提供统一的注册和发布接口</li>
 *   <li>观察者模式：实现了事件的发布-订阅机制</li>
 * </ul>
 * <p>
 * 组件职责：
 * <ul>
 *   <li>HookContainer：存储和管理所有注册的钩子，按事件类型组织</li>
 *   <li>HookMulticaster：负责事件的异步分发和多播</li>
 * </ul>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>注册中心不直接持有Native资源</li>
 *   <li>销毁时会清理所有钩子容器和事件分发器</li>
 *   <li>应用程序关闭前应调用destroy()方法确保资源正确释放</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>注册操作使用批量方法registers()减少遍历开销</li>
 *   <li>事件发布使用非阻塞队列，避免阻塞生产者线程</li>
 *   <li>销毁操作清理所有内部资源</li>
 * </ul>
 */
public enum HookRegistry {
    /**
     * 单例实例
     * <p>
     * 使用枚举实现单例模式，这是Java中推荐的单例实现方式。
     * 枚举单例具有以下优势：
     * <ul>
     *   <li>线程安全：枚举的创建是线程安全的，无需额外的同步措施</li>
     *   <li>序列化安全：枚举天生支持序列化，无需额外处理</li>
     *   <li>防止反射攻击：枚举无法通过反射创建新实例</li>
     *   <li>延迟加载：枚举在首次访问时才初始化</li>
     * </ul>
     * <p>
     * 使用说明：
     * 通过HookRegistry.INSTANCE访问单例实例，例如：
     * <pre>
     * HookRegistry.INSTANCE.register(myHook);
     * HookRegistry.INSTANCE.publish(HookEventType.PLAYER_UPDATE, playerData);
     * </pre>
     */
    INSTANCE;

    /**
     * 钩子容器
     * <p>
     * 负责存储和管理所有注册的钩子，按事件类型组织钩子列表。
     * 提供了钩子的注册、查询和清理功能。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用单例模式确保全局唯一的容器实例</li>
     *   <li>使用ConcurrentHashMap和CopyOnWriteArrayList保证线程安全</li>
     *   <li>按事件类型组织钩子，支持高效的查找和分发</li>
     * </ul>
     * <p>
     * 使用说明：
     * 注册中心将具体的注册和查询操作委托给容器实现，
     * 提供了更清晰的职责划分。
     */
    private final HookContainer container = HookContainer.getInstance();

    /**
     * 注册单个钩子
     * <p>
     * 将指定的钩子注册到钩子系统中，使其能够接收和处理事件。
     * 钩子会被注册到它支持的所有事件类型下。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>遍历钩子支持的所有事件类型（supportedEvents()）</li>
     *   <li>将钩子注册到每个对应的事件类型下</li>
     *   <li>支持一个钩子同时处理多种事件类型</li>
     * </ul>
     * <p>
     * 使用示例：
     * <pre>
     * // 创建钩子实例
     * PlayerHook playerHook = new PlayerHook();
     * // 注册钩子
     * HookRegistry.INSTANCE.register(playerHook);
     * </pre>
     * <p>
     * 线程安全说明：
     * HookContainer内部使用线程安全的数据结构，此方法可以安全地在多线程环境中调用。
     * <p>
     * 性能说明：
     * 时间复杂度为O(n)，其中n为钩子支持的事件类型数量。
     * 由于钩子支持的事件类型数量通常很少（1-3个），性能影响可以忽略。
     *
     * @param hook 要注册的钩子实例，不能为null
     *             钩子必须实现AbstractGenericHook接口，并正确实现supportedEvents()方法。
     *             如果钩子支持的事件类型集合为空，则不会被注册到任何事件。
     *             同一个钩子可以重复注册，但通常不建议这样做。
     */
    public void register(AbstractGenericHook<?> hook) {
        // 遍历钩子支持的所有事件类型
        // 钩子通过supportedEvents()方法声明它能够处理的事件类型
        for (HookEventType eventType : hook.supportedEvents()) {
            // 将钩子注册到容器中的对应事件类型下
            // 容器内部会创建或获取该事件类型的钩子列表，并将钩子添加进去
            container.registerHook(eventType, hook);
        }
    }

    /**
     * 批量注册多个钩子
     * <p>
     * 将多个钩子一次性注册到钩子系统中。相比多次调用register()方法，
     * 批量注册可以减少方法调用的开销，提供更简洁的API。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用可变参数支持任意数量的钩子注册</li>
     *   <li>遍历每个钩子，注册到它支持的所有事件类型下</li>
     *   <li>提供了便利性，减少样板代码</li>
     * </ul>
     * <p>
     * 使用示例：
     * <pre>
     * // 创建多个钩子实例
     * PlayerHook playerHook = new PlayerHook();
     * MapHook mapHook = new MapHook();
     * FrameHook frameHook = new FrameHook();
     *
     * // 批量注册钩子
     * HookRegistry.INSTANCE.registers(playerHook, mapHook, frameHook);
     * </pre>
     * <p>
     * 线程安全说明：
     * 内部调用register()方法，具有相同的线程安全保证。
     * <p>
     * 性能说明：
     * 时间复杂度为O(m*n)，其中m为钩子数量，n为每个钩子支持的事件类型平均数量。
     * 相比多次单独调用，减少了方法调用的开销，但算法复杂度相同。
     *
     * @param hooks 要注册的钩子实例数组，不能为null，数组元素不能为null
     *              支持使用Java可变参数语法传入多个钩子。
     *              可以传入0个钩子（什么都不做），但通常不会这样做。
     *              同一个钩子可以在数组中出现多次，但通常不建议这样做。
     */
    public void registers(AbstractGenericHook<?>... hooks) {
        // 遍历所有要注册的钩子
        for (AbstractGenericHook<?> hook : hooks) {
            // 遍历钩子支持的所有事件类型
            // 与单个注册方法的逻辑相同
            for (HookEventType eventType : hook.supportedEvents()) {
                // 将钩子注册到容器中的对应事件类型下
                container.registerHook(eventType, hook);
            }
        }
    }

    /**
     * 发布事件到钩子系统
     * <p>
     * 将指定的事件和数据发布到钩子系统中，触发所有订阅该事件类型的钩子。
     * 事件会被放入事件队列，由HookMulticaster异步分发。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用异步队列模式，避免阻塞调用线程</li>
     *   <li>事件分发由HookMulticaster负责，实现解耦</li>
     *   <li>调用线程不会被阻塞，可以立即返回</li>
     * </ul>
     * <p>
     * 工作流程：
     * <ol>
     *   <li>创建HookEventTask对象封装事件类型和数据</li>
     *   <li>将任务放入HookMulticaster的事件队列</li>
     *   <li>HookMulticaster的消费者线程会处理队列中的任务</li>
     *   <li>消费者从容器中查找订阅该事件的所有钩子</li>
     *   <li>调用每个钩子的onEvent方法</li>
     * </ol>
     * <p>
     * 使用示例：
     * <pre>
     * // 发布玩家更新事件
     * PlayerData playerData = new PlayerData(x, y, state);
     * HookRegistry.INSTANCE.publish(HookEventType.PLAYER_UPDATE, playerData);
     *
     * // 发布地图变化事件
     * HookRegistry.INSTANCE.publish(HookEventType.MAP_CHANGED, mapData);
     * </pre>
     * <p>
     * 线程安全说明：
     * 此方法使用非阻塞队列（LinkedBlockingQueue.offer()），是线程安全的。
     * 可以在多线程环境中同时发布事件，不会出现竞争条件。
     * <p>
     * 性能说明：
     * 时间复杂度为O(1)，使用队列的offer()方法是无锁的（大部分情况下）。
     * 事件分发是异步的，调用线程的延迟极小。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>事件数据data的生命周期由调用方和订阅方协商确定</li>
     *   <li>如果data包含Native资源，订阅方需要确保正确释放</li>
     *   <li>在钩子系统关闭后发布的事件会被忽略</li>
     * </ul>
     *
     * @param eventType 事件类型，指示要发布的事件
     *                  该参数必须是HookEventType枚举的一个值。
     *                  只有注册了支持该事件类型的钩子才会被触发。
     *                  建议使用枚举常量而非字符串或整数。
     * @param data 事件数据，可以是任意对象
     *             该数据会被传递给所有订阅该事件类型的钩子。
     *             不同的事件类型通常有不同的数据类型。
     *             例如：PLAYER_UPDATE事件传递PlayerData对象，MAP_CHANGED事件传递MapData对象。
     *             订阅方需要知道数据的具体类型并进行适当的类型转换或处理。
     */
    public void publish(HookEventType eventType, Object data) {
        // 将事件任务加入队列，由HookMulticaster异步分发
        // 使用非阻塞的offer()方法，如果队列已满则立即返回false，不会阻塞
        // HookMulticaster内部会处理队列满的情况（日志记录）
        HookMulticaster.getInstance().enqueue(eventType, data);
    }

    /**
     * 销毁钩子系统
     * <p>
     * 清理钩子系统的所有资源，停止事件分发，清除所有注册的钩子。
     * 通常在应用程序关闭前调用此方法，确保资源正确释放。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>停止HookMulticaster的事件分发线程</li>
     *   <li>清空HookContainer中的所有钩子</li>
     *   <li>清理事件队列中的待处理任务</li>
     * </ul>
     * <p>
     * 工作流程：
     * <ol>
     *   <li>调用HookMulticaster.shutdown()停止事件分发</li>
     *   <li>调用HookContainer.clear()清除所有钩子</li>
     *   <li>资源清理完成，钩子系统不可再使用</li>
     * </ol>
     * <p>
     * 使用示例：
     * <pre>
     * // 在应用程序关闭时
     * Runtime.getRuntime().addShutdownHook(new Thread(() -> {
     *     HookRegistry.INSTANCE.destroy();
     * }));
     * </pre>
     * <p>
     * 线程安全说明：
     * 此方法不是完全线程安全的，与publish()和register()方法可能存在竞争。
     * 建议在不再需要使用钩子系统时调用，并且不再调用其他方法。
     * <p>
     * 性能说明：
     * shutdown()的时间复杂度为O(1)，clear()的时间复杂度为O(m)，
     * 其中m为注册的钩子数量。通常调用频率很低（应用程序关闭时），性能不是关键。
     * <p>
     * 注意事项：
     * <ul>
     *   <li>销毁后不应再调用publish()或register()方法</li>
     *   <li>销毁后已发布的事件可能不会分发完</li>
     *   <li>销毁操作不可逆，需要重新创建才能使用钩子系统</li>
     * </ul>
     */
    public void destroy() {
        // 停止HookMulticaster的事件分发
        // 这会设置运行标志为false，中断消费线程，清空事件队列
        HookMulticaster.getInstance().shutdown();
        // 清除HookContainer中的所有钩子
        // 这会释放所有对钩子实例的引用，帮助垃圾回收
        container.clear();
    }
}
