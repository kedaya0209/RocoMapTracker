package com.luoke.app.hook.container;

import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 钩子容器
 * <p>
 * 负责存储和管理所有注册的钩子，按事件类型组织钩子列表。
 * 提供了钩子的注册、查询和清理功能。
 * <p>
 * 设计说明：
 * <ul>
 *   <li>使用单例模式确保全局唯一的容器实例</li>
 *   <li>使用ConcurrentHashMap保证Map操作的线程安全</li>
 *   <li>使用CopyOnWriteArrayList保证List操作的线程安全</li>
 *   <li>按事件类型组织钩子，支持高效的查找和分发</li>
 * </ul>
 * <p>
 * 数据结构设计：
 * <pre>
 * Map&lt;HookEventType, List&lt;AbstractGenericHook&lt;?&gt;&gt;&gt; eventHookMap
 * </pre>
 * <ul>
 *   <li>Key：事件类型（HookEventType枚举）</li>
 *   <li>Value：订阅该事件类型的钩子列表</li>
 *   <li>支持一个钩子同时订阅多种事件类型</li>
 *   <li>支持多个钩子同时订阅同一种事件类型</li>
 * </ul>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>容器本身不持有Native资源</li>
 *   <li>钩子实例的生命周期由调用方管理</li>
 *   <li>clear()方法释放所有对钩子的引用，帮助垃圾回收</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>使用ConcurrentHashMap实现O(1)的查找性能</li>
 *   <li>使用CopyOnWriteArrayList在读多写少的场景下性能优秀</li>
 *   <li>computeIfAbsent()避免多次重复创建列表</li>
 * </ul>
 * <p>
 * 线程安全说明：
 * <ul>
 *   <li>ConcurrentHashMap保证Map操作的线程安全</li>
 *   <li>CopyOnWriteArrayList保证List操作的线程安全</li>
 *   <li>所有公开方法都是线程安全的，可在多线程中并发调用</li>
 * </ul>
 */
public class HookContainer {

    /**
     * 单例实例
     * <p>
     * 使用饿汉式初始化，确保实例在类加载时就创建。
     * 这种方式简单且线程安全，因为Java保证类加载的线程安全性。
     */
    private static final HookContainer INSTANCE = new HookContainer();

    /**
     * 事件类型到钩子列表的映射
     * <p>
     * 使用ConcurrentHashMap实现线程安全的Map操作。
     * Key是事件类型，Value是订阅该事件的所有钩子列表。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用ConcurrentHashMap而非HashMap，保证线程安全</li>
     *   <li>使用CopyOnWriteArrayList作为Value，保证List操作的线程安全</li>
     *   <li>支持一个事件类型对应多个钩子（一对多关系）</li>
     *   <li>支持一个钩子对应多个事件类型（多对一关系）</li>
     * </ul>
     * <p>
     * 数据流向：
     * <ul>
     *   <li>注册：钩子 -> 通过supportedEvents()获取事件类型 -> 添加到对应列表</li>
     *   <li>查询：事件类型 -> 从Map获取钩子列表 -> 返回给调用方</li>
     *   <li>清理：清空Map -> 释放所有钩子引用</li>
     * </ul>
     * <p>
     * 线程安全说明：
     * ConcurrentHashMap和CopyOnWriteArrayList的组合提供了完全的线程安全。
     * 多个线程可以同时注册、查询和清理，不会出现竞争条件。
     */
    // 事件类型 -> 对应钩子列表
    private final Map<HookEventType, List<AbstractGenericHook<?>>> eventHookMap;

    /**
     * 私有构造函数
     * <p>
     * 初始化事件到钩子的映射表。
     * 使用私有构造函数确保单例模式。
     * <p>
     * 初始化说明：
     * <ul>
     *   <li>创建空的ConcurrentHashMap实例</li>
     *   <li>使用默认构造函数，没有初始容量和负载因子设置</li>
     *   <li>Map会在首次插入数据时自动扩容</li>
     * </ul>
     * <p>
     * 性能说明：
     * ConcurrentHashMap的默认初始容量是16，负载因子是0.75。
     * 对于钩子系统，事件类型数量通常很少（3-5个），默认配置是合适的。
     */
    private HookContainer() {
        // 创建线程安全的ConcurrentHashMap实例
        // 用于存储事件类型到钩子列表的映射
        this.eventHookMap = new ConcurrentHashMap<>();
    }

