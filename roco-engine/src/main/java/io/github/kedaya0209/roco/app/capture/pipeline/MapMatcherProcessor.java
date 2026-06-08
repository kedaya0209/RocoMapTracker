package io.github.kedaya0209.roco.app.capture.pipeline;

import io.github.kedaya0209.roco.app.capture.frame.CaptureFrameBuffer;
import io.github.kedaya0209.roco.app.capture.frame.ROIData;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.config.SiftConfig;
import io.github.kedaya0209.roco.app.context.MapContext;
import io.github.kedaya0209.roco.app.context.StatsContext;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusStateMachine;
import io.github.kedaya0209.roco.app.map.model.CompositeMapMetadata;
import io.github.kedaya0209.roco.app.match.PlayerStateTracker;
import io.github.kedaya0209.roco.app.match.SiftMatchHandler;
import io.github.kedaya0209.roco.app.match.SiftMatchProtocol;
import lombok.extern.slf4j.Slf4j;
import net.jcip.annotations.NotThreadSafe;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 地图匹配处理器 — 通过独立 C++ 进程 (sift_match.exe) 执行 SIFT 匹配。
 *
 * <p>架构变更:
 * <pre>
 *   旧: JavaCPP (SIFT/FLANN/HoughCircles 内存泄漏) → Java 进程内
 *   新: 独立 C++ sift_match.exe → Socket → 零 JavaCPP 依赖
 * </pre>
 *
 * <p>每帧流程: 接收 BGRA 全彩图 → 发送给 C++ → C++ 完成灰度转换+小地图检测+遮罩+SIFT匹配+HSV箭头检测 → 返回坐标+角度
 */
@NotThreadSafe
@Slf4j
public class MapMatcherProcessor implements RoiProcessor, AutoCloseable {

    private final int targetRoiIndex;
    private final ROIData cachedRoi = new ROIData(SiftConfig.ROI_MAP_X, SiftConfig.ROI_MAP_Y, SiftConfig.ROI_MAP_W, SiftConfig.ROI_MAP_H);
    private final SiftMatchHandler matchClient;
    private final PlayerStateTracker stateTracker;
    private final CaptureFrameBuffer frameBuffer;
    private final StatsContext stats;
    private final MatchingWatchdog watchdog;


    // 专用单线程池：SynchronousQueue 无缓冲，忙时新任务直接丢弃（只保留最新帧）
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new SynchronousQueue<>(),
            r -> { Thread t = new Thread(r, "matcher-worker"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.DiscardPolicy());
    // 频率限制
    private final long delay = 1000L / CaptureConfig.TARGET_CAPTURE_FPS;
    private long prevTime = 0L;
    /** 帧序列号，用于诊断追踪 */
    private long frameSeq = 0L;
    /** 上次匹配开关状态，用于侦测切换时发布事件 */
    private boolean wasMatchingEnabled;
    /** 上次洞穴模式状态，用于侦测变化时发布事件 */
    private boolean wasCaveMode;
    /** 上次 GC 触发时间戳，每 15 秒回收一次匹配过程的内存 */
    private long lastGcTime = System.currentTimeMillis();
    /**
     * 小地图连续匹配失败计数，超过阈值触发 LOST 状态
     */
    private int consecutiveFailures = 0;

    public MapMatcherProcessor(int targetRoiIndex, SiftMatchHandler matchClient,
                                CaptureFrameBuffer frameBuffer,
                                StatsContext stats,
                                PlayerStateTracker stateTracker) {
        this.targetRoiIndex = targetRoiIndex;
        this.matchClient = matchClient;
        this.frameBuffer = frameBuffer;
        this.stats = stats;
        this.stateTracker = stateTracker;
        this.wasMatchingEnabled = SiftConfig.SIFT_MATCHING_ENABLED;
        this.wasCaveMode = false;
        long watchdogTimeout = Math.max(3000L, SiftConfig.MATCH_TIMEOUT_MS * 3);
        this.watchdog = new MatchingWatchdog(watchdogTimeout);

    }

    @Override
    public ImageType requiredImageType() {
        return ImageType.BGRA; // C++ 侧需要全彩图进行 HSV 箭头方向检测
    }

    @Override
    public void onProcess(byte[] data, int width, int height) {
        frameBuffer.putFrame(targetRoiIndex, data, width, height);
        long now = System.currentTimeMillis();
        if (now - prevTime < delay) return;
        prevTime = now;
        frameSeq++;

        // 匹配开关变化时发布轮播事件（仅在合法源状态下转换）
        boolean enabled = SiftConfig.SIFT_MATCHING_ENABLED;
        if (enabled != wasMatchingEnabled) {
            wasMatchingEnabled = enabled;
            if (enabled) {
                StatusStateMachine.getInstance().cascadeTransition(StatusStateMachine.StatusKey.MATCH, StatusStateMachine.State.ACTIVE);
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("匹配已开启", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL));
            } else {
                StatusStateMachine.getInstance().cascadeTransition(StatusStateMachine.StatusKey.MATCH, StatusStateMachine.State.PAUSED);
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("匹配已暂停", NotificationType.INFO, StatusEvent.DisplayMode.CAROUSEL));
            }
            // 匹配关闭时触发 GC 回收匹配过程分配的大块堆内存
            System.gc();
        }
        // 匹配开关未变但 MATCH 状态被级联覆盖（如 SIFT 断开后重连），恢复状态
        if (enabled && StatusStateMachine.getInstance()
                .currentState(StatusStateMachine.StatusKey.MATCH) != StatusStateMachine.State.ACTIVE) {
            StatusStateMachine.getInstance().cascadeTransition(StatusStateMachine.StatusKey.MATCH, StatusStateMachine.State.ACTIVE);
        }
        if (!enabled) return;

