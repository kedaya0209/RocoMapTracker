package com.luoke.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * Socket 与子进程管理配置持久化 
 */
@NotThreadSafe
public final class SocketConfig {

    // ============================================================
    // Socket 与子进程管理参数
    // ============================================================
    /**
     * ServerSocket 待处理连接队列深度
     */
    public static int SOCKET_BACKLOG = 1;
    /**
     * Socket accept 线程 join 超时（毫秒）
     */
    public static int SOCKET_ACCEPT_JOIN_TIMEOUT = 2000;
    /**
     * 崩溃后最小重启间隔（毫秒）
     */
    public static long SIFT_RESTART_MIN_INTERVAL = 5000;
    /**
     * 重启前等待旧进程退出的延迟（毫秒）
     */
    public static long SIFT_RESTART_DELAY = 1000;
    /**
     * 子进程优雅停止等待秒数
     */
    public static int SIFT_PROCESS_STOP_TIMEOUT = 3;

    private SocketConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    public static void load(Properties prop) {
        SOCKET_BACKLOG = ConfigHelper.getInt(prop, "socket.backlog", SOCKET_BACKLOG);
        SOCKET_ACCEPT_JOIN_TIMEOUT = ConfigHelper.getInt(prop, "socket.accept.join.timeout", SOCKET_ACCEPT_JOIN_TIMEOUT);
        SIFT_RESTART_MIN_INTERVAL = ConfigHelper.getLong(prop, "sift.restart.min.interval", SIFT_RESTART_MIN_INTERVAL);
        SIFT_RESTART_DELAY = ConfigHelper.getLong(prop, "sift.restart.delay", SIFT_RESTART_DELAY);
        SIFT_PROCESS_STOP_TIMEOUT = ConfigHelper.getInt(prop, "sift.process.stop.timeout", SIFT_PROCESS_STOP_TIMEOUT);
    }

    public static void save(StringBuilder sb) {
        sb.append("# ServerSocket 待处理连接队列深度\n");
        sb.append("socket.backlog=").append(SOCKET_BACKLOG).append("\n");
        sb.append("# Socket accept 线程 join 超时（毫秒）\n");
        sb.append("socket.accept.join.timeout=").append(SOCKET_ACCEPT_JOIN_TIMEOUT).append("\n");
        sb.append("# SIFT 崩溃后最小重启间隔（毫秒）\n");
        sb.append("sift.restart.min.interval=").append(SIFT_RESTART_MIN_INTERVAL).append("\n");
        sb.append("# 重启前等待旧进程退出的延迟（毫秒）\n");
        sb.append("sift.restart.delay=").append(SIFT_RESTART_DELAY).append("\n");
        sb.append("# 子进程优雅停止等待秒数\n");
        sb.append("sift.process.stop.timeout=").append(SIFT_PROCESS_STOP_TIMEOUT).append("\n\n");
    }
}
