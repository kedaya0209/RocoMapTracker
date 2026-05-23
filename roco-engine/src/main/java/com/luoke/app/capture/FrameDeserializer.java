package com.luoke.app.capture;

import net.jcip.annotations.NotThreadSafe;
import net.jcip.annotations.ThreadSafe;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * 帧数据反序列化器 — 从 Socket 消息中解析多 ROI 帧数据。
 * 从 CaptureHandler 拆分，单一职责：二进制协议 → 值对象。
 * <p>
 * 协议格式 (Big-Endian):
 * <pre>
 *   [2] roiCount
 *   每个 ROI: [1]index [2]w [2]h [2]stride [4]dataLen [dataLen]BGRA
 * </pre>
 */
@NotThreadSafe
public class FrameDeserializer {

    @ThreadSafe
    public record FrameSlot(int index, byte[] pixels, int w, int h, int stride) {
    }

    private byte[] fullFramePoolBuffer;

    /**
     * 反序列化帧数据。
     *
     * @param buf              已 flip 的 ByteBuffer（Big-Endian）
     * @param roiCount         ROI 数量
     * @param fullFrameRoiIndex 全帧 ROI 索引（-1 表示不启用池化）
     * @return 解析后的 ROI slot 列表
     */
    public List<FrameSlot> deserialize(ByteBuffer buf, int roiCount, int fullFrameRoiIndex) {
        List<FrameSlot> slots = new ArrayList<>(roiCount);
        for (int i = 0; i < roiCount; i++) {
            if (buf.remaining() < 11) break;

            int index = buf.get() & 0xFF;
            int w = buf.getShort() & 0xFFFF;
            int h = buf.getShort() & 0xFFFF;
            int stride = buf.getShort() & 0xFFFF;
            int dataLen = buf.getInt();

            if (dataLen <= 0 || buf.remaining() < dataLen) break;

            // 全帧 ROI 复用池化缓冲区，避免每帧 8MB humongous 分配 → 老年代堆积
            byte[] pixels;
            if (index == fullFrameRoiIndex) {
                if (fullFramePoolBuffer == null || fullFramePoolBuffer.length != dataLen) {
                    fullFramePoolBuffer = new byte[dataLen];
                }
                pixels = fullFramePoolBuffer;
            } else {
                pixels = new byte[dataLen];
            }
            buf.get(pixels);
            slots.add(new FrameSlot(index, pixels, w, h, stride));
        }
        return slots;
    }

    /**
     * 释放全帧池化缓冲区
     */
    public void clearPool() {
        fullFramePoolBuffer = null;
    }
}