        // 看门狗：matching 卡住超时时强制复位
        watchdog.checkTimeout(frameSeq);

        // executor 忙时 SynchronousQueue 拒绝，DiscardPolicy 静默丢弃（跳帧）
        executor.submit(() -> executeMatching(data, width, height));
    }

    private void executeMatching(byte[] data, int width, int height) {
        if (!watchdog.tryStart()) {
            return;
        }
        final long seq = frameSeq;
        if (log.isTraceEnabled()) {
            log.trace("[seq={}] executeMatching enter, size={}x{}", seq, width, height);
        }
        try {
            long tStart = System.currentTimeMillis();
            stats.onFrameProcessed();

            try {
                SiftMatchProtocol.MatchResult result = matchClient.sendFrameAndWait(
                        data, width, height,
                        SiftConfig.MATCH_TIMEOUT_MS);

                long elapsed = System.currentTimeMillis() - tStart;
                stats.recordMatch(elapsed);
                // 记录 C++ 端分段耗时（小地图检测/特征提取/FLANN匹配/箭头方向）
                stats.recordSiftTimings(result.tMinimapMs(), result.tExtractMs(), result.tFlannMs());
                stats.recordDirection(Math.round(result.tArrowMs()));

                if (result.success()) {
                    // Plan B: 用 C++ 返回的 map_id 确定子图
                    CompositeMapMetadata meta = MapContext.getInstance().getMultiMapMetadata();
                    int mapId = result.mapId();
                    CompositeMapMetadata.SubImageInfo sub = null;
                    if (meta != null && mapId >= 0 && mapId < meta.subImages().size()) {
                        sub = meta.subImages().get(mapId);
                    }

                    // Plan B: C++ 返回子图局部坐标（所有子图 offsetY=0），
                    // 瓦片和渲染都在大陆局部空间，无需加 offsetY
                    double fullX = result.x();
                    double fullY = result.y();

                    // 安全检测：map_id 失效时坐标可能在完整图空间（>8192），记警告
                    if (mapId < 0 && (result.y() > 8192 || result.x() > 8192)) {
                        log.warn("map_id={} 且坐标异常 ({}), 可能投票未生效，结果不可靠",
                                mapId, String.format("%.1f,%.1f", result.x(), result.y()));
                    }

                    // 大陆/洞穴切换时重置 EMA（渲染始终在大陆空间）
                    boolean newCave = sub != null && sub.isCave();
                    if (newCave != wasCaveMode) {
                        log.info("洞穴切换: caveMode={} (map_id={}), 重置位置平滑", newCave, mapId);
                        stateTracker.reset();
                    }

                    double angle = AngleConverter.toJavaFX(result.angle());
                    stateTracker.onMatchSuccess(fullX, fullY,
                        Double.isNaN(angle) ? null : angle);

                    // 用 map_id + 元数据判断大陆/洞穴，更新洞穴状态
                    // 渲染始终使用大陆坐标空间（offsetY=0），不切换活跃子图
                    boolean inCave = false;
                    String caveName = null;
                    int caveIdx = -1;

                    if (sub != null && sub.isCave()) {
                        inCave = true;
                        caveIdx = sub.index();
                        caveName = sub.name();
                    }
                    MapContext.getInstance().updateCaveMode(inCave, caveIdx, caveName);

                    // 洞穴模式变化时发布轮播事件
                    if (inCave != wasCaveMode) {
                        wasCaveMode = inCave;
                        AppEvents.publish(StatusEvent.class, inCave
                                ? new StatusEvent("洞穴: " + (caveName != null ? caveName : "?"), NotificationType.INFO, StatusEvent.DisplayMode.CAROUSEL)
                                : new StatusEvent("大陆", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL));
                    }

                    // 匹配成功：重置连续失败计数，更新小地图跟踪状态
                    boolean wasLost = StatusStateMachine.getInstance()
                            .currentState(StatusStateMachine.StatusKey.MINIMAP) == StatusStateMachine.State.LOST;
                    consecutiveFailures = 0;
                    StatusStateMachine.getInstance().cascadeTransition(StatusStateMachine.StatusKey.MINIMAP, StatusStateMachine.State.TRACKING);
                    if (wasLost) {
                        AppEvents.publish(StatusEvent.class,
                                new StatusEvent("检测到小地图，正在跟踪", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL));
                    }

                    if (log.isTraceEnabled()) {
                        log.trace("[seq={}] match OK: ({},{}), angle={}, sub={}, elapsed={}ms",
                                seq, String.format("%.1f", result.x()),
                                String.format("%.1f", result.y()),
                                String.format("%.1f", result.angle()),
                                caveName, elapsed);
                    }
                } else {
                    stateTracker.onMatchFailure("C++ match failed");
                    consecutiveFailures++;
                    if (consecutiveFailures >= 5) {
                        consecutiveFailures = 0;
                        StatusStateMachine.getInstance().cascadeTransition(StatusStateMachine.StatusKey.MINIMAP, StatusStateMachine.State.LOST);
                        AppEvents.publish(StatusEvent.class,
                                new StatusEvent("未检测到小地图", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));
                    }
                    // 匹配失败时不改变洞穴状态（保留上一帧的有效值）
                    if (log.isTraceEnabled()) {
                        log.trace("[seq={}] match FAILED, elapsed={}ms", seq, elapsed);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stateTracker.onMatchFailure("interrupted");
                log.warn("[seq={}] matching interrupted", seq);
            } catch (Exception e) {
                // 匹配管道中含多种未检查异常，保留通用捕获
                log.error("[seq={}] 匹配异常", seq, e);
                stateTracker.onMatchFailure("exception: " + e.getMessage());
            }

        } finally {
            watchdog.finish();
            // 定期触发 GC：匹配活跃时每 15 秒回收一次临时分配的 byte[]，
            // 避免 Serial GC 惰性收缩导致堆内存持续膨胀
            long now = System.currentTimeMillis();
            if (now - lastGcTime > 15_000L) {
                lastGcTime = now;
                System.gc();
            }
        }
    }

    @Override
    public int targetRoiIndex() {
        return targetRoiIndex;
    }

    @Override
    public ROIData getRoi() {
        return cachedRoi;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        System.gc();
        log.info("MapMatcherProcessor 已关闭");
    }
}
