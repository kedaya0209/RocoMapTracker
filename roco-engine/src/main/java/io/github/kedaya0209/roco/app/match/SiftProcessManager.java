package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.config.SocketConfig;
import io.github.kedaya0209.roco.app.platform.JobObjectManager;
import io.github.kedaya0209.roco.app.process.NativeProcess;
import io.github.kedaya0209.roco.app.process.NativeProcessFactory;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 匹配子进程生命周期管理器 — 管理 match.exe 的启动、重启、销毁。
 * 实际算法由 CONFIG_DATA 中的 kind 字段指定。
 *
 * <p>不持有任何 SocketSession 引用，与会话管理完全解耦。
 * 所有方法均为同步操作，异步编排由 {@link SiftMatchHandler} 协调器负责。</p>
 */
@NotThreadSafe
@Slf4j
public class SiftProcessManager {

    @Getter @Setter
    private NativeProcess activeProcess;
    @Getter
    private NativeProcess pendingProcess;
    private final NativeProcessFactory processFactory;
    private volatile long lastRestartTime = 0;

    public SiftProcessManager(NativeProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    // ==================== 进程启动 ====================

    /**
     * 创建新的 match.exe 进程并连接到指定 SocketServer
     *
     * @param server    Socket 服务端
     * @param threadName stdout 读取线程名
     * @return 新进程，失败返回 null
     */
    public NativeProcess launchProcess(SocketServer server, String threadName) {
        int port = server.getPort();
        if (port <= 0) {
            log.error("SocketServer 未运行");
            return null;
        }

        String exePath = FilePathUtil.getExternalPath(PathConfig.MATCH_EXE, true);
        String cmdLine = "\"" + exePath + "\" " + port;
        NativeProcess proc = processFactory.create(cmdLine, JobObjectManager.getJobHandle(), true);
        if (proc == null) {
            log.error("通过 NativeProcess 启动 match.exe 失败");
            return null;
        }

        startReaderThread(proc, threadName);
        log.info("match.exe 已启动 (pid={}), 端口={}", proc.pid(), port);
        return proc;
    }

    /**
     * 启动 pending 进程用于热切换
     */
    public NativeProcess launchPendingProcess(SocketServer server) {
        this.pendingProcess = launchProcess(server, "sift-stdout-pending");
        return this.pendingProcess;
    }

    // ==================== 崩溃重启 ====================

    /**
     * 崩溃后重启 active 进程（受速率限制）。
     * 调用方应在外层虚拟线程中执行，先 sleep 等待旧进程完全退出再调用此方法。
     *
     * @return true 表示重启成功
     */
    public boolean restartAfterCrash(SocketServer server) {
        long now = System.currentTimeMillis();
        if (now - lastRestartTime < SocketConfig.SIFT_RESTART_MIN_INTERVAL) {
            log.warn("跳过 match.exe 重启，触发速率限制 ({}ms < {}ms)",
                    now - lastRestartTime, SocketConfig.SIFT_RESTART_MIN_INTERVAL);
            return false;
        }
        lastRestartTime = now;

        // 清理旧进程
        if (activeProcess != null && activeProcess.isAlive()) {
            activeProcess.destroyForcibly();
        }
        activeProcess = null;

        NativeProcess newProc = launchProcess(server, "sift-stdout");
        if (newProc == null) {
            return false;
        }
        this.activeProcess = newProc;
        return true;
    }

    // ==================== 热切换 ====================

    /**
     * 提升 pending 为 active，返回旧 active 进程（调用方负责销毁）
     */
    public NativeProcess promotePending() {
        NativeProcess oldProc = this.activeProcess;
        this.activeProcess = this.pendingProcess;
        this.pendingProcess = null;
        return oldProc;
    }

    /**
     * 清理 pending 进程（强制销毁）
     */
    public void clearPending() {
        NativeProcess p = pendingProcess;
        pendingProcess = null;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    // ==================== 进程销毁 ====================

    /**
     * 强制销毁进程
     */
    public void destroyProcess(NativeProcess process) {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    /**
     * 停止进程 — JobObject 保证子进程随父进程退出，直接强杀无需等待。
     */
    public boolean stopProcess(NativeProcess process) {
        if (process == null || !process.isAlive()) return true;
        process.destroyForcibly();
        return true;
    }

    // ==================== 内部工具 ====================

    private void startReaderThread(NativeProcess process, String name) {
        Thread.ofPlatform()
                .daemon(true)
                .name(name)
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.info("[{}] {}", name, line);
                        }
                    } catch (IOException e) {
                        log.warn("[{}] stdout reader exception: {}", name, e.getMessage(), e);
                    }
                    int code = process.exitCode();
                    if (code == 0) {
                        log.info("[{}] exited with code 0", name);
                    } else {
                        log.warn("[{}] exited with non-zero code {} (可能崩溃或被强杀)", name, code);
                    }
                });
    }
}
