package com.luoke.app.macher;

import com.luoke.app.config.AppConfig;
import com.luoke.app.process.JobObjectManager;
import com.luoke.app.process.NativeProcess;
import com.luoke.app.socket.SocketHandler;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.socket.SocketSession;
import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.luoke.app.macher.SiftMatchProtocol.*;

/**
 * SIFT 匹配客户端 — 管理 sift_match.exe 子进程，通过 Socket 通信.
 *
 * <p>协议 (msgType 200-209):
 * <pre>
 *   HANDSHAKE:
 *   208 C++→Java: REQUEST_CONFIG  {}                     — 请求算法参数
 *   209 Java→C++: CONFIG_DATA     {binary}              — SIFT/FLANN/RANSAC/MATCH 参数 + 路径
 *   200 C++→Java: REQUEST_MAP     {}                     — 缓存未命中，请求地图数据
 *   201 Java→C++: MAP_DATA        {w(int32),h(int32),pixelsLen(int32),gray8}
 *   202 C++→Java: INIT_COMPLETE   {featureCount(int32)}
 *   203 C++→Java: INIT_FAILED     {errcode(int32),msg(ascii)}
 *
 *   MATCHING LOOP:
 *   204 C++→Java: READY           {}
 *   205 Java→C++: FRAME_DATA      {w,h,hintX,hintY,pixelsLen,gray8}
 *   206 C++→Java: MATCH_RESULT    {success(1/0),x(f64),y(f64)}
 *
 *   SHUTDOWN:
 *   207 Java→C++: SHUTDOWN        {}
 * </pre>
 *
 * <p>无感热切换: restart() 先启动新进程，旧进程继续服务，
 * 新进程握手完成后原子交换，零停机时间。
 */
@Slf4j
public class SiftMatchHandler implements SocketHandler {

    private static final Set<Integer> TYPES = Set.of(
            MSG_REQUEST_MAP, MSG_REQUEST_CONFIG,
            MSG_INIT_COMPLETE, MSG_INIT_FAILED,
            MSG_READY, MSG_MATCH_RESULT);
    // 匹配结果同步: 每帧一个请求-响应周期, wait/notify 替代忙等
    private final Object resultLock = new Object();
    // ---- 当前服务中的进程 (active) ----
    private NativeProcess activeProcess;
    private volatile SocketSession activeSession;
    private volatile boolean activeInitialized;
    private volatile SiftVariant activeVariant;
    // ---- 正在初始化的新进程 (pending)，用于无感热切换 ----
    private NativeProcess pendingProcess;
    private volatile SocketSession pendingSession;
    private volatile boolean pendingInitialized;
    private volatile boolean switching;
    private MatchResult pendingResult;
    private volatile StateCallback stateCallback;

    // ---- SocketHandler 实现 ----
    // 崩溃重启限速，防止子进程反复崩溃时无限快速重启
    private volatile long lastRestartTime = 0;

    @Override
    public Set<Integer> messageTypes() {
        return TYPES;
    }

    @Override
    public String clientType() {
        return "sift";
    }

    @Override
    public void onConnect(SocketSession session) {
        if (switching && pendingSession == null) {
            this.pendingSession = session;
            log.info("SiftMatchHandler bound pending session #{}", session.id());
        } else if (activeSession == null || activeSession.isClosed()) {
            this.activeSession = session;
            log.info("SiftMatchHandler bound active session #{}", session.id());
        }
    }

    @Override
    public void onMessage(int type, byte[] body, SocketSession session) {
        boolean fromPending = switching && session == pendingSession;

        switch (type) {
            case MSG_REQUEST_CONFIG -> handleRequestConfig(session);
            case MSG_REQUEST_MAP -> handleRequestMap(session);
            case MSG_INIT_COMPLETE -> {
                if (fromPending) handlePendingInitComplete(body);
                else handleInitComplete(body, session);
            }
            case MSG_INIT_FAILED -> {
                if (fromPending) handlePendingInitFailed(body);
                else handleInitFailed(body);
            }
            case MSG_READY -> { /* backpressure ack, no action needed */ }
            case MSG_MATCH_RESULT -> {
                if (!fromPending) handleMatchResult(body);
            }
        }
    }

    @Override
    public void onDisconnect(SocketSession session, String reason) {
        if (session != activeSession && session != pendingSession) return;

        // Pending 进程断开 — 取消热切换，不影响 active
        if (switching && session == pendingSession) {
            log.warn("Pending sift_match.exe #{} disconnected during switch: {}", session.id(), reason);
            cancelPendingCleanup();
            return;
        }

        // Active 进程断开
        log.warn("SiftMatchHandler active session #{} disconnected: {}", session.id(), reason);
        this.activeSession = null;
        this.activeInitialized = false;

        // 唤醒等待匹配结果的线程，防止死等 500ms 超时
        synchronized (resultLock) {
            pendingResult = MatchResult.FAIL;
            resultLock.notify();
        }

        if (switching) {
            log.info("Active disconnected during switch, waiting for pending to take over");
        } else {
            // 自动重启 C++ 子进程，使匹配自动恢复
            restartAfterCrash();
            if (stateCallback != null) {
                stateCallback.onStateChange(false, reason);
            }
        }
    }

