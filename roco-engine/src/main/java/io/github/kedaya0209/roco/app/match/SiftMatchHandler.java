package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.config.SocketConfig;
import io.github.kedaya0209.roco.app.context.ResourceConfigContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata.SubImageInfo;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import io.github.kedaya0209.roco.app.process.NativeProcess;
import io.github.kedaya0209.roco.app.process.NativeProcessFactory;
import io.github.kedaya0209.roco.app.process.ProcessRestartHelper;
import io.github.kedaya0209.roco.app.socket.HandlerSubscriber;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.socket.SocketSession;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static io.github.kedaya0209.roco.app.match.SiftMatchProtocol.*;

/**
 * SIFT 匹配协调器 — 编排 {@link SiftProcessManager}（子进程生命周期）和
 * {@link SiftSessionManager}（Socket 会话管理），对外保持与旧版本完全兼容的 API。
 *
 * <p>协调器自身职责：
 * <ul>
 *   <li>消息路由（handlers 路由表）</li>
 *   <li>热切换编排（同时协调进程切换与会话切换）</li>
 *   <li>崩溃恢复编排</li>
 * </ul>
 *
 * <p>已抽取的独立职责：
 * <ul>
 *   <li>{@link FrameMatchSynchronizer} — 帧匹配 wait/notify 同步</li>
 *   <li>{@link MapImageLoader} — 地图资源加载 + 灰度转换</li>
 *   <li>{@link LaunchParams} — 变体状态管理</li>
 * </ul>
 *
 * <p>通过 {@link HandlerSubscriber} 注册到 {@link SocketServer}，订阅 sift_match.exe 提供的服务。
 */
@NotThreadSafe
@Slf4j
public class SiftMatchHandler {

    /** Java 端需要订阅的消息类型（sift_match.exe 发出的） */
    private static final Set<Integer> SUBSCRIBE_TYPES = Set.of(
            MSG_REQUEST_MAP, MSG_REQUEST_CONFIG,
            MSG_INIT_COMPLETE, MSG_INIT_FAILED,
            MSG_READY, MSG_MATCH_RESULT);

    // ==================== 子管理器 ====================

    private final SiftProcessManager processManager;
    private final SiftSessionManager sessionManager;
    private final ProcessRestartHelper restartHelper;

    // ==================== 协调器自身字段 ====================

    private final SocketServer server;
    private final LaunchParams launchParams;
    private final FrameMatchSynchronizer synchronizer = new FrameMatchSynchronizer();

    // 外部回调
    private volatile StateCallback stateCallback;

    // ==================== 消息路由 ====================

    @FunctionalInterface
    private interface MessageHandler {
        void handle(byte[] body, SocketSession session);
    }

    private final Map<Integer, MessageHandler> handlers = Map.of(
            MSG_REQUEST_CONFIG, (b, s) -> handleRequestConfig(s),
            MSG_REQUEST_MAP, (b, s) -> handleRequestMap(s),
            MSG_INIT_COMPLETE, this::handleInitComplete,
            MSG_INIT_FAILED, this::handleInitFailed,
            MSG_READY, this::handleReady,
            MSG_MATCH_RESULT, this::handleMatchResult
    );

    public SiftMatchHandler(SocketServer server, NativeProcessFactory processFactory) {
        this.server = server;
        this.processManager = new SiftProcessManager(processFactory);
        this.sessionManager = new SiftSessionManager();
        this.restartHelper = new ProcessRestartHelper("sift_match",
                SocketConfig.SIFT_RESTART_DELAY);
        this.launchParams = new LaunchParams(null);
    }

    // ==================== HandlerSubscriber 适配 ====================

    /**
     * 创建 HandlerSubscriber 并注册到 SocketServer，订阅 sift_match.exe 提供的服务
     */
    public void registerToServer(SocketServer server) {
        HandlerSubscriber subscriber = new HandlerSubscriber(
                this::onMessage,
                this::onConnect,
                this::onDisconnect,
                "sift-handler"
        );
        server.registerInternal(SUBSCRIBE_TYPES, subscriber);
    }

    // ==================== 消息处理 ====================

    private void onConnect(SocketSession session) {
        sessionManager.onConnect(session);
    }

    private void onMessage(int type, byte[] body, SocketSession session) {
        MessageHandler handler = handlers.get(type);
        if (handler != null) {
            handler.handle(body, session);
        } else {
            log.warn("未知 SIFT 消息类型: {}", type);
        }
    }

