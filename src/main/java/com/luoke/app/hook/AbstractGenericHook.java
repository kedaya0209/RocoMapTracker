package com.luoke.app.hook;

import lombok.Getter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * 抽象泛型钩子基类
 * <p>
 * 提供了泛型钩子的基础实现，解决了Java泛型擦除带来的类型信息丢失问题。
 * 通过反射机制在构造时捕获真实的泛型类型参数，为子类提供类型安全的事件处理能力。
 * <p>
 * 设计模式说明：
 * 本类复刻了Jackson框架中TypeReference的设计方案，通过匿名内部类或继承来捕获泛型类型。
 * 这种方式被称为"Type Token"模式，是Java中处理泛型类型擦除的常用技巧。
 * <p>
 * 使用示例：
 * <pre>
 * // 创建针对PlayerData类型的钩子
 * public class PlayerHook extends AbstractGenericHook&lt;PlayerData&gt; {
 *     &#64;Override
 *     public Set&lt;HookEventType&gt; supportedEvents() {
 *         return Set.of(HookEventType.PLAYER_UPDATE);
 *     }
 *
 *     &#64;Override
 *     public void onEvent(HookEventType eventType, PlayerData data) {
 *         // 处理玩家数据
 *     }
 * }
 *
 * // 使用时直接实例化，泛型类型会被自动捕获
 * HookRegistry.INSTANCE.register(new PlayerHook());
 * </pre>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>基类不持有Native资源，资源管理由子类负责</li>
 *   <li>反射操作在构造时执行一次，后续使用无开销</li>
 *   <li>在Native Image环境中，反射配置需要正确注册</li>
 * </ul>
 * <p>
 * 性能优化要点：
 * <ul>
 *   <li>泛型类型捕获只在构造时执行一次，性能开销极小</li>
 *   <li>使用@Getter注解自动生成getter，避免手写样板代码</li>
 *   <li>抽象方法避免子类重复实现通用逻辑</li>
 * </ul>
 *
 * @param <T> 事件数据类型，必须是引用类型
 *             泛型参数T指定该钩子处理的事件数据类型。
 *             通过继承并传入具体的类型参数，可以实现类型安全的事件处理。
 *             例如：AbstractGenericHook<PlayerData>表示该钩子专门处理PlayerData类型的事件。
 */
@Getter
public abstract class AbstractGenericHook<T> implements IHook<T> {

    /**
     * 数据泛型的真实类型
     * <p>
     * 由于Java的泛型擦除机制，运行时无法直接获取泛型参数的实际类型。
     * 本字段通过反射在构造时捕获并保存泛型参数的真实类型信息。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>使用java.lang.reflect.Type接口而非Class，支持更复杂的泛型类型</li>
     *   <li>类型捕获在构造函数中完成，确保字段被正确初始化</li>
     *   <li>使用protected访问权限，允许子类访问但外部无法修改</li>
     * </ul>
     * <p>
     * 使用场景：
     * <ul>
     *   <li>类型检查：在事件分发前验证事件数据的类型是否匹配</li>
     *   <li>日志记录：记录钩子处理的具体数据类型</li>
     *   <li>序列化：根据类型信息进行对象的序列化和反序列化</li>
     * </ul>
     * <p>
     * Native Image配置说明：
     * 在GraalVM Native Image环境中，反射需要特殊配置。
     * 需要在reflect-config.json中注册本类的构造函数和genericSuperclass属性访问权限。
     * 否则，反射操作会抛出异常。
     */
    protected final Type dataGenericType;

