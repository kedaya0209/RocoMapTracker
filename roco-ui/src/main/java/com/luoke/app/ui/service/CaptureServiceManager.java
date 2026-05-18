package com.luoke.app.ui.service;

import com.luoke.app.capture.CaptureService;
import com.luoke.app.capture.ROIData;
import com.luoke.app.capture.processor.MapMatcherProcessor;
import com.luoke.app.capture.processor.OcrProcessor;
import com.luoke.app.config.AppConfig;
import com.luoke.app.macher.SiftMatchHandler;
import com.luoke.app.ui.component.SettingsStage;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 截图服务生命周期管理 + 断线 watchdog。
 */
@Slf4j
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
        captureService = new CaptureService(AppConfig.TARGET_WINDOW_NAME);
        setupCaptureProcessors();
        SettingsStage.setCaptureService(captureService);
        startWatchdog();
    }

    private void setupCaptureProcessors() {
        if (captureService == null) return;
        MapMatcherProcessor siftProcessor = new MapMatcherProcessor(0, siftMatchClient);
        OcrProcessor ocrProcessor = new OcrProcessor(1);
        captureService.addProcessors(siftProcessor, ocrProcessor);

        List<ROIData> rois = new ArrayList<>();
        rois.add(siftProcessor.getRoi());
        rois.add(ocrProcessor.getRoi());

        captureService.setRois(ROIData.createContiguousArray(rois));
        log.info("采集处理器配置完成");
    }

    private void startWatchdog() {
        watchdogRunning = true;
        Thread.ofVirtual().start(() -> {
            while (watchdogRunning) {
                try {
                    if (!captureService.isRunning()) {
                        if (captureService.tryConnect()) {
                            log.info("采集会话已连接");
                        } else {
                            log.info("未找到游戏窗口，5秒后重试...");
                        }
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
