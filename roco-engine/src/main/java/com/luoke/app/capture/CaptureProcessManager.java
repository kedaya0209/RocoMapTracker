package com.luoke.app.capture;

import com.luoke.app.process.JobObjectManager;
import com.luoke.app.process.NativeProcess;
import com.luoke.app.process.NativeProcessFactory;
import com.luoke.app.socket.SocketServer;
import lombok.extern.slf4j.Slf4j;

import net.jcip.annotations.NotThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * capture.exe 子进程生命周期管理器 — 单一职责：启动、停止、销毁截图子进程。
 * <p>不持有任何 SocketSession 引用，与会话管理完全解耦。</p>
 */
@NotThreadSafe
@Slf4j
public class CaptureProcessManager {

    private NativeProcess process;
    private final NativeProcessFactory processFactory;

    public CaptureProcessManager(NativeProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    /**
     * 启动 capture.exe 子进程
     *
     * @return 成功返回 true
     */
    public boolean launchProcess(SocketServer server, String exePath, long hwnd, int maxFps) {
        int port = server.getPort();
        if (port <= 0) {
            log.error("SocketServer 未运行");
            return false;
        }

        // 清理旧进程
        if (process != null && process.isAlive()) {
            log.warn("旧 capture.exe 进程仍存活，强制终止");
            process.destroyForcibly();
        }

        String cmdLine = "\"" + exePath + "\" " + hwnd + " " + port + " " + maxFps;
        process = processFactory.create(cmdLine, JobObjectManager.getJobHandle(), true);
        if (process == null) {
            log.error("通过 NativeProcess 启动 capture.exe 失败");
            return false;
        }

        startReaderThread();
        log.info("capture.exe 已启动 (pid={}), hwnd=0x{}", process.pid(), Long.toHexString(hwnd));
        return true;
    }

    /**
     * 重启 capture.exe 子进程（崩溃恢复用）
     *
     * @return 成功返回 true
     */
    public boolean restartProcess(SocketServer server, String exePath, long hwnd, int maxFps) {
        // 清理旧进程
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
        return launchProcess(server, exePath, hwnd, maxFps);
    }

    /**
     * 优雅停止进程，超时后强制销毁
     */
    public void stopProcess() {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                log.warn("capture.exe pid={} 未在 3 秒内停止，强制终止", process.pid());
                process.destroyForcibly();
            }
        }
    }

    /**
     * 强制销毁进程
     */
    public void destroyProcess() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public NativeProcess getProcess() {
        return process;
    }

    private void startReaderThread() {
        Thread.ofPlatform()
                .daemon(true)
                .name("capture-stdout")
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.debug("[capture.exe] {}", line);
                        }
                    } catch (IOException ignored) {
                    }
                });
    }
}
