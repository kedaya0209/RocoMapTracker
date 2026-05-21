package com.luoke.app.capture;

import lombok.Getter;

import net.jcip.annotations.ThreadSafe;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 截帧缓冲区 — 存储各 ROI 最新一帧的原始像素数据以及全帧数据，供 UI 预览使用。
 * 线程安全：写入来自虚拟线程，读取来自 JavaFX 线程。
 */
@ThreadSafe
public class CaptureFrameBuffer {

    private static final CaptureFrameBuffer INSTANCE = new CaptureFrameBuffer();

    private final ConcurrentMap<Integer, RoiFrame> frames = new ConcurrentHashMap<>();

    /**
     * 全帧数据（设置面板预览使用，单独存储不占用 ROI 槽位）
     * -- GETTER --
     * 读取全帧数据，不存在返回 null
     */
    @Getter
    private volatile RoiFrame fullFrame;

    /**
     * 全帧内部可复用缓冲区，避免源 byte[] 晋升老年代
     */
    private byte[] fullFramePixels;

    public static CaptureFrameBuffer getInstance() {
        return INSTANCE;
    }

    /**
     * 存入 ROI 帧数据
     */
    public void putFrame(int roiIndex, byte[] pixels, int width, int height) {
        byte[] copy = new byte[pixels.length];
        System.arraycopy(pixels, 0, copy, 0, pixels.length);
        frames.put(roiIndex, new RoiFrame(copy, width, height, System.currentTimeMillis()));
    }

    /**
     * 读取指定 ROI 的最新帧，不存在返回 null
     */
    public RoiFrame getFrame(int roiIndex) {
        return frames.get(roiIndex);
    }

    /**
     * 存入全帧数据（设置面板预览）
     * 内部拷贝到可复用缓冲区，避免源 byte[]（8MB humongous 对象）晋升到老年代。
     */
    public void putFullFrame(byte[] src, int width, int height) {
        if (fullFramePixels == null || fullFramePixels.length != src.length) {
            fullFramePixels = new byte[src.length];
        }
        System.arraycopy(src, 0, fullFramePixels, 0, src.length);
        fullFrame = new RoiFrame(fullFramePixels, width, height, System.currentTimeMillis());
    }

    /**
     * 清空所有缓存帧及内部缓冲区
     */
    public void clear() {
        frames.clear();
        fullFrame = null;
        fullFramePixels = null;
    }

    /**
     * ROI 帧数据记录
     */
    public record RoiFrame(byte[] pixels, int width, int height, long timestamp) {
    }
}