    private void onDisconnect(SocketSession session, String reason) {
        if (session != sessionManager.getActiveSession()
                && session != sessionManager.getPendingSession()) {
            return;
        }

        // ── Pending 进程断开 — 取消热切换，不影响 active ──
        if (sessionManager.isFromPending(session)) {
            log.warn("待命 sift_match.exe #{} 在切换期间断开: {}", session.id(), reason);
            sessionManager.cancelPendingCleanup();
            processManager.clearPending();
            return;
        }

        // ── Active 进程断开 ──
        handleActiveDisconnect(session, reason);
    }

    private void handleActiveDisconnect(SocketSession session, String reason) {
        log.warn("SiftMatchHandler 活跃会话 #{} 断开: {}", session.id(), reason);
        sessionManager.handleActiveDisconnect();
        AppEvents.publish(StatusEvent.class,
                new StatusEvent("sift引擎断开", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));

        synchronizer.failAndWake();

        if (sessionManager.isSwitching()) {
            log.info("活跃会话在切换期间断开，等待待命会话接管");
            return;
        }

        // 异步重启 C++ 子进程（使用公共重启辅助组件）
        restartHelper.restartAsync(server, processManager::restartAfterCrash);

        if (stateCallback != null) {
            stateCallback.onStateChange(false, reason);
        }
    }

    // ==================== 握手协议 ====================

