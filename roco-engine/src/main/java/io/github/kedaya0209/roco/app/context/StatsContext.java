package io.github.kedaya0209.roco.app.context;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.config.StatsConfig;
import lombok.Getter;

@ThreadSafe
@Getter
public final class StatsContext {

    // 单例实例：确保全局只有一个统计管理器
    private static final StatsContext INSTANCE = new StatsContext();

    // ====================== 【性能耗时统计】 ======================
    private volatile long lastMapDetectMs;
    private volatile long lastMatchMs;
    private volatile long lastCircleMaskMs;
    private volatile long lastDirectionMs;

    // ====================== 【SIFT C++ 分段耗时】 ======================
    private volatile long lastSiftMinimapMs;
    private volatile long lastSiftExtractMs;
    private volatile long lastSiftFlannMs;

    // ====================== 【帧率统计】 ======================
    private volatile int frequency;

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

    public void recordSiftTimings(float minimapMs, float extractMs, float flannMs) {
        this.lastSiftMinimapMs = (long) minimapMs;
        this.lastSiftExtractMs = (long) extractMs;
        this.lastSiftFlannMs = (long) flannMs;
    }

    public void reset() {
        lastMapDetectMs = 0;
        lastMatchMs = 0;
        lastDirectionMs = 0;
        lastSiftMinimapMs = 0;
        lastSiftExtractMs = 0;
        lastSiftFlannMs = 0;
    }

    public void onFrameProcessed() {
        // 帧计数器累加：统计当前时间窗口内的帧数
        frameCounter++;

        // 获取当前时间戳：用于判断是否需要更新帧率统计
        long now = System.currentTimeMillis();

        // 检查是否经过1秒时间窗口
        // 使用 >= 确保即使时间略有偏差也能正确更新
        if (now - lastSecondTime >= StatsConfig.STATS_FPS_WINDOW_MS) {
            // 更新帧率：将当前窗口的帧计数保存为帧率
            frequency = frameCounter;

            // 重置帧计数器：开始下一个1秒时间窗口的统计
            frameCounter = 0;

            // 更新时间戳：记录当前窗口的开始时间
            lastSecondTime = now;
        }
    }
}
