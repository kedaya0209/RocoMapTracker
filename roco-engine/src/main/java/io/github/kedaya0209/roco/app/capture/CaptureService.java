package io.github.kedaya0209.roco.app.capture;

import io.github.kedaya0209.roco.app.capture.frame.CaptureFrameBuffer;
import io.github.kedaya0209.roco.app.capture.frame.ROIData;
import io.github.kedaya0209.roco.app.capture.pipeline.RoiProcessor;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.platform.WindowFinder;
import io.github.kedaya0209.roco.app.config.PathConfig;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.CaptureStateEvent;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import io.github.kedaya0209.roco.app.hook.event.StatusEvent;
import io.github.kedaya0209.roco.app.hook.event.StatusStateMachine;
import io.github.kedaya0209.roco.app.hook.event.StatusStateMachine.State;
import io.github.kedaya0209.roco.app.hook.event.StatusStateMachine.StatusKey;
import io.github.kedaya0209.roco.app.process.NativeProcess;
import io.github.kedaya0209.roco.app.socket.SocketServer;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import net.jcip.annotations.NotThreadSafe;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 截图会话管理器 — 通过 SocketServer + CaptureHandler 获取 WGC 帧数据
 * Socket 由 SocketServer 常驻, CaptureHandler 按需启动 capture.exe
 */
@NotThreadSafe
@Data
@Slf4j
public class CaptureService implements FullFrameControl {
    private final String windowTitle;
    private final AtomicInteger continuousBlackFrames = new AtomicInteger(0);
    //退避策略 减少连续重启频率
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final int tolerance = 3;
    private final CopyOnWriteArrayList<RoiProcessor> processors = new CopyOnWriteArrayList<>();
    private final CaptureHandler handler = new CaptureHandler(SocketServer.instance(), NativeProcess::create);
    private final CaptureHandler.FrameCallback frameCallback;
    private final CaptureHandler.StateCallback stateCallback;
    private ROIData[] cachedRois;
    /**
     * 全帧模式下，全帧数据在帧数据中的索引位置 (= ROIs 数量)
     */
    private volatile int fullFrameIndex = -1;

