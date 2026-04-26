package com.luoke.app.context;

import lombok.Getter;

/**
 * 统计信息上下文管理器（单例模式）
 * <p>
 * 职责：
 * <ul>
 *   <li>记录和统计应用运行时的各项性能指标</li>
 *   <li>记录关键操作的执行时间（地图检测、匹配、方向计算等）</li>
 *   <li>计算并显示实时帧率（FPS）</li>
 * </ul>
 * <p>
 * 核心功能：
 * <ul>
 *   <li>性能记录：记录各操作的耗时（毫秒）</li>
 *   <li>帧率统计：计算实时处理频率（每秒处理帧数）</li>
 *   <li>实时监控：提供最新的性能数据用于UI显示或日志输出</li>
 * </ul>
 * <p>
 * 统计指标：
 * <ul>
 *   <li>lastMapDetectMs: 地图检测操作的耗时</li>
 *   <li>lastMatchMs: 图像匹配操作的耗时</li>
 *   <li>lastDirectionMs: 方向计算操作的耗时</li>
 *   <li>frequency: 当前帧率（每秒处理帧数）</li>
 * </ul>
 * <p>
 * 设计特点：
 * <ul>
 *   <li>使用Lombok的@Getter自动生成getter方法</li>
 *   <li>类使用final修饰防止继承</li>
 *   <li>所有字段都是基本类型，内存占用最小化</li>
 * </ul>
 * <p>
 * 注意事项：
 * <ul
 *   <li>不是线程安全的，需要调用方确保同步</li>
 *   <li>帧率统计基于时间窗口，不是精确的每秒统计</li>
 *   <li>性能数据实时更新，适用于监控场景</li>
 * </ul>
 */
@Getter
public final class StatsContext {

    // 单例实例：确保全局只有一个统计管理器
    private static final StatsContext INSTANCE = new StatsContext();

    // ====================== 【性能耗时统计】 ======================
    // 上次地图检测操作的耗时（毫秒）
    // 记录地图识别、定位等操作的执行时间
    // 用于性能分析和优化
    private long lastMapDetectMs;

    // 上次图像匹配操作的耗时（毫秒）
    // 记录模板匹配、特征匹配等操作的执行时间
    // 通常是最耗时的操作之一
    private long lastMatchMs;

    // 上次方向计算操作的耗时（毫秒）
    // 记录玩家移动方向、朝向计算等操作的执行时间
    // 用于导航和路径规划的性能监控
    private long lastDirectionMs;

    // ====================== 【帧率统计】 ======================
    // 当前帧率（每秒处理的帧数）
    // 每秒更新一次，反映实时处理能力
    // 初始值为0，首次onFrameProcessed调用后开始计算
    private int frequency;

    // 当前时间窗口内的帧计数器
    // 在每秒时间窗口内累加帧数
    // 达到1秒时重置为0
    private int frameCounter;

    // 上次更新帧率的时间戳（毫秒）
    // 用于判断是否经过1秒时间窗口
    // 初始化为当前时间，确保首次计算准确
    private long lastSecondTime = System.currentTimeMillis();

    /**
     * 获取统计信息上下文管理器的单例实例
     *
     * @return 全局唯一的StatsContext实例
     */
    public static StatsContext getInstance() {
        return INSTANCE;
    }

    /**
     * 记录地图检测操作的耗时
     * <p>
     * 调用时机：在地图执行检测操作后立即调用
     * <p>
     * 功能说明：存储地图检测操作的执行时间，用于性能监控
     * <p>
     * 使用场景：
     * <ul>
     *   <li>性能分析：了解地图检测的耗时分布</li>
     *   <li>性能优化：识别性能瓶颈</li>
     *   <li>监控告警：耗时过长时发出警告</li>
     * </ul>
     * <p>
     * 性能考虑：直接赋值操作，无额外开销
     *
     * @param ms 地图检测操作的耗时（毫秒）
     */
    public void recordMapDetect(long ms) {
        // 直接存储耗时值：简单赋值，性能开销最小
        // 不做参数校验，假设调用方传入有效值
        this.lastMapDetectMs = ms;
    }

