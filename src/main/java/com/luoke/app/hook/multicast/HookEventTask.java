package com.luoke.app.hook.multicast;

import com.luoke.app.hook.HookEventType;

/**
 * 钩子事件任务
 * <p>
 * 封装了事件类型和事件数据的不可变对象，用于在钩子系统的队列中传递。
 * 使用Java 14+的record特性，提供了简洁的不可变数据类实现。
 * <p>
 * 设计说明：
 * <ul>
 *   <li>使用record实现不可变对象，提供自动生成的构造器、访问器、equals、hashCode和toString</li>
 *   <li>不可变性确保线程安全，多线程并发访问无需同步</li>
 *   <li>轻量级设计，仅包含必要的数据字段</li>
 *   <li>作为数据传输对象（DTO），封装事件信息</li>
 * </ul>
 * <p>
 * 使用场景：
 * <ul>
 *   <li>作为事件队列的元素，在生产者和消费者之间传递</li>
 *   <li>封装事件类型和数据，便于统一处理</li>
 *   <li>提供类型信息，支持事件的分类和路由</li>
 * </ul>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>record本身不持有Native资源</li>
 *   <li>如果data字段包含Native资源，由调用方和订阅方协商管理</li>
 *   <li>作为不可变对象，不会导致资源泄漏</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>record的equals和hashCode是自动生成的，性能优于手动实现</li>
 *   <li>不可变性允许对象复用和缓存</li>
 *   <li>字段访问使用标准getter方法，性能高效</li>
 * </ul>
 * <p>
 * 线程安全说明：
 * <ul>
 *   <li>record是不可变的，线程安全</li>
 *   <li>多线程并发读取无需同步</li>
 *   <li>可以在多个线程间安全传递</li>
 * </ul>
 *
 * @param eventType 事件类型，指示该任务代表的钩子事件
 *                  该参数必须是HookEventType枚举的一个值。
 *                  钩子系统根据此字段查找订阅该事件类型的所有钩子。
 *                  例如：HookEventType.PLAYER_UPDATE表示玩家更新事件。
 *                  字段是final的，创建后不可修改。
 * @param data 事件数据，可以是任意对象
 *             该数据会被传递给所有订阅对应事件类型的钩子。
 *             不同的事件类型通常有不同的数据类型。
 *             例如：PLAYER_UPDATE事件传递PlayerData对象，MAP_CHANGED事件传递MapData对象。
 *             字段是final的，创建后不可修改。
 *             订阅方需要知道'数据的具体类型并进行适当的处理。
 * <p>
 * 使用示例：
 * <pre>
 * // 创建事件任务
 * HookEventTask task = new HookEventTask(HookEventType.PLAYER_UPDATE, playerData);
 *
 * // 访问事件类型
 * HookEventType type = task.eventType();
 *
 * // 访问事件数据
 * Object data = task.data();
 * </pre>
 */
public record HookEventTask(HookEventType eventType, Object data) {
}