    public CaptureService(String windowTitle) {
        this.windowTitle = windowTitle;

        // 注册 handler 到全局 SocketServer（通过 HandlerSubscriber 订阅 capture.exe 的服务）
        handler.registerToServer(SocketServer.instance());

        frameCallback = (index, data, w, h, stride) -> {
            // 懒加载灰度图: 有处理器需要时才转换
            byte[] gray = null;

            // 黑帧检测 (始终用灰度图)
            if (index == 0) {
                gray = bgraToGray(data, w, h, stride);
                if (isAllBlack(gray, CaptureConfig.CAPTURE_BLACK_SAMPLE_SIZE)) {
                    if (continuousBlackFrames.incrementAndGet() > CaptureConfig.MAX_BLACK_FRAMES * (1 << Math.min(restartCount.get(), tolerance))) {
                        log.error("持续黑帧, 强制重置采集会话...");
                        continuousBlackFrames.set(0);
                        restartCount.incrementAndGet();
                        restartCount.set(Math.min(restartCount.get(), tolerance));
                        this.stop();
                        return;
                    }
                } else {
                    continuousBlackFrames.set(0);
                    restartCount.set(0);
                }
            }

            for (RoiProcessor processor : processors) {
                try {
                    if (processor.targetRoiIndex() == -1 || processor.targetRoiIndex() == index) {
                        if (processor.requiredImageType() == RoiProcessor.ImageType.BGRA) {
                            processor.onProcess(compactBgra(data, w, h, stride), w, h);
                        } else {
                            if (gray == null) {
                                gray = bgraToGray(data, w, h, stride);
                            }
                            processor.onProcess(gray, w, h);
                        }
                    }
                } catch (Exception ignore) {
                    // 处理器回调可能抛出多种异常，保留通用捕获
                }
            }

            // 全帧模式：将全帧数据存入 CaptureFrameBuffer 供设置面板预览
            if (index == fullFrameIndex) {
                CaptureFrameBuffer.getInstance().putFullFrame(data, w, h);
            }
        };

        stateCallback = (connected, detail) -> {
            if (!connected) {
                log.warn("capture.exe 断开: {}", detail);
                AppEvents.publish(CaptureStateEvent.class,
                        new CaptureStateEvent(-1, false, windowTitle));
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("capture断开", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));
            } else {
                log.info("capture.exe 已连接: {}", detail);
                AppEvents.publish(StatusEvent.class,
                        new StatusEvent("capture加载完成", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL));
            }
        };
    }

    private static byte[] bgraToGray(byte[] bgra, int w, int h, int stride) {
        byte[] gray = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int rowStart = y * stride;
            int grayRow = y * w;
            for (int x = 0; x < w; x++) {
                int pos = rowStart + x * 4;
                int b = bgra[pos] & 0xFF;
                int g = bgra[pos + 1] & 0xFF;
                int r = bgra[pos + 2] & 0xFF;
                gray[grayRow + x] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
            }
        }
        return gray;
    }

    /**
     * 将 stride 对齐的 BGRA 缓冲区压缩为紧凑格式（w*h*4 字节），
     * 方便传递给 RoiProcessor / Socket 通信。
     * 若 stride == w*4 则直接返回原数组（无需拷贝）。
     */
    private static byte[] compactBgra(byte[] bgra, int w, int h, int stride) {
        if (stride == w * 4) return bgra;
        byte[] compact = new byte[w * h * 4];
        for (int y = 0; y < h; y++) {
            System.arraycopy(bgra, y * stride, compact, y * w * 4, w * 4);
        }
        return compact;
    }

    /**
     * 查找窗口 → 启动 capture.exe → 由 SocketServer 已注册的 CaptureHandler 接管通信
     */
    public boolean tryConnect() {
        // 兜底：看门狗重连时如果状态机卡在 READY（例如前一次 stop() 未触发断开回调），
        // 先走 DISCONNECTED 确保后续 captureRetry/captureLoading 转换合法
        if (StatusStateMachine.getInstance().currentState(StatusKey.CAPTURE) == State.READY) {
            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("capture断开", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));
        }

        long hwnd = WindowFinder.findWindowByKeyword(windowTitle);
        if (hwnd <= 0) {
            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("未找到游戏窗口，5秒后重试...", NotificationType.INFO, StatusEvent.DisplayMode.CAROUSEL));
            return false;
        }

        String exePath = FilePathUtil.getExternalPath(PathConfig.CAPTURE_EXE, true);

        // 发布捕获加载中状态
        AppEvents.publish(StatusEvent.class,
                new StatusEvent("capture加载中", NotificationType.LOADING, StatusEvent.DisplayMode.CAROUSEL));

        boolean ok = handler.start(hwnd, CaptureConfig.TARGET_CAPTURE_FPS, exePath,
                cachedRois, frameCallback, stateCallback);

        if (ok) {
            log.info("成功连接窗口 [{}], HWND: 0x{}", windowTitle, Long.toHexString(hwnd));
            AppEvents.publish(CaptureStateEvent.class,
                    new CaptureStateEvent(1, true, windowTitle));
            AppEvents.publish(StatusEvent.class,
                    new StatusEvent("capture加载完成", NotificationType.SUCCESS, StatusEvent.DisplayMode.CAROUSEL));
            return true;
        }

        // handler.start 失败（如 capture.exe 启动异常）
        AppEvents.publish(StatusEvent.class,
                new StatusEvent("capture启动失败，5秒后重试...", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));
        return false;
    }

    private boolean isAllBlack(byte[] data, int sampleSize) {
        int checkLen = Math.min(data.length, sampleSize);
        int result = 0;
        for (int i = 0; i < checkLen; i++) {
            result |= (data[i] & 0xFF);
        }
        return result == 0;
    }

    public void setRois(ROIData[] rois) {
        this.cachedRois = rois;
    }

    /**
     * 切换全帧模式。开启后 C++ capture.exe 会在 ROI 帧后附加一帧完整画面，
     * CaptureService 将其存入 CaptureFrameBuffer 供设置面板预览。
     */
    public void setFullFrameMode(boolean enabled) {
        if (enabled && cachedRois != null && cachedRois.length > 0) {
            // 全帧数据在帧消息中的索引 = ROI 数量
            fullFrameIndex = cachedRois.length;
            handler.setFullFrameRoiIndex(fullFrameIndex);
            handler.sendSwitchMode(true);
            log.info("全帧模式已启用，索引={}", fullFrameIndex);
        } else {
            fullFrameIndex = -1;
            handler.setFullFrameRoiIndex(-1);
            handler.releaseFullFrameBuffer();
            handler.sendSwitchMode(false);
            CaptureFrameBuffer.getInstance().clear();
            log.info("全帧模式已禁用");
        }
    }

    public void addProcessors(RoiProcessor... processors) {
        this.processors.addAll(List.of(processors));
    }

    public boolean isRunning() {
        return handler.isRunning();
    }

    public void stop() {
        handler.stop();
        // 不反注册 handler — handler 注册于构造函数，生命周期与 CaptureService 相同。
        // 反注册会导致后续 tryConnect() 启动的 capture.exe 无法完成 Socket 握手（onConnect 不被调用），
        // 从而 isRunning() 永远返回 false，watchdog 陷入"创建→丢弃→创建"的死循环。
        AppEvents.publish(CaptureStateEvent.class,
                new CaptureStateEvent(-1, false, windowTitle));
        AppEvents.publish(StatusEvent.class,
                new StatusEvent("capture断开", NotificationType.ERROR, StatusEvent.DisplayMode.CAROUSEL));
    }
}
