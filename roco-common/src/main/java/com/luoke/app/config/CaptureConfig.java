package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * 截图与窗口配置持久化
 */
@NotThreadSafe
public final class CaptureConfig {

    // ============================================================
    // 窗口与捕获
    // ============================================================
    /**
     * 目标游戏窗口标题
     */
    public static String TARGET_WINDOW_NAME = "洛克王国：世界";
    /**
     * 应用主窗口标题
     */
    public static String APP_MAIN_TITLE = "RocoMapTracker";
    /**
     * 目标捕获帧率
     */
    public static int TARGET_CAPTURE_FPS = 30;
    /**
     * 连续黑帧最大数量（超过则断开）
     */
    public static int MAX_BLACK_FRAMES = 100;
    /**
     * 显示录制区域边框
     */
    public static boolean SHOW_MONITOR_BORDER = false;

    // --- 捕获引擎参数 ---
    /**
     * 黑帧检测采样字节数
     */
    public static int CAPTURE_BLACK_SAMPLE_SIZE = 100;
    /**
     * 帧率统计日志间隔（毫秒）
     */
    public static int CAPTURE_STATS_INTERVAL = 10000;
    /**
     * capture.exe 进程优雅停止等待秒数
     */
    public static int CAPTURE_PROCESS_SHUTDOWN_WAIT = 3;

    private CaptureConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        TARGET_WINDOW_NAME = ConfigHelper.getStr(prop, "target.window.name", TARGET_WINDOW_NAME);
        TARGET_CAPTURE_FPS = ConfigHelper.getInt(prop, "target.capture.fps", TARGET_CAPTURE_FPS);
        SHOW_MONITOR_BORDER = ConfigHelper.getBool(prop, "show.monitor.border", SHOW_MONITOR_BORDER);
        CAPTURE_BLACK_SAMPLE_SIZE = ConfigHelper.getInt(prop, "capture.black.sample.size", CAPTURE_BLACK_SAMPLE_SIZE);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 目标游戏窗口标题\n");
        sb.append("target.window.name=").append(TARGET_WINDOW_NAME).append("\n");
        sb.append("# 目标捕获帧率\n");
        sb.append("target.capture.fps=").append(TARGET_CAPTURE_FPS).append("\n");
        sb.append("# 显示录制区域边框\n");
        sb.append("show.monitor.border=").append(SHOW_MONITOR_BORDER).append("\n");
        sb.append("# 黑帧检测采样字节数\n");
        sb.append("capture.black.sample.size=").append(CAPTURE_BLACK_SAMPLE_SIZE).append("\n");
        sb.append("# 连续黑帧最大数量（超过则断开）\n");
        sb.append("capture.max.black.frames=").append(MAX_BLACK_FRAMES).append("\n");
        sb.append("# 帧率统计日志间隔（毫秒）\n");
        sb.append("capture.stats.interval=").append(CAPTURE_STATS_INTERVAL).append("\n");
        sb.append("# capture.exe 进程优雅停止等待秒数\n");
        sb.append("capture.process.shutdown.wait=").append(CAPTURE_PROCESS_SHUTDOWN_WAIT).append("\n\n");
    }
}