    /**
     * 获取单例实例
     * <p>
     * 返回HookContainer的唯一实例。
     * 使用饿汉式初始化，线程安全且无锁。
     * <p>
     * 使用示例：
     * <pre>
     * HookContainer container = HookContainer.getInstance();
     * container.registerHook(HookEventType.PLAYER_UPDATE, myHook);
     * </pre>
     *
     * @return HookContainer单例实例，永不为null
     */
    public static HookContainer getInstance() {
        return INSTANCE;
    }

    /**
     * 注册钩子到指定事件类型
     * <p>
     * 将指定的钩子添加到订阅该事件类型的钩子列表中。
     * 如果该事件类型还没有钩子列表，会自动创建一个。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用computeIfAbsent()原子地获取或创建钩子列表</li>
     *   <li>使用CopyOnWriteArrayList保证List操作的线程安全</li>
     *   <li>允许同一个钩子被多次注册，但不推荐这样做</li>
     * </ul>
     * <p>
     * 工作流程：
     * <ol>
     *   <li>检查Map中是否存在该事件类型的钩子列表</li>
     *   <li>如果不存在，创建新的CopyOnWriteArrayList</li>
     *   <li>将钩子添加到列表中</li>
     *   <li>注册完成，钩子可以接收该类型的事件</li>
     * </ol>
     * <p>
     * 线程安全说明：
     * <ul>
     *   <li>computeIfAbsent()是原子操作，线程安全</li>
     *   <li>CopyOnWriteArrayList.add()是线程安全的</li>
     *   <li>可以安全地在多线程中并发注册</li>
     * </ul>
     * <p>
     * 性能说明：
     * <ul>
     *   <li>compute时间复杂度为O(1)，基于HashMap的查找</li>
     *   <li>add时间复杂度为O(n)，需要复制整个数组</li>
     *   <li>写操作相对昂贵，读操作非常便宜</li>
     *   <li>适用于读多写少的场景（注册频率低，查询频率高）</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>同一个钩子可以重复注册，会收到多次事件</li>
     *   <li>重复注册会增加内存消耗和事件分发开销</li>
     *   <li>建议在注册前检查钩子是否已注册，避免重复</li>
     * </ul>
     *
     * @param eventType 事件类型，钩子要订阅的事件
     *                  该参数必须是HookEventType枚举的一个值。
     *                  钩子将被添加到订阅该事件类型的钩子列表中。
     *                  同一个钩子可以订阅多个事件类型。
     * @param hook 要注册的钩子实例，不能为null
     *             钩子必须实现AbstractGenericHook接口。
     *             钩子会被添加到订阅该事件类型的钩子列表中。
     *             钩子的生命周期由调用方管理，容器只持有引用。
     */
    public void registerHook(HookEventType eventType, AbstractGenericHook<?> hook) {
        // 使用computeIfAbsent()原子地获取或创建钩子列表
        // 如果Map中不存在该事件类型的列表，则创建新的CopyOnWriteArrayList
        // computeIfAbsent()是线程安全的，避免了"检查-创建"的竞态条件
        eventHookMap.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(hook);
    }