    private void restartAfterCrash() {
        long now = System.currentTimeMillis();
        if (now - lastRestartTime < AppConfig.SIFT_RESTART_MIN_INTERVAL) {
            log.warn("Skipping sift_match.exe restart due to rate limit ({}ms < {}ms)",
                    now - lastRestartTime, AppConfig.SIFT_RESTART_MIN_INTERVAL);
            return;
        }
        lastRestartTime = now;

        Thread.ofVirtual().name("sift-restart").start(() -> {
            try {
                Thread.sleep(AppConfig.SIFT_RESTART_DELAY); // 等待旧进程完全退出

                int port = SocketServer.instance().getPort();
                if (port <= 0) {
                    log.error("SocketServer not running, cannot restart sift_match.exe");
                    return;
                }

                // 清理旧进程句柄
                NativeProcess oldProc = activeProcess;
                if (oldProc != null && oldProc.isAlive()) {
                    oldProc.destroyForcibly();
                }
                activeProcess = null;

                String exePath = FileUtil.getExternalPath(AppConfig.SIFT_MATCH_EXE, false);
                String cmdLine = "\"" + exePath + "\" " + port;
                NativeProcess newProc = NativeProcess.create(cmdLine, JobObjectManager.getJobHandle(), true);
                if (newProc == null) {
                    log.error("Failed to restart sift_match.exe after crash");
                    return;
                }

                activeProcess = newProc;
                startReaderThread(newProc, "sift-stdout");
                log.info("sift_match.exe restarted (pid={}) after crash", newProc.pid());
            } catch (Exception e) {
                log.error("Error restarting sift_match.exe", e);
            }
        });
    }

    // ---- 握手: 参数下发 ----