    private void handleRequestConfig(SocketSession session) {
        log.info("收到 REQUEST_CONFIG，发送参数...");
        try {
            SiftVariant variant = launchParams.get() != null ? launchParams.get() : SiftVariant.PCA_ULTRA;
            String cacheSuffix = variant.cacheSuffix();

            // Plan B unified index: load sub-image heights + per-sub-image SIFT params from metadata
            int[] subImageHeights = null;
            SubImageSiftOverride[] subImageOverrides = null;
            SubImageSiftOverride matchingSift = null;
            if (ResourceConfigContext.isMultiMapActive()) {
                try (InputStream is = ResourceUtils.getResourceStream(
                        ResourceConfigContext.getMultiMapMetadata())) {
                    CompositeMapMetadata metadata = CompositeMapMetadata.load(is);
                    var subs = metadata.subImages();
                    subImageHeights = subs.stream()
                            .mapToInt(SubImageInfo::height)
                            .toArray();

                    // 收集有 SIFT 参数覆盖的子图
                    subImageOverrides = subs.stream()
                            .filter(sub -> sub.siftOverride() != null && sub.siftOverride().hasAny())
                            .map(sub -> {
                                var o = sub.siftOverride();
                                return new SubImageSiftOverride(
                                        sub.index(), o.contrastThreshold(),
                                        o.edgeThreshold(), o.nfeatures(),
                                        o.nOctaveLayers(), o.sigma());
                            })
                            .toArray(SubImageSiftOverride[]::new);

                    // 匹配侧 SIFT 参数（元数据覆盖）
                    CompositeMapMetadata.SiftParams ms = metadata.matchingSift();
                    if (ms != null && ms.hasAny()) {
                        matchingSift = new SubImageSiftOverride(
                                -1, ms.contrastThreshold(), ms.edgeThreshold(),
                                ms.nfeatures(), ms.nOctaveLayers(), ms.sigma());
                    }

                    cacheSuffix = cacheSuffix + ".multi";
                    log.info("MultiMap 模式: {} 子图, {} 个参数覆盖, 匹配参数={}, 缓存后缀={}",
                            subImageHeights.length, subImageOverrides.length,
                            matchingSift != null ? "自定义" : "SiftConfig默认",
                            cacheSuffix);
                } catch (Exception e) {
                    log.warn("加载 MultiMap 元数据失败，回退到普通模式", e);
                }
            }

            // 亮度分流：使用独立的 cave 缓存路径
            String caveCacheSuffix = variant.cacheSuffix() + ".cave";
            byte[] body = encodeConfig(variant.variantOrdinal(), cacheSuffix, caveCacheSuffix, 0,
                    subImageHeights, subImageOverrides, matchingSift);
            session.send(MSG_CONFIG_DATA, body);
            log.info("CONFIG_DATA 已发送 ({} 字节, algoKind={}, cache={})",
                    body.length, 0, cacheSuffix);
        } catch (RuntimeException e) {
            log.error("序列化 CONFIG_DATA 失败", e);
            byte[] errBody = ("Config error: " + e.getMessage())
                    .getBytes(StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    private void handleRequestMap(SocketSession session) {
        if (ResourceConfigContext.isMultiMapActive()) {
            handleRequestMultiMap(session);
            return;
        }
        log.info("收到 REQUEST_MAP，加载地图...");
        try {
            MapImageLoader.ImageInfo info = MapImageLoader.loadImage();
            int subImageCount = MapImageLoader.getSubImageCount(info.height());
            int bodyLength = 16 + subImageCount * 4 + info.width() * info.height();
            session.sendStreaming(MSG_MAP_DATA, bodyLength, out -> {
                MapImageLoader.writeStreamingMulti(info, subImageCount, out);
            });
            log.info("地图数据已发送: {}x{} ({} 子图, {} 灰度像素)",
                    info.width(), info.height(), subImageCount, info.width() * info.height());
        } catch (Exception e) {
            log.error("加载地图数据失败", e);
            byte[] errBody = ("Map load error: " + e.getMessage())
                    .getBytes(StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    private void handleRequestMultiMap(SocketSession session) {
        log.info("收到 REQUEST_MAP (MultiMap)，加载子图...");
        try {
            CompositeMapMetadata metadata;
            try (InputStream is = ResourceUtils.getResourceStream(
                    ResourceConfigContext.getMultiMapMetadata())) {
                metadata = CompositeMapMetadata.load(is);
            }
            int w = metadata.width();
            int totalH = metadata.totalHeight();
            var subs = metadata.subImages();
            int subCount = subs.size();
            int bodyLength = 16 + subCount * 4 + w * totalH;

            session.sendStreaming(MSG_MAP_DATA, bodyLength, out -> {
                MapImageLoader.writeStreamingMultiFromMetadata(metadata, out);
            });
            log.info("MultiMap 数据已发送: {}x{} ({} 子图)", w, totalH, subCount);
        } catch (Exception e) {
            log.error("加载 MultiMap 地图数据失败", e);
            byte[] errBody = ("MultiMap map load error: " + e.getMessage())
                    .getBytes(StandardCharsets.UTF_8);
            session.send(MSG_INIT_FAILED, errBody);
        }
    }

    private void handleInitComplete(byte[] body, SocketSession session) {
        if (sessionManager.isFromPending(session)) {
            handlePendingInitComplete(body);
            return;
        }
        int featureCount = sessionManager.handleInitComplete(body);
        AppEvents.publish(StatusEvent.class,
                new StatusEvent("sift引擎加载完成", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL));
        if (stateCallback != null) {
            stateCallback.onStateChange(true, "SIFT ready (" + featureCount + " features)");
        }
    }

    private void handleInitFailed(byte[] body, SocketSession session) {
        if (sessionManager.isFromPending(session)) {
            handlePendingInitFailed(body);
            return;
        }
        String msg = sessionManager.handleInitFailed(body);
        AppEvents.publish(StatusEvent.class,
                new StatusEvent("sift引擎加载失败", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));
        if (stateCallback != null) {
            stateCallback.onStateChange(false, msg);
        }
    }

    private void handlePendingInitComplete(byte[] body) {
        SiftSessionManager.SwapResult swap = sessionManager.handlePendingInitComplete(body);

        // 关闭旧会话
        if (swap.oldActiveSession() != null && !swap.oldActiveSession().isClosed()) {
            swap.oldActiveSession().send(MSG_SHUTDOWN, null);
        }
        // 切换并停止旧进程
        NativeProcess oldProcess = processManager.promotePending();
        processManager.stopProcess(oldProcess);

        log.info("无缝切换完成，变体={}, {} 特征点",
                launchParams.get(), swap.featureCount());
        if (stateCallback != null) {
            stateCallback.onStateChange(true, "SIFT ready (" + swap.featureCount() + " features)");
        }
    }

    private void handlePendingInitFailed(byte[] body) {
        String msg = sessionManager.handlePendingInitFailed(body);
        processManager.clearPending();
        if (stateCallback != null) {
            stateCallback.onStateChange(false, "Switch failed: " + msg);
        }
    }

    // ==================== 匹配结果 ====================

    private void handleMatchResult(byte[] body, SocketSession session) {
        if (sessionManager.isFromPending(session)) return;
        MatchResult result = decodeMatchResult(body);
        synchronizer.complete(result);
    }

    private void handleReady(byte[] body, SocketSession session) {
        sessionManager.handleReady();
    }

    // ==================== 帧匹配 ====================

    /**
     * 发送帧数据并等待匹配结果。
     *
     * @return 匹配结果（成功/失败 + 坐标），超时返回 MatchResult.FAIL
     */
    public MatchResult sendFrameAndWait(byte[] grayData, int width, int height,
                                        double hintX, double hintY,
                                        long timeoutMs) throws InterruptedException {
        SocketSession s = sessionManager.getActiveSession();
        if (s == null || !sessionManager.isReady()) {
            return MatchResult.FAIL;
        }

        byte[] frameData = encodeFrameData(grayData, width, height, hintX, hintY);
        if (!s.send(MSG_FRAME_DATA, frameData)) {
            return MatchResult.FAIL;
        }

        return synchronizer.awaitResult(timeoutMs);
    }

    // ==================== 生命周期 ====================

    /**
     * 启动 sift_match.exe 子进程。
     */
    public boolean start(StateCallback stateCb) {
        this.stateCallback = stateCb;
        if (launchParams.get() == null) {
            launchParams.set(SiftVariant.fromDisplayName(SiftConfig.MAP_MATCHAER));
        }

        NativeProcess proc = processManager.launchProcess(server, "sift-stdout");
        if (proc == null) {
            return false;
        }
        processManager.setActiveProcess(proc);

        AppEvents.publish(StatusEvent.class,
                new StatusEvent("sift引擎加载中", NotificationType.LOADING, StatusEvent.DisplayMode.CAROUSEL));
        return true;
    }

    /**
     * 无感热切换 SIFT 变体。
     */
    public void restart(int newVariantOrdinal) {
        SiftVariant newVariant = SiftVariant.fromOrdinal(newVariantOrdinal);
        if (newVariant == launchParams.get() && !sessionManager.isSwitching()) return;

        // 取消正在进行的切换
        if (sessionManager.isSwitching()) {
            SocketSession oldPending = sessionManager.cancelPendingCleanup();
            if (oldPending != null && !oldPending.isClosed()) {
                oldPending.send(MSG_SHUTDOWN, null);
            }
            processManager.clearPending();
        }

        launchParams.set(newVariant);
        sessionManager.enterSwitching();

        if (processManager.launchPendingProcess(server) == null) {
            log.error("启动待命进程失败，保留当前活跃会话");
            sessionManager.resetSwitching();
            if (stateCallback != null) {
                stateCallback.onStateChange(false, "Failed to launch new process");
            }
        }
    }

    /**
     * 强制重启子进程（算法类型切换时使用，跳过变体未变更检查）。
     */
    public void restartForce() {
        // 取消正在进行的切换
        if (sessionManager.isSwitching()) {
            SocketSession oldPending = sessionManager.cancelPendingCleanup();
            if (oldPending != null && !oldPending.isClosed()) {
                oldPending.send(MSG_SHUTDOWN, null);
            }
            processManager.clearPending();
        }

        sessionManager.enterSwitching();

        if (processManager.launchPendingProcess(server) == null) {
            log.error("启动待命进程失败，保留当前活跃会话");
            sessionManager.resetSwitching();
            if (stateCallback != null) {
                stateCallback.onStateChange(false, "Failed to launch new process");
            }
        }
    }

    /**
     * 停止所有子进程和会话。
     */
    public void stop() {
        // 取消 pending
        if (sessionManager.isSwitching()) {
            SocketSession oldPending = sessionManager.cancelPendingCleanup();
            if (oldPending != null && !oldPending.isClosed()) {
                oldPending.send(MSG_SHUTDOWN, null);
            }
            processManager.clearPending();
        }

        // 停止 active
        SocketSession activeSess = sessionManager.getActiveSession();
        if (activeSess != null && !activeSess.isClosed()) {
            activeSess.send(MSG_SHUTDOWN, null);
        }
        processManager.stopProcess(processManager.getActiveProcess());
        sessionManager.reset();

        log.info("SiftMatchHandler 已停止");
    }

    /**
     * @return 当前 sift_match.exe 子进程 PID，未启动时返回 -1
     */
    public int getActiveProcessPid() {
        NativeProcess p = processManager.getActiveProcess();
        return p != null ? p.pid() : -1;
    }

    /**
     * 检查 active 进程和会话是否就绪。
     */
    public boolean isReady() {
        return sessionManager.isReady();
    }

    // ==================== 内嵌类型（向后兼容） ====================

    @FunctionalInterface
    public interface StateCallback {
        void onStateChange(boolean ready, String detail);
    }
}
