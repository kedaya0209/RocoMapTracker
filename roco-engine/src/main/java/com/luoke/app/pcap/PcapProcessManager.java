package com.luoke.app.pcap;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.PcapConfig;
import com.luoke.app.config.SocketConfig;
import com.luoke.app.process.JobObjectManager;
import com.luoke.app.process.NativeProcess;
import com.luoke.app.process.NativeProcessFactory;
import com.luoke.app.utils.FilePathUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Pcap 子进程生命周期管理器 — 管理 pcap.exe 的启动、重启、销毁。
 * <p>
 * 与 {@link com.luoke.app.macher.SiftProcessManager} 模式对称，但更精简（无热切换需求）。
 */
@NotThreadSafe
@Slf4j
public class PcapProcessManager {

    @Getter
    private NativeProcess activeProcess;
    private final NativeProcessFactory processFactory;
    private volatile long lastRestartTime = 0;
    private int restartCount = 0;

    public PcapProcessManager(NativeProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    /**
     * 启动 pcap.exe 子进程并连接到指定 SocketServer
     *
     * @param serverPort SocketServer 端口
     * @param iface      网卡名（null 表示自动检测）
     * @return true 表示启动成功
     */
    public boolean start(int serverPort, String iface) {
        if (serverPort <= 0) {
            log.error("SocketServer 端口无效: {}", serverPort);
            return false;
        }

        String exePath = FilePathUtil.getExternalPath(PcapConfig.PCAP_EXE, true);
        StringBuilder cmd = new StringBuilder("\"").append(exePath).append("\" --rmt-port ").append(serverPort);
        if (iface != null && !iface.isBlank()) {
            cmd.append(" --iface ").append(iface);
        }

        NativeProcess proc = processFactory.create(cmd.toString(), JobObjectManager.getJobHandle(), true);
        if (proc == null) {
            log.error("启动 pcap.exe 失败");
            return false;
        }

        this.activeProcess = proc;
        this.restartCount = 0;
        startReaderThread(proc);
        log.info("pcap.exe 已启动 (pid={}), 端口={}", proc.pid(), serverPort);
        return true;
    }

    /**
     * 崩溃后重启（受速率限制）。
     *
     * @param serverPort SocketServer 端口
     * @param iface      网卡名
     * @return true 表示重启成功
     */
    public boolean restartAfterCrash(int serverPort, String iface) {
        long now = System.currentTimeMillis();
        if (now - lastRestartTime < TimeUnit.SECONDS.toMillis(PcapConfig.RESTART_DELAY_SEC)) {
            log.warn("跳过 pcap.exe 重启，冷却中");
            return false;
        }
        if (restartCount >= PcapConfig.MAX_RESTART_ATTEMPTS) {
            log.error("pcap.exe 连续崩溃 {} 次，停止重启", restartCount);
            return false;
        }
        lastRestartTime = now;
        restartCount++;

        if (activeProcess != null && activeProcess.isAlive()) {
            activeProcess.destroyForcibly();
        }
        return start(serverPort, iface);
    }

    /**
     * 停止 pcap.exe
     */
    public void stop() {
        if (activeProcess != null && activeProcess.isAlive()) {
            activeProcess.destroy();
            if (!activeProcess.waitFor(SocketConfig.SIFT_PROCESS_STOP_TIMEOUT, TimeUnit.SECONDS)) {
                log.warn("pcap.exe pid={} 未在 {}s 内停止，强制终止",
                        activeProcess.pid(), SocketConfig.SIFT_PROCESS_STOP_TIMEOUT);
                activeProcess.destroyForcibly();
            }
        }
        activeProcess = null;
        restartCount = 0;
    }

    /**
     * 重置崩溃计数器（连接成功后调用）
     */
    public void resetCrashCount() {
        this.restartCount = 0;
    }

    private void startReaderThread(NativeProcess proc) {
        Thread.ofPlatform()
                .daemon(true)
                .name("pcap-stdout")
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.info("[pcap] {}", line);
                        }
                    } catch (IOException e) {
                        log.warn("[pcap] stdout reader 异常: {}", e.getMessage());
                    }
                    int code = proc.exitCode();
                    if (code == 0) {
                        log.info("[pcap] 正常退出 (code=0)");
                    } else {
                        log.warn("[pcap] 异常退出 (code={})", code);
                    }
                });
    }
}
