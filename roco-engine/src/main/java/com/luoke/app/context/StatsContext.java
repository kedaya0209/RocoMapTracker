package com.luoke.app.context;

import com.luoke.app.config.AppConfig;
import lombok.Getter;

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

    // 上次圆形遮罩应用的耗时（毫秒）
    private long lastCircleMaskMs;

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


    public void recordMapDetect(long ms) {
        // 直接存储耗时值：简单赋值，性能开销最小
        // 不做参数校验，假设调用方传入有效值
        this.lastMapDetectMs = ms;
    }

    public void recordMatch(long ms) {
        // 直接存储耗时值：简单赋值，性能开销最小
        // 不做参数校验，假设调用方传入有效值
        this.lastMatchMs = ms;
    }

    public void recordCircleMask(long ms) {
        this.lastCircleMaskMs = ms;
    }

    public void recordDirection(long ms) {
        // 直接存储耗时值：简单赋值，性能开销最小
        // 不做参数校验，假设调用方传入有效值
        this.lastDirectionMs = ms;
    }

    public void reset() {
        lastMapDetectMs = 0;
        lastMatchMs = 0;
        lastDirectionMs = 0;
    }

    public void onFrameProcessed() {
        // 帧计数器累加：统计当前时间窗口内的帧数
        frameCounter++;

        // 获取当前时间戳：用于判断是否需要更新帧率统计
        long now = System.currentTimeMillis();

        // 检查是否经过1秒时间窗口
        // 使用 >= 确保即使时间略有偏差也能正确更新
        if (now - lastSecondTime >= AppConfig.STATS_FPS_WINDOW_MS) {
            // 更新帧率：将当前窗口的帧计数保存为帧率
            frequency = frameCounter;

            // 重置帧计数器：开始下一个1秒时间窗口的统计
            frameCounter = 0;

            // 更新时间戳：记录当前窗口的开始时间
            lastSecondTime = now;
        }
    }
}
