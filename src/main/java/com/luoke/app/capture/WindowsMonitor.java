package com.luoke.app.capture;

import com.luoke.app.capture.jna.Frame;
import com.luoke.app.capture.jna.WindowFinder;
import com.luoke.app.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Windows窗口监控器
 * <p>
 * 该类负责持续监控指定名称的Windows窗口，并在窗口存在时自动启动屏幕捕获。
 * 核心功能包括：
 * <ul>
 *   <li>窗口自动发现与重连：当目标窗口关闭时，自动等待并重新发现窗口</li>
 *   <li>帧率控制：通过时间戳机制限制回调频率，避免过多帧处理</li>
 *   <li>线程安全监控：使用synchronized保证监控状态的一致性</li>
 *   <li>异常恢复：捕获异常后自动恢复，保证持续运行</li>
 * </ul>
 *
 * <h3>资源管理策略</h3>
 * <ul>
 *   <li>Native资源通过WgcCapture管理，确保及时释放</li>
 *   <li>监控线程通过volatile标志控制，避免线程死锁</li>
 *   <li>finally块保证异常情况下资源也能正确释放</li>
 * </ul>
 *
 * <h3>性能优化</h3>
 * <ul>
 *   <li>帧率控制避免过度处理</li>
 *   <li>窗口检查间隔（500ms）平衡响应速度与CPU占用</li>
 *   <li>重试间隔（30秒）避免无效轮询</li>
 * </ul>
 *
 * @author RocoMapTracker Team
 * @since 1.0
 */
@Slf4j
public class WindowsMonitor {
    /**
     * 帧间延迟时间（毫秒）
     * 根据目标帧率计算，例如30FPS对应约33ms
     */
    private static final long FRAME_DELAY = 1000 / AppConfig.TARGET_CAPTURE_FPS;

    /**
     * 重试间隔时间（毫秒）
     * 当窗口未找到或捕获失败时，等待30秒后重试
     */
    private static final long RETRY_INTERVAL = 30 * 1000;

    /**
     * 监控状态标志
     * 使用volatile保证多线程可见性，避免缓存不一致问题
     */
    private volatile boolean isMonitoring = false;

    /**
     * 目标窗口关键字
     * 用于通过WindowFinder查找目标窗口
     */
    private final String windowKeyword;

    /**
     * 当前运行的捕获实例
     * 需要确保及时释放Native资源，避免内存泄漏
     */
    private WgcCapture runningCapture;

    /**
     * 构造Windows窗口监控器
     *
     * @param windowKeyword 目标窗口的关键字，用于查找目标窗口
     *                      例如：窗口标题中包含的关键字
     */
    public WindowsMonitor(String windowKeyword) {
        this.windowKeyword = windowKeyword;
    }

    /**
     * 启动窗口监控
     * <p>
     * 该方法执行以下循环逻辑：
     * <ol>
     *   <li>查找目标窗口，如果未找到则等待重试</li>
     *   <li>创建WgcCapture实例并开始捕获循环</li>
     *   <li>在定期检查窗口是否仍然存在</li>
     *   <li>如果窗口关闭或发生异常，释放资源并重新开始</li>
     * </ol>
     *
     * <h3>线程模型</h3>
     * <ul>
     *   <li>该方法在调用线程中执行（通常是独立的工作线程）</li>
     *   <li>WgcCapture的回调在Native线程中执行</li>
     *   <li>使用synchronized保证startMonitor和stopMonitor的互斥访问</li>
     * </ul>
     *
     * <h3>资源生命周期</h3>
     * <ul>
     *   <li>WgcCapture实例在窗口发现时创建</li>
     *   <li>在窗口关闭或异常时通过finally块确保释放</li>
     *   <li>线程安全地更新runningCapture引用</li>
     * </ul>
     *
     * @param callBack 帧回调函数，接收到新帧时调用
     *                 回调在Native线程中执行，注意线程安全
     */
    public synchronized void startMonitor(Consumer<Frame> callBack) {
        // 防止重复启动监控
        if (isMonitoring) return;
        isMonitoring = true;

        // 主监控循环：持续尝试查找并监控窗口
        while (isMonitoring) {
            try {
                // 1. 查找目标窗口
                long hwnd = WindowFinder.findWindowByKeyword(windowKeyword);
                if (hwnd == 0) {
                    // 窗口未找到，等待后重试
                    log.warn("等待窗口 [{}]...", windowKeyword);
                    Thread.sleep(RETRY_INTERVAL);
                    continue;
                }

                // 2. 创建捕获实例并开始捕获
                // 注意：此时会分配Native资源
                runningCapture = new WgcCapture(hwnd);
                log.info("已连接窗口: {}", hwnd);

                // 用于帧率控制的最后帧时间戳
                // 使用数组以便在lambda中修改
                final long[] lastFrameTime = {0L};

                // 3. 启动捕获循环，传入帧回调
                // 回调中实现了帧率控制逻辑
                runningCapture.startLoop(frame -> {
                    long now = System.currentTimeMillis();
                    // 检查是否达到最小帧间隔，控制回调频率
                    if (now - lastFrameTime[0] >= FRAME_DELAY) {
                        callBack.accept(frame);
                        lastFrameTime[0] = now;
                    }
                }, AppConfig.SHOW_MONITOR_BORDER);

                // 4. 窗口存在性检查循环
                // 定期检查窗口是否仍然存在，避免在窗口关闭后继续运行
                while (isMonitoring) {
                    long check = WindowFinder.findWindowByKeyword(windowKeyword);
                    if (check == 0) break; // 窗口已关闭，退出内层循环
                    Thread.sleep(500); // 每500ms检查一次窗口状态
                }

            } catch (Exception e) {
                log.error("采集异常", e);
            } finally {
                // 5. 确保Native资源被释放
                // 无论正常退出还是异常退出，都要清理资源
                if (runningCapture != null) {
                    runningCapture.close();
                    runningCapture = null;
                }
            }

            // 6. 窗口关闭后等待重试
            // 避免立即重试造成的CPU占用
            try {
                Thread.sleep(RETRY_INTERVAL);
            } catch (Exception ignored) {
                // 忽略中断异常
            }
        }
    }

    /**
     * 停止窗口监控
     * <p>
     * 该方法执行以下操作：
     * <ol>
     *   <li>设置isMonitoring标志为false，触发监控循环退出</li>
     *   <li>如果存在正在运行的捕获实例，调用其close()方法释放Native资源</li>
     * </ol>
     *
     * <h3>线程安全</h3>
     * <ul>
     *   <li>使用synchronized保证与startMonitor的互斥访问</li>
     *   <li>volatile的isMonitoring标志保证多线程可见性</li>
     * </ul>
     *
     * <h3>资源释放</h3>
     * <ul>
     *   <li>立即释放Native资源，避免内存泄漏</li>
     *   <li>即使监控循环还在运行，也会被标志位打断</li>
     * </ul>
     */
    public synchronized void stopMonitor() {
        isMonitoring = false;
        if (runningCapture != null) {
            runningCapture.close();
        }
    }
}