package io.github.kedaya0209.roco.app.capture;

import io.github.kedaya0209.roco.app.platform.JobObjectManager;
import io.github.kedaya0209.roco.app.process.NativeProcess;
import io.github.kedaya0209.roco.app.process.NativeProcessFactory;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import net.jcip.annotations.NotThreadSafe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * capture.exe 子进程生命周期管理器 — 单一职责：启动、停止、销毁截图子进程。
 * <p>不持有任何 SocketSession 引用，与会话管理完全解耦。</p>
 */
@NotThreadSafe
@Slf4j
public class CaptureProcessManager {

    @Getter
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
     * 停止进程 — JobObject 保证子进程随父进程退出，直接强杀无需等待。
     */
    public void stopProcess() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    private void startReaderThread() {
        log.info("capture.exe 日志读取线程已启动 pid={}", process.pid());
        Thread.ofPlatform()
                .daemon(true)
                .name("capture-stdout")
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.info("[capture.exe] {}", line);
                        }
                        log.info("capture.exe 日志流已结束");
                    } catch (IOException e) {
                        log.warn("capture.exe 日志读取异常: {}", e.getMessage());
                    }
                });
    }
}
