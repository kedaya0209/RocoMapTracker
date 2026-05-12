package com.luoke.app.macher;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.ResourceConfigContext;
import com.luoke.app.socket.SocketHandler;
import com.luoke.app.socket.SocketServer;
import com.luoke.app.socket.SocketSession;
import com.luoke.app.utils.FileUtil;
import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SIFT 匹配客户端 — 管理 sift_match.exe 子进程，通过 Socket 通信.
 *
 * <p>协议 (msgType 200-229):
 * <pre>
 *   HANDSHAKE:
 *   220 C++→Java: REQUEST_CONFIG  {}                     — 请求算法参数
 *   221 Java→C++: CONFIG_DATA     {binary}              — SIFT/FLANN/RANSAC/MATCH 参数 + 路径
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
 *   210 Java→C++: SHUTDOWN        {}
 * </pre>
 *
 * <p>无感热切换: restart() 先启动新进程，旧进程继续服务，
 * 新进程握手完成后原子交换，零停机时间。
 */
@Slf4j
public class SiftMatchHandler implements SocketHandler {

    public static final String SIFT = "SIFT";
    public static final String SIFT_PCA = "SIFT-PCA";
    public static final String SIFT_PCA_ULTRA = "SIFT-PCA-ULTRA";
    public static final String SIFT_ULTRA = "SIFT-ULTRA";
    // Message types
    private static final int MSG_REQUEST_MAP = 200;
    private static final int MSG_MAP_DATA = 201;
    private static final int MSG_INIT_COMPLETE = 202;
    private static final int MSG_INIT_FAILED = 203;
    private static final int MSG_READY = 204;
    private static final int MSG_FRAME_DATA = 205;
    private static final int MSG_MATCH_RESULT = 206;
    private static final int MSG_SHUTDOWN = 210;
    private static final int MSG_REQUEST_CONFIG = 220;
    private static final int MSG_CONFIG_DATA = 221;
    private static final Set<Integer> TYPES = Set.of(
            MSG_REQUEST_MAP, MSG_REQUEST_CONFIG,
            MSG_INIT_COMPLETE, MSG_INIT_FAILED,
            MSG_READY, MSG_MATCH_RESULT);

    // ---- 当前服务中的进程 (active) ----
    private Process activeProcess;
    private volatile SocketSession activeSession;
    private volatile boolean activeInitialized;

    // ---- 正在初始化的新进程 (pending)，用于无感热切换 ----
    private Process pendingProcess;
    private volatile SocketSession pendingSession;
    private volatile boolean pendingInitialized;
    private volatile boolean switching;

    // 匹配结果同步: 每帧一个请求-响应周期
    private final AtomicReference<MatchResult> pendingResult = new AtomicReference<>();
    private volatile StateCallback stateCallback;
    private volatile int currentVariant = -1;

    /**
     * 根据变体序号返回缓存后缀 (兼容 DescriptorTransform.Variant)
     */
    private static String cacheSuffixForVariant(int variant) {
        return switch (variant) {
            case 0 -> ".v2.feat";        // STANDARD
            case 1 -> ".pca64.feat";     // PCA
            case 2 -> ".sift.ultra.feat"; // ULTRA
            default -> ".pca64.ultra.feat"; // PCA_ULTRA (3)
        };
    }