    /**
     * 构造函数：捕获泛型类型参数
     * <p>
     * 通过反射机制获取子类继承本类时传入的泛型参数的实际类型。
     * 这是解决Java泛型擦除问题的关键步骤。
     * <p>
     * 实现原理：
     * <ol>
     *   <li>获取当前类（子类）的泛型超类信息（GenericSuperclass）</li>
     *   <li>将超类类型转换为ParameterizedType（参数化类型）</li>
     *   <li>从参数化类型中提取实际的类型参数</li>
     *   <li>保存第一个类型参数（因为本类只有一个泛型参数T）</li>
     * </ol>
     * <p>
     * 类型安全保证：
     * <ul>
     *   <li>本类是抽象类，必须被子类继承</li>
     *   <li>子类必须明确指定泛型类型参数</li>
     *   <li>如果子类也是泛型类型，捕获到的可能仍是类型变量</li>
     * </ul>
     * <p>
     * 异常情况处理：
     * <ul>
     *   <li>如果子类未指定泛型参数，可能抛出ClassCastException</li>
     *   <li>在Native Image环境中，需要正确配置反射权限</li>
     *   <li>建议通过单元测试验证类型捕获的正确性</li>
     * </ul>
     * <p>
     * 性能调用说明：
     * 反射操作只在构造时执行一次，后续使用dataGenericType字段无需任何反射调用，
     * 因此运行时性能开销为零。
     */
    protected AbstractGenericHook() {
        // 获取当前类（子类）的泛型超类信息
        // 例如：对于PlayerHook extends AbstractGenericHook<PlayerData>，
        // this.getClass()返回PlayerHook.class，getGenericSuperclass()返回AbstractGenericHook<PlayerData>
        // 捕获子类真实泛型类型，规避泛型擦除问题
        ParameterizedType pt = (ParameterizedType) this.getClass().getGenericSuperclass();
        // 从参数化类型中提取实际的类型参数
        // getActualTypeArguments()返回所有类型参数数组，本类只有一个参数T，所以取索引0
        this.dataGenericType = pt.getActualTypeArguments()[0];
    }

    /**
     * 泛型事件回调方法
     * <p>
     * 子类必须实现此方法来处理特定类型的事件。此方法提供了类型安全的事件处理接口，
     * 避免了传统的Object类型转换，减少了运行时类型错误。
     * <p>
     * 设计说明：
     * <ul>
     *   <li>声明为abstract方法强制子类实现</li>
     *   <li>使用泛型T提供类型安全的数据参数</li>
     *   <li>方法签名与IHook接口保持一致，确保契约履行</li>
     * </ul>
     * <p>
     * 实现要点：
     * <ul>
     *   <li>子类应首先检查eventType，确保处理期望的事件类型</li>
     *   <li>数据处理时应进行必要的空值检查和边界验证</li>
     *   <li>避免在此方法中执行耗时操作，影响钩子系统性能</li>
     *   <li>如需异步处理，将任务提交到独立的线程池</li>
     * </ul>
     * <p>
     * Native资源管理说明：
     * <ul>
     *   <li>如果data参数包含Native资源，子类需要确保正确释放</li>
     *   <li>建议使用try-finally块确保资源的清理</li>
     *   <li>避免在异常情况下导致Native资源泄漏</li>
     * </ul>
     * <p>
     * 线程安全说明：
     * 钩子系统的分发器可能在多个线程中调用此方法。
     * 如果子类的实现需要访问共享状态，必须采取适当的同步措施：
     * <ul>
     *   <li>使用synchronized关键字或锁保护共享数据</li>
     *   <li>使用线程安全的数据结构（如ConcurrentHashMap）</li>
     *   <li>使用不可变对象避免同步需求</li>
     * </ul>
     *
     * @param eventType 事件类型，指示当前回调的具体事件
     *                  该参数由钩子系统传入，保证在supportedEvents()返回的集合中。
     *                  子类应该验证eventType值，只处理期望的事件类型。
     *                  建议使用switch-case语句处理多种事件类型。
     * @param data 事件数据，类型为泛型参数T
     *             该参数包含了事件相关的数据，具体类型由子类继承时指定的泛型参数决定。
     *             例如：AbstractGenericHook<PlayerData>的子类，data参数类型就是PlayerData。
     *             由于泛型的类型安全保证，子类可以直接使用data而无需类型转换。
     *             子类应该对data进行空值检查，确保健壮性。
     */
    public abstract void onEvent(HookEventType eventType, T data);

}
