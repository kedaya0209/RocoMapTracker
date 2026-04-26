package com.luoke.app.hook;

import java.util.Set;

/**
 * 泛型钩子接口
 * <p>
 * 定义了钩子的基本契约，所有实现此接口的类都可以注册到钩子系统中
 * 并响应特定类型的事件。钩子系统采用观察者模式，实现了事件的发布-订阅机制。
 * <p>
 * 设计说明：
 * <ul>
 *   <li>使用泛型T指定事件数据类型，提供类型安全的回调接口</li>
 *   <li>通过supportedEvents()声明支持的事件类型，实现事件的按需分发</li>
 *   <li>onEvent()方法提供统一的回调接口，解耦事件源和处理逻辑</li>
 * </ul>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>接口本身不持有Native资源，生命周期由实现类管理</li>
 *   <li>事件数据data的生命周期由调用方和订阅方协商确定</li>
 *   <li>在钩子系统销毁时，需要正确清理所有钩子实例</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>使用泛型避免类型转换开销，提升运行时性能</li>
 *   <li>supportedEvents()返回Set允许快速查找，支持事件类型的批量处理</li>
 *   <li>接口设计轻量级，不包含复杂的依赖关系</li>
 * </ul>
 *
 * @param <T> 事件数据类型，必须是引用类型
 *             泛型参数T用于类型安全地传递事件数据，避免了传统的Object类型转换。
 *             使用泛型可以在编译期发现类型错误，减少运行时异常。
 *             例如：IHook<PlayerData>表示该钩子专门处理玩家数据类型的事件。
 */
public interface IHook<T> {

    /**
     * 获取该钩子支持的事件类型集合
     * <p>
     * 返回钩子能够处理的事件类型列表。钩子系统会根据此方法返回的结果
     * 将钩子注册到对应的事件类型下。只有当发布的事件类型在支持列表中时，
     * 该钩子的onEvent方法才会被调用。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>返回Set而不是List，因为事件类型应该是唯一的，不需要重复</li>
     *   <li>允许一个钩子同时支持多种事件类型，实现事件聚合处理</li>
     *   <li>返回的是不可变集合的引用，避免外部修改内部状态</li>
     * </ul>
     * <p>
     * 使用示例：
     * <pre>
     * &#64;Override
     * public Set&lt;HookEventType&gt; supportedEvents() {
     *     return Set.of(HookEventType.PLAYER_UPDATE, HookEventType.MAP_CHANGED);
     * }
     * </pre>
     *
     * @return 支持的事件类型集合，永不为null，可能为空集合
     *         返回Set.of()表示该钩子暂时不支持任何事件（极少使用）。
     *         返回Set.of(HookEventType.PLAYER_UPDATE)表示只支持玩家更新事件。
     *         返回Set.of(HookEventType.PLAYER_UPDATE, HookEventType.MAP_CHANGED)表示支持多种事件。
     */
    Set<HookEventType> supportedEvents();

    /**
     * 事件回调方法
     * <p>
     * 当钩子系统发布的事件类型被该钩子支持时，此方法会被调用。
     * 实现类在此方法中编写事件处理逻辑。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用泛型T作为数据参数，提供类型安全的回调接口</li>
     *   <li>方法签名简单清晰，只包含必要的事件信息</li>
     *   <li>实现类应该保证方法的线程安全性，因为可能在多线程环境调用</li>
     * </ul>
     * <p>
     * Native资源管理说明：
     * <ul>
     *   <li>如果data包含Native资源，实现类需要确保正确的资源释放</li>
     *   <li>避免在此方法中长时间阻塞，影响钩子系统的整体性能</li>
     *   <li>如需异步处理，应该将任务提交到独立的线程池</li>
     * </ul>
     * <p>
     * 使用示例：
     * <pre>
     * &#64;Override
     * public void onEvent(HookEventType type, PlayerData data) {
     *     switch (type) {
     *         case PLAYER_UPDATE:
     *             updatePlayer(data);
     *             break;
     *         case MAP_CHANGED:
     *             refreshMap();
     *             break;
     *     }
     * }
     * </pre>
     *
     * @param type 事件类型，指示当前回调的具体事件
     *             该参数由钩子系统传入，保证是supportedEvents()返回的集合中的一个。
     *             实现类可以使用switch-case语句或if-else链来区分不同的事件处理逻辑。
     *             类型安全保证不会出现不支持的事件类型。
     * @param data 事件数据，泛型类型T
     *             该参数包含了事件相关的数据，具体类型由泛型参数T决定。
     *             例如，对于PLAYER_UPDATE事件，data可能是PlayerData对象；
     *             对于MAP_CHANGED事件，data可能是MapData对象。
     *             实现类应该根据type参数决定如何解析和使用data。
     */
    void onEvent(HookEventType type, T data);
}