    /**
     * 返回 Sidebar 下拉菜单中显示的变体列表
     */
    public static java.util.Set<String> getVariants() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>(4);
        set.add(SIFT);
        set.add(SIFT_PCA);
        set.add(SIFT_ULTRA);
        set.add(SIFT_PCA_ULTRA);
        return set;
    }

    /**
     * 将配置名称映射为 C++ 侧变体序号 (0=STANDARD, 1=PCA, 2=ULTRA, 3=PCA_ULTRA)
     */
    public static int variantOrdinal(String name) {
        return switch (name) {
            case SIFT -> 0;
            case SIFT_PCA -> 1;
            case SIFT_ULTRA -> 2;
            default -> 3;
        };
    }

    // ---- SocketHandler 实现 ----

    @Override
    public Set<Integer> messageTypes() {
        return TYPES;
    }

    @Override
    public void onConnect(SocketSession session) {
        if (switching && pendingSession == null) {
            // 热切换进行中，新连接绑定到 pending
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
        // 热切换中被替换的旧进程断开 — 正常，忽略
        if (session != activeSession && session != pendingSession) {
            return;
        }

        // Pending 进程断开 — 取消热切换，不影响正在服务的 active
        if (switching && session == pendingSession) {
            log.warn("Pending sift_match.exe #{} disconnected during switch: {}", session.id(), reason);
            pendingSession = null;
            pendingInitialized = false;
            if (pendingProcess != null) {
                pendingProcess.destroyForcibly();
                pendingProcess = null;
            }
            switching = false;
            return;
        }

        // Active 进程断开
        log.warn("SiftMatchHandler active session #{} disconnected: {}", session.id(), reason);
        this.activeSession = null;
        this.activeInitialized = false;

        if (switching) {
            // 旧进程意外断开但 pending 正在初始化，静默等待 pending 接管
            log.info("Active disconnected during switch, waiting for pending to take over");
        } else {
            if (stateCallback != null) {
                stateCallback.onStateChange(false, reason);
            }
        }
    }

    // ---- 握手: 参数下发 ----

    private void handleRequestConfig(SocketSession session) {
        log.info("Received REQUEST_CONFIG, sending parameters...");
        try {
            byte[] body = serializeConfigData();
            session.send(MSG_CONFIG_DATA, body);
            log.info("CONFIG_DATA sent ({} bytes)", body.length);
        } catch (Exception e) {
            log.error("Failed to serialize CONFIG_DATA", e);
            byte[] errBody = ("Config error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    /**
     * 序列化 CONFIG_DATA 二进制格式 (Big-Endian):
     * <pre>
     *   [4B]siftVariant [4B]nfeatures [4B]nOctaveLayers
     *   [8B]contrastThreshold [8B]edgeThreshold [8B]sigma
     *   [8B]matchRatioThreshold [4B]matchMinCount [4B]searchRadius
     *   [4B]flannKDTreeCount [4B]flannSearchChecks
     *   [8B]ransacReprojThreshold [4B]ransacMaxIters [8B]ransacConfidence
     *   [4B]cacheFilePathLen [NB]cacheFilePath(UTF-8)
     * </pre>
     */
    private byte[] serializeConfigData() {
        String siftMapPath = ResourceConfigContext.getSiftMap();
        String cacheSuffix = cacheSuffixForVariant(currentVariant);
        String cacheFilePath = FileUtil.getExternalFile(siftMapPath + cacheSuffix).getAbsolutePath();

        byte[] cachePathBytes = cacheFilePath.getBytes(StandardCharsets.UTF_8);

        int bodyLen = 4 + 4 + 4 + 8 + 8 + 8       // variant + SIFT
                + 8 + 4 + 4                          // MATCH
                + 4 + 4                              // FLANN
                + 8 + 4 + 8                          // RANSAC
                + 4 + cachePathBytes.length;         // cache path

        ByteBuffer buf = ByteBuffer.allocate(bodyLen).order(ByteOrder.BIG_ENDIAN);

        // SIFT variant + params
        buf.putInt(currentVariant);
        buf.putInt(AppConfig.SIFT_N_FEATURES);
        buf.putInt(AppConfig.SIFT_N_OCTAVE_LAYERS);
        buf.putDouble(AppConfig.SIFT_CONTRAST_THRESHOLD);
        buf.putDouble(AppConfig.SIFT_EDGE_THRESHOLD);
        buf.putDouble(AppConfig.SIFT_SIGMA);

        // MATCH params
        buf.putDouble(AppConfig.MATCH_RATIO_THRESHOLD);
        buf.putInt(AppConfig.MATCH_MIN_COUNT);
        buf.putInt(500); // SEARCH_RADIUS

        // FLANN params
        buf.putInt(1);  // KDTreeIndexParams(1)
        buf.putInt(24); // SearchParams(24, 0, true)

        // RANSAC params
        buf.putDouble(AppConfig.RANSAC_REPROJ_THRESHOLD);
        buf.putInt(AppConfig.RANSAC_MAX_ITERS);
        buf.putDouble(AppConfig.RANSAC_CONFIDENCE);

        // Cache path
        buf.putInt(cachePathBytes.length);
        buf.put(cachePathBytes);

        return buf.array();
    }

    // ---- 握手: 地图数据 ----

    private void handleRequestMap(SocketSession session) {
        log.info("Received REQUEST_MAP, loading map...");
        try {
            String mapPath = ResourceConfigContext.getSiftMap();
            java.awt.image.BufferedImage img;
            try (java.io.InputStream is = ResourceUtils.getResourceStream(mapPath)) {
                img = javax.imageio.ImageIO.read(is);
            }
            if (img == null) {
                throw new java.io.IOException("Failed to decode map image");
            }

            int w = img.getWidth();
            int h = img.getHeight();
            byte[] grayPixels = new byte[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    grayPixels[y * w + x] = (byte) ((r * 299 + g * 587 + b * 114) / 1000);
                }
            }

            ByteBuffer buf = ByteBuffer.allocate(12 + grayPixels.length).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(w);
            buf.putInt(h);
            buf.putInt(grayPixels.length);
            buf.put(grayPixels);
            session.send(MSG_MAP_DATA, buf.array());
            log.info("Map data sent: {}x{} ({} gray pixels)", w, h, grayPixels.length);
        } catch (Exception e) {
            log.error("Failed to load map data", e);
            byte[] errBody = ("Map load error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    // ---- Active 进程初始化完成 ----

    private void handleInitComplete(byte[] body, SocketSession session) {
        this.activeSession = session;
        int featureCount = body != null && body.length >= 4
                ? ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt() : 0;
        log.info("SIFT ready, {} features", featureCount);
        activeInitialized = true;
        if (stateCallback != null) {
            stateCallback.onStateChange(true, "SIFT ready (" + featureCount + " features)");
        }
    }

    private void handleInitFailed(byte[] body) {
        String msg = body != null && body.length > 4
                ? new String(body, 4, body.length - 4, StandardCharsets.UTF_8) : "unknown error";
        log.error("SIFT init failed: {}", msg);
        if (stateCallback != null) {
            stateCallback.onStateChange(false, msg);
        }
    }

    // ---- Pending 进程热切换完成 ----

    /**
     * Pending 进程 INIT_COMPLETE — 原子交换 active ↔ pending，停止旧进程。
     */
    private void handlePendingInitComplete(byte[] body) {
        int featureCount = body != null && body.length >= 4
                ? ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).getInt() : 0;
        log.info("Pending SIFT ready ({} features), swapping...", featureCount);

        // 保存旧进程引用
        Process oldProcess = this.activeProcess;
        SocketSession oldSession = this.activeSession;

        // 原子交换
        this.activeProcess = this.pendingProcess;
        this.activeSession = this.pendingSession;
        this.activeInitialized = true;

        this.pendingProcess = null;
        this.pendingSession = null;
        this.pendingInitialized = false;
        this.switching = false;

        log.info("Seamless switch complete, new variant={}, {} features", currentVariant, featureCount);

        // 通知上层
        if (stateCallback != null) {
            stateCallback.onStateChange(true, "SIFT ready (" + featureCount + " features)");
        }

        // 优雅关闭旧进程 (异步，不阻塞)
        stopProcess(oldSession, oldProcess);
    }

    /**
     * Pending 进程 INIT_FAILED — 清理 pending，保留 active 继续服务。
     */
    private void handlePendingInitFailed(byte[] body) {
        String msg = body != null && body.length > 4
                ? new String(body, 4, body.length - 4, StandardCharsets.UTF_8) : "unknown error";
        log.error("Pending SIFT init failed: {}, keeping current active", msg);

        // 清理 pending
        Process p = this.pendingProcess;
        this.pendingProcess = null;
        this.pendingSession = null;
        this.pendingInitialized = false;
        this.switching = false;

        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }

        // 通知上层切换失败 (但 active 仍在服务)
        if (stateCallback != null) {
            stateCallback.onStateChange(false, "Switch failed: " + msg);
        }
    }

    // ---- 匹配结果处理 ----

    private void handleMatchResult(byte[] body) {
        if (body == null || body.length < 17) return;
        ByteBuffer buf = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN);
        boolean success = buf.get() == 1;
        double x = buf.getDouble();
        double y = buf.getDouble();
        pendingResult.set(new MatchResult(success, x, y));
    }

    // ---- 进程管理 ----

    /**
     * 启动 sift_match.exe 子进程 (首次初始化).
     */
    public boolean start(StateCallback stateCb) {
        if (currentVariant < 0) {
            currentVariant = variantOrdinal(AppConfig.MAP_MATCHAER);
        }
        this.stateCallback = stateCb;

        int port = SocketServer.instance().getPort();
        if (port <= 0) {
            log.error("SocketServer is not running");
            return false;
        }

        String exePath = FileUtil.getExternalPath(AppConfig.SIFT_MATCH_EXE, false);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exePath,
                    Integer.toString(port)
            );
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(exePath).getParentFile());
            activeProcess = pb.start();

            startReaderThread(activeProcess, "sift-stdout");

            log.info("sift_match.exe launched, port={}", port);
            return true;
        } catch (IOException e) {
            log.error("Failed to launch sift_match.exe", e);
            return false;
        }
    }

    /**
     * 启动 pending 进程 (内部使用，用于热切换).
     */
    private boolean launchPendingProcess() {
        int port = SocketServer.instance().getPort();
        if (port <= 0) {
            log.error("SocketServer is not running");
            return false;
        }

        String exePath = FileUtil.getExternalPath(AppConfig.SIFT_MATCH_EXE, false);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exePath,
                    Integer.toString(port)
            );
            pb.redirectErrorStream(true);
            pb.directory(new java.io.File(exePath).getParentFile());
            pendingProcess = pb.start();

            startReaderThread(pendingProcess, "sift-stdout-pending");

            log.info("Pending sift_match.exe launched, port={}, variant={}", port, currentVariant);
            return true;
        } catch (IOException e) {
            log.error("Failed to launch pending sift_match.exe", e);
            return false;
        }
    }

    private void startReaderThread(Process process, String name) {
        Thread.ofVirtual()
                .name(name)
                .start(() -> {
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            log.info("[{}] {}", name, line);
                        }
                    } catch (IOException ignored) {
                    }
                    try {
                        int exit = process.waitFor();
                        log.info("{} exited with code {}", name, exit);
                    } catch (InterruptedException ignored) {
                    }
                });
    }

    /**
     * 停止指定进程: 先发 SHUTDOWN 消息，再 destroy。
     */
    private void stopProcess(SocketSession session, Process process) {
        if (session != null && !session.isClosed()) {
            session.send(MSG_SHUTDOWN, null);
        }
        if (process != null && process.isAlive()) {
            try {
                process.destroy();
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                process.destroyForcibly();
            }
        }
    }

    /**
     * 发送一帧灰度数据并阻塞等待匹配结果.
     *
     * @param grayData  灰度像素 (8UC1)
     * @param width     图像宽度
     * @param height    图像高度
     * @param hintX     预测 X (NaN = 无预测)
     * @param hintY     预测 Y (NaN = 无预测)
     * @param timeoutMs 超时毫秒
     * @return 匹配结果, 超时或失败时返回 FAIL
     */
    public MatchResult sendFrameAndWait(byte[] grayData, int width, int height,
                                        double hintX, double hintY,
                                        long timeoutMs) throws InterruptedException {
        // 始终使用 active session — 热切换期间旧进程继续服务
        SocketSession s = activeSession;
        if (s == null || !activeInitialized) {
            return MatchResult.FAIL;
        }

        ByteBuffer buf = ByteBuffer.allocate(28 + grayData.length).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(width);
        buf.putInt(height);
        buf.putDouble(Double.isNaN(hintX) ? -1 : hintX);
        buf.putDouble(Double.isNaN(hintY) ? -1 : hintY);
        buf.putInt(grayData.length);
        buf.put(grayData);

        pendingResult.set(null);

        if (!s.send(MSG_FRAME_DATA, buf.array())) {
            return MatchResult.FAIL;
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            MatchResult result = pendingResult.getAndSet(null);
            if (result != null) {
                return result;
            }
            Thread.onSpinWait();
        }

        log.warn("Match result timeout after {}ms", timeoutMs);
        return MatchResult.FAIL;
    }

    /**
     * 运行时无感热切换 SIFT 变体 — 新进程在后台初始化，旧进程继续服务，
     * 新进程就绪后原子交换，零停机时间。
     *
     * <p>如果上一次切换尚未完成，会取消上一次 pending 并用最新变体重启。
     */
    public void restart(int newVariant) {
        if (newVariant == currentVariant && !switching) {
            return;
        }

        log.info("Seamless restart: variant {} -> {} (switching={})",
                currentVariant, newVariant, switching);

        // 如果上一次切换还在进行中，取消旧的 pending
        if (switching) {
            cancelPending();
        }

        // 更新变体 (新进程在 handleRequestConfig 中读取)
        this.currentVariant = newVariant;
        this.switching = true;

        // 启动新进程 (旧进程继续服务 sendFrameAndWait)
        if (!launchPendingProcess()) {
            log.error("Failed to launch pending process, keeping current active");
            this.switching = false;
            if (stateCallback != null) {
                stateCallback.onStateChange(false, "Failed to launch new process");
            }
        }
    }

    /**
     * 取消正在进行的 pending 切换 (不影响 active)。
     */
    private void cancelPending() {
        log.info("Cancelling previous pending switch");
        Process p = pendingProcess;
        SocketSession s = pendingSession;
        pendingProcess = null;
        pendingSession = null;
        pendingInitialized = false;

        if (s != null && !s.isClosed()) {
            s.send(MSG_SHUTDOWN, null);
        }
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
        }
    }

    public void stop() {
        // 先取消 pending
        switching = false;
        cancelPending();

        // 再停止 active
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