    /**
     * 获取订阅指定事件类型的所有钩子
     * <p>
     * 返回订阅该事件类型的钩子列表。
     * 如果该事件类型没有订阅的钩子，返回空'列表。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用getOrDefault()提供默认值，避免null返回</li>
     *   <li>返回不可变的空列表（Collections.EMPTY_LIST），避免NullPointerException</li>
     *   <li>返回的列表是线程安全的，可以安全遍历</li>
     * </ul>
     * <p>
     * 使用示例：
     * <pre>
     * List&lt;AbstractGenericHook&lt;?&gt;&gt; hooks = container.getHookList(HookEventType.PLAYER_UPDATE);
     * for (AbstractGenericHook&lt;?&gt; hook : hooks) {
     *     // 调用钩子
     * }
     * </pre>
     * <p>
     * 线程安全说明：
     * <ul>
     *   <li>getOrDefault()是线程安全的读操作</li>
     *   <li>返回的CopyOnWriteArrayList是线程安全的</li>
     *   <li>可以安全地在多线程中并发查询和遍历</li>
     * </ul>
     * <p>
     * 性能说明：
     * <ul>
     *   <li>时间复杂度为O(1)，基于HashMap的查找</li>
     *   <li>CopyOnWriteArrayList的迭代器是快照，遍历过程中不受修改影响</li>
     *   <li>读操作非常便宜，适合高频调用</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>返回的列表引用不应被外部修改</li>
     *   <li>返回的列表可能是空的，调用方应处理这种情况</li>
     *   <li>返回的列表快照可能与当前状态不完全一致（不影响正确性）</li>
     * </ul>
     *
     * @param eventType 事件类型，要查询的事件
     *                  该参数必须是HookEventType枚举的一个值。
     *                  用于查找订阅该事件类型的钩子列表。
     * @return 订阅该事件类型的钩子列表，永不为null，可能为空列表
     *         如果该事件类型有订阅的钩子，返回对应的钩子列表（CopyOnWriteArrayList）。
     *         如果该事件类型没有订阅的钩子，返回不可变的空列表（Collections.EMPTY_LIST）。
     *         返回的列表是线程安全的，可以安全遍历和读取。
     */
    public List<AbstractGenericHook<?>> getHookList(HookEventType eventType) {
        // 使用getOrDefault()获取钩子列表，如果不存在则返回不可变的空列表
        // Collections.EMPTY_LIST是一个共享的不可变空列表，避免创建多个空列表实例
        // 返回空列表而不是null，避免了调用方进行null检查
        return eventHookMap.getOrDefault(eventType, Collections.emptyList());
    }

    /**
     * 清除所有钩子
     * <p>
     * 清空事件到钩子的映射表，释放所有对钩子的引用。
     * 通常在钩子系统关闭时调用，帮助垃圾回收。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>调用Map的clear()方法，清空所有映射</li>
     *   <li>释放所有对钩子实例的引用</li>
     *   <li>不影响钩子实例本身，只是释放容器中的引用</li>
     * </ul>
     * <p>
     * 工作流程：
     * <ol>
     *   <li>清空Map中的所有键值对</li>
     *   <li>释放所有钩子列表的引用</li>
     *   <li>释放所有钩子实例的引用</li>
     *   <li>容器变为空状态，可以重新注册钩子</li>
     * </ol>
     * <p>
     * 线程安全说明：
     * <ul>
     *   <li>ConcurrentHashMap.clear()是线程安全的</li>
     *   <li>可以安全地在多线程中调用</li>
     *   <li>正在进行的查询可能看到部分清理的视图</li>
     * </ul>
     * <p>
     * 性能说明：
     * <ul>
     *   <li>时间复杂度为O(m)，其中m为Map中的映射数量</li>
     *   <li>clear()操作会清空内部数组，但不会释放内存</li>
     *   <li>通常调用频率很低（系统关闭时），性能不是关键</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>清理后容器变为空，需要重新注册钩子</li>
     *   <li>正在进行的查询可能返回不一致的结果</li>
     *   <li>清理操作不可逆，需要重新注册才能使用</li>
     *   <li>不会影响钩子实例本身的生命周期，由调用方管理</li>
     * </ul>
     * <p>
     * 使用示例：
     * <pre>
     * // 在应用程序关闭时
     * HookContainer.getInstance().clear();
     * </pre>
     */
    public void clear() {
        // 清空Map中的所有映射，释放所有钩子引用
        // 这会帮助垃圾回收器回收不再使用的钩子实例
        // 注意：这不会影响钩子实例本身，只是释放容器中的引用
        eventHookMap.clear();
    }
}