    /**
     * 记录图像匹配操作的耗时
     * <p>
     * 调用时机：在图像模板匹配操作后立即调用
     * <p>
     * 功能说明：存储图像匹配操作的执行时间，用于性能监控
     * <p>
     * 使用场景：
     * <ul>
     *   <li>性能分析：了解图像匹配的耗时分布</li>
     *   <li>性能优化：识别性能瓶颈（通常是最耗时的操作）</li>
     *   <li>监控告警：耗时过长时考虑降级或优化策略</li>
     * </ul>
     * <p>
     * 性能考虑：直接赋值操作，无额外开销
     *
     * @param ms 图像匹配操作的耗时（毫秒）
     */
    public void recordMatch(long ms) {
        // 直接存储耗时值：简单赋值，性能开销最小
        // 不做参数校验，假设调用方传入有效值
        this.lastMatchMs = ms;
    }

    /**
     * 记录方向计算操作的耗时
     * <p>
     * 调用时机：在方向计算操作后立即调用
     * <p>
     * 功能说明：存储方向计算操作的执行时间，用于性能监控
     * <p>
     * 使用场景：
     * <ul>
     *   <li>性能分析：了解方向计算的耗时分布</li>
     *   <li>性能优化：识别性能瓶颈</li>
     *   <li>导航精度：方向计算耗时影响实时导航响应速度</li>
     * </ul>
     * <p>
     * 性能考虑：直接赋值操作，无额外开销
     *
     * @param ms 方向计算操作的耗时（毫秒）
     */
    public void recordDirection(long ms) {
        // 直接存储耗时值：简单赋值，性能开销最小
        // 不做参数校验，假设调用方传入有效值
        this.lastDirectionMs = ms;
    }

    /**
     * 记录一帧处理完成（核心帧率统计方法）
     * <p>
     * 调用时机：每处理完一帧后立即调用
     * <p>
     * 功能说明：
     * <ul>
     *   <li>累加帧计数器</li>
     *   <li>每秒更新一次帧率统计</li>
     *   <li>重置计数器以开始下一秒统计</li>
     * </ul>
     * <p>
     * 工作原理：
     * <ol>
     *   <li>frameCounter++：统计当前秒的帧数</li>
     *   <li>检查是否经过1秒：当前时间 - 上次更新时间 >= 1000ms</li>
     *   <li>如果经过1秒：更新frequency为计数器值，重置计数器，更新时间戳</li>
     * </ol>
     * <p>
     * 性能特点：
     * <ul>
     *   <li>使用System.currentTimeMillis()获取时间（性能优于Date）</li>
     *   <li>简单的条件判断和赋值，开销极小</li>
     *   <li>适合高频调用（每帧调用一次）</li>
     * </ul>
     * <p>
     * 注意事项：
     * <ul>
     *   <li>不是线程安全的，需要调用方确保同步</li>
     *   <li>帧率统计基于1秒时间窗口，不是精确的实时帧率</li>
     *   <li>初始化时frequency为0，需要等待1秒后才有有效值</li>
     * </ul>
     */
    public void onFrameProcessed() {
        // 帧计数器累加：统计当前时间窗口内的帧数
        frameCounter++;

        // 获取当前时间戳：用于判断是否需要更新帧率统计
        long now = System.currentTimeMillis();

        // 检查是否经过1秒时间窗口
        // 使用 >= 确保即使时间略有偏差也能正确更新
        if (now - lastSecondTime >= 1000) {
            // 更新帧率：将当前窗口的帧计数保存为帧率
            frequency = frameCounter;

            // 重置帧计数器：开始下一个1秒时间窗口的统计
            frameCounter = 0;

            // 更新时间戳：记录当前窗口的开始时间
            lastSecondTime = now;
        }
    }
}