    private void handleRequestConfig(SocketSession session) {
        log.info("Received REQUEST_CONFIG, sending parameters...");
        try {
            SiftVariant variant = activeVariant != null ? activeVariant : SiftVariant.PCA_ULTRA;
            byte[] body = encodeConfig(variant.variantOrdinal(), variant.cacheSuffix());
            session.send(MSG_CONFIG_DATA, body);
            log.info("CONFIG_DATA sent ({} bytes)", body.length);
        } catch (Exception e) {
            log.error("Failed to serialize CONFIG_DATA", e);
            byte[] errBody = ("Config error: " + e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    // ---- 握手: 地图数据 ----

    private void handleRequestMap(SocketSession session) {
        log.info("Received REQUEST_MAP, loading map...");
        try {
            MapImageData mapData = loadMapGray();
            byte[] body = encodeMapData(mapData.grayPixels(), mapData.width(), mapData.height());
            session.send(MSG_MAP_DATA, body);
            log.info("Map data sent: {}x{} ({} gray pixels)", mapData.width(), mapData.height(), mapData.grayPixels().length);
        } catch (Exception e) {
            log.error("Failed to load map data", e);
            byte[] errBody = ("Map load error: " + e.getMessage()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    // ---- Active 进程初始化完成 ----

    private void handleInitComplete(byte[] body, SocketSession session) {
        this.activeSession = session;
        int featureCount = decodeInitComplete(body);
        log.info("SIFT ready, {} features", featureCount);
        activeInitialized = true;
        if (stateCallback != null) {
            stateCallback.onStateChange(true, "SIFT ready (" + featureCount + " features)");
        }
    }

    private void handleInitFailed(byte[] body) {
        String msg = decodeInitFailed(body);
        log.error("SIFT init failed: {}", msg);
        if (stateCallback != null) {
            stateCallback.onStateChange(false, msg);
        }
    }

    // ---- Pending 进程热切换完成 ----

    private void handlePendingInitComplete(byte[] body) {
        int featureCount = decodeInitComplete(body);
        log.info("Pending SIFT ready ({} features), swapping...", featureCount);

        NativeProcess oldProcess = this.activeProcess;
        SocketSession oldSession = this.activeSession;

        this.activeProcess = this.pendingProcess;
        this.activeSession = this.pendingSession;
        this.activeInitialized = true;

        this.pendingProcess = null;
        this.pendingSession = null;
        this.pendingInitialized = false;
        this.switching = false;

        log.info("Seamless switch complete, variant={}, {} features", activeVariant, featureCount);

        if (stateCallback != null) {
            stateCallback.onStateChange(true, "SIFT ready (" + featureCount + " features)");
        }

        stopProcess(oldSession, oldProcess);
    }

    private void handlePendingInitFailed(byte[] body) {
        String msg = decodeInitFailed(body);
        log.error("Pending SIFT init failed: {}, keeping current active", msg);
        cancelPendingCleanup();
        if (stateCallback != null) {
            stateCallback.onStateChange(false, "Switch failed: " + msg);
        }
    }

    // ---- 匹配结果处理 ----

    private void handleMatchResult(byte[] body) {
        MatchResult result = decodeMatchResult(body);
        synchronized (resultLock) {
            pendingResult = result;
            resultLock.notify();
        }
    }

    // ---- 进程管理 ----

    public boolean start(StateCallback stateCb) {
        if (activeVariant == null) {
            activeVariant = SiftVariant.fromDisplayName(AppConfig.MAP_MATCHAER);
        }
        this.stateCallback = stateCb;

        int port = SocketServer.instance().getPort();
        if (port <= 0) {
            log.error("SocketServer is not running");
            return false;
        }

        String exePath = FileUtil.getExternalPath(AppConfig.SIFT_MATCH_EXE, false);
        String cmdLine = "\"" + exePath + "\" " + port;
        activeProcess = NativeProcess.create(cmdLine, JobObjectManager.getJobHandle(), true);
        if (activeProcess == null) {
            log.error("Failed to launch sift_match.exe via NativeProcess");
            return false;
        }

        startReaderThread(activeProcess, "sift-stdout");
        log.info("sift_match.exe launched (pid={}), port={}", activeProcess.pid(), port);
        return true;
    }

    private boolean launchPendingProcess() {
        int port = SocketServer.instance().getPort();
        if (port <= 0) {
            log.error("SocketServer is not running");
            return false;
        }

        String exePath = FileUtil.getExternalPath(AppConfig.SIFT_MATCH_EXE, false);
        String cmdLine = "\"" + exePath + "\" " + port;
        pendingProcess = NativeProcess.create(cmdLine, JobObjectManager.getJobHandle(), true);
        if (pendingProcess == null) {
            log.error("Failed to launch pending sift_match.exe via NativeProcess");
            return false;
        }

        startReaderThread(pendingProcess, "sift-stdout-pending");
        log.info("Pending sift_match.exe launched (pid={}), port={}, variant={}",
                pendingProcess.pid(), port, activeVariant);
        return true;
    }

    private void startReaderThread(NativeProcess process, String name) {
        Thread.ofVirtual()
                .name(name)
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.info("[{}] {}", name, line);
                        }
                    } catch (Exception ignored) {
                    }
                    log.info("{} exited with code {}", name, process.exitCode());
                });
    }

    private void stopProcess(SocketSession session, NativeProcess process) {
        if (session != null && !session.isClosed()) {
            session.send(MSG_SHUTDOWN, null);
        }
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(AppConfig.SIFT_PROCESS_STOP_TIMEOUT, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        }
    }

    // ---- 帧匹配 ----

    public MatchResult sendFrameAndWait(byte[] grayData, int width, int height,
                                        double hintX, double hintY,
                                        long timeoutMs) throws InterruptedException {
        SocketSession s = activeSession;
        if (s == null || !activeInitialized) {
            return MatchResult.FAIL;
        }

        byte[] frameData = encodeFrameData(grayData, width, height, hintX, hintY);
        if (!s.send(MSG_FRAME_DATA, frameData)) {
            return MatchResult.FAIL;
        }

        synchronized (resultLock) {
            pendingResult = null;
            long deadline = System.currentTimeMillis() + timeoutMs;
            long remaining;
            while (pendingResult == null && (remaining = deadline - System.currentTimeMillis()) > 0) {
                resultLock.wait(remaining);
            }
            if (pendingResult != null) {
                return pendingResult;
            }
        }

        log.warn("Match result timeout after {}ms", timeoutMs);
        return MatchResult.FAIL;
    }

    // ---- 热切换 ----

    public void restart(int newVariantOrdinal) {
        SiftVariant newVariant = SiftVariant.fromOrdinal(newVariantOrdinal);
        if (newVariant == activeVariant && !switching) return;

        log.info("Seamless restart: variant {} -> {} (switching={})",
                activeVariant, newVariant, switching);

        if (switching) cancelPendingCleanup();

        this.activeVariant = newVariant;
        this.switching = true;

        if (!launchPendingProcess()) {
            log.error("Failed to launch pending process, keeping current active");
            this.switching = false;
            if (stateCallback != null) {
                stateCallback.onStateChange(false, "Failed to launch new process");
            }
        }
    }

    // ---- 取消 & 清理 ----

    private void cancelPendingCleanup() {
        log.info("Cancelling previous pending switch");
        NativeProcess p = pendingProcess;
        SocketSession s = pendingSession;
        pendingProcess = null;
        pendingSession = null;
        pendingInitialized = false;
        switching = false;

        if (s != null && !s.isClosed()) {
            s.send(MSG_SHUTDOWN, null);
        }
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    public void stop() {
        switching = false;
        cancelPendingCleanup();
        stopProcess(activeSession, activeProcess);
        activeSession = null;
        activeInitialized = false;
        activeProcess = null;
        log.info("SiftMatchHandler stopped");
    }

    public boolean isReady() {
        return activeInitialized && activeSession != null && !activeSession.isClosed()
                && activeProcess != null && activeProcess.isAlive();
    }

    @FunctionalInterface
    public interface StateCallback {
        void onStateChange(boolean ready, String detail);
    }

    public record MatchResult(boolean success, double x, double y) {
        public static final MatchResult FAIL = new MatchResult(false, 0, 0);
    }
}
