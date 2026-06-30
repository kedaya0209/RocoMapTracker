package io.github.kedaya0209.roco.app.ui.service.lifecycle;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.capture.frame.CaptureFrameBuffer;
import io.github.kedaya0209.roco.app.capture.CaptureService;
import io.github.kedaya0209.roco.app.capture.frame.ROIData;
import io.github.kedaya0209.roco.app.capture.pipeline.MapMatcherProcessor;
import io.github.kedaya0209.roco.app.config.CaptureConfig;
import io.github.kedaya0209.roco.app.context.StatsContext;
import io.github.kedaya0209.roco.app.match.SiftMatchHandler;
import io.github.kedaya0209.roco.app.match.PlayerStateTracker;
import io.github.kedaya0209.roco.app.ui.component.setting.SettingsStage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 截图服务生命周期管理 + 断线 watchdog。
 */
@Slf4j
@ThreadSafe
public class CaptureServiceManager {

    @Getter
    private CaptureService captureService;
    private SiftMatchHandler siftMatchClient;
    private volatile boolean watchdogRunning = false;

    /**
     * 初始化并启动截图服务
     */
    public void init(SiftMatchHandler siftClient) {
        this.siftMatchClient = siftClient;
        captureService = new CaptureService(CaptureConfig.TARGET_WINDOW_NAME);
        setupCaptureProcessors();
        SettingsStage.setCaptureService(captureService);
        startWatchdog();
    }

    private void setupCaptureProcessors() {
        if (captureService == null) return;
        MapMatcherProcessor siftProcessor = new MapMatcherProcessor(0, siftMatchClient,
                CaptureFrameBuffer.getInstance(), StatsContext.getInstance(),
                new PlayerStateTracker());
        captureService.addProcessors(siftProcessor);

        List<ROIData> rois = new ArrayList<>();
        rois.add(siftProcessor.getRoi());

        captureService.setRois(ROIData.createContiguousArray(rois));
        log.info("采集处理器配置完成");
    }

    private void startWatchdog() {
        watchdogRunning = true;
        Thread.ofPlatform().daemon(true).name("capture-watchdog").start(() -> {
            while (watchdogRunning) {
                try {
                    if (!captureService.isRunning()) {
                        if (captureService.tryConnect()) {
                            log.info("采集会话已连接");
                        } else {
                            log.info("未找到游戏窗口，5秒后重试...");
                        }
                    }
                    // 同步 PID 到插件进程注册中心（首次连接或重启后更新）
                    int pid = getProcessPid();
                    if (pid > 0) {
                        PluginProcessRegistry.register("capture", pid);
                    }
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("采集监控守护进程异常", e);
                }
            }
        });
    }

    /**
     * @return 当前 capture.exe 子进程 PID，未启动时返回 -1
     */
    public int getProcessPid() {
        return captureService != null ? captureService.getProcessPid() : -1;
    }

    /**
     * 切换截图目标到指定窗口。
     *
     * @param newHwnd 目标窗口 HWND
     * @return 切换是否成功
     */
    public boolean switchTarget(long newHwnd) {
        if (captureService == null) return false;
        return captureService.switchTarget(newHwnd);
    }

    /** 获取当前截图目标 HWND（共享上下文） */
    public long getTargetHwnd() {
        return captureService != null ? captureService.getTargetHwnd() : 0;
    }

    /**
     * 停止截图服务
     */
    public void stop() {
        watchdogRunning = false;
        if (captureService != null) {
            captureService.stop();
            captureService = null;
        }
    }
}
