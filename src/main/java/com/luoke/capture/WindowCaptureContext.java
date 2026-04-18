package com.luoke.capture;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * 窗口截图工具类（基于 WGC + JNA）
 * 封装：查找窗口、创建捕获、获取帧、转图片、保存
 */
@Slf4j
public class WindowCaptureContext {

    private final long hwnd;
    private long captureHandle = 0;

    protected WindowCaptureContext(String windowKeyword) {
        hwnd = WindowFinder.findWindowByKeyword(windowKeyword);
        if (hwnd == 0) {
            throw new RuntimeException(String.format("未捕捉到窗口:%S", windowKeyword));
        }
    }

    /**
     * 启动捕获会话
     */
    protected boolean start() {
        if (captureHandle != 0) {
            return true;
        }
        long start = System.currentTimeMillis();
        captureHandle = WGCCapture.createCapture(hwnd);
        if (captureHandle == 0) {
            return false;
        }
        boolean startCapture = WGCCapture.startCapture(captureHandle);
        log.debug("监视器启动耗时:{}", System.currentTimeMillis() - start);
        return startCapture;
    }

    /**
     * 获取一帧图片（BufferedImage）
     */
    protected CaptureFrameRecord captureFrameBytes() {
        if (captureHandle == 0) {
            throw new IllegalStateException("未启动捕获会话");
        }
        long start = System.currentTimeMillis();
        int[] wh = new int[2];
        byte[] frame = WGCCapture.grabFrame(captureHandle, 1000, wh);
        long time = System.currentTimeMillis();
        log.debug("截图耗时:{}", time - start);
        if (frame == null || wh[0] <= 0 || wh[1] <= 0) {
            return null;
        }
        return CaptureFrameRecord.builder().width(wh[0]).height(wh[1]).bytes(frame).build();
    }

    /**
     * 获取一帧图片（BufferedImage）
     */
    protected BufferedImage captureFrame() {
        CaptureFrameRecord captureFrameRecord = captureFrameBytes();
        long start = System.currentTimeMillis();
        BufferedImage bufferedImage = ImageConverter.convertBgraToImage(captureFrameRecord.bytes(), captureFrameRecord.width(), captureFrameRecord.height());
        log.debug("数组转为图片耗时:{}", System.currentTimeMillis() - start);
        return bufferedImage;
    }

    /**
     * 截图并直接保存为文件
     */
    protected boolean captureAndSave(String savePath) {
        BufferedImage image = captureFrame();
        if (image == null) {
            return false;
        }
        long start = System.currentTimeMillis();
        try {
            ImageIO.write(image, "png", new File(savePath));
            return true;
        } catch (IOException e) {
            log.error("保存图片失败，e:", e);
            return false;
        } finally {
            log.debug("保存图片耗时:{}", System.currentTimeMillis() - start);
        }
    }

    /**
     * 关闭释放资源
     */
    protected void close() {
        if (captureHandle != 0) {
            WGCCapture.closeCapture(captureHandle);
            captureHandle = 0;
        }
    }

}