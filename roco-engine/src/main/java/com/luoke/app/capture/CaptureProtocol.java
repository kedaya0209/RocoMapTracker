package com.luoke.app.capture;

import com.luoke.app.capture.frame.ROIData;
import net.jcip.annotations.ThreadSafe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * capture.exe 协议常量与序列化 — 纯函数工具类。
 *
 * <p>与 {@link com.luoke.app.match.SiftMatchProtocol} 对称，
 * 将协议层从 {@link CaptureHandler} 协调器中剥离。
 */
@ThreadSafe
public final class CaptureProtocol {

    private CaptureProtocol() {
    }

    // ==================== 消息类型 ====================

    public static final int MSG_REQUEST_ROI = 100;
    public static final int MSG_RETURN_ROI = 101;
    public static final int MSG_CAPTURE_READY = 102;
    public static final int MSG_FRAME_DATA = 103;
    public static final int MSG_PROCESSING_DONE = 104;
    public static final int MSG_WINDOW_CLOSED = 105;
    public static final int MSG_STOP_REQUEST = 106;
    public static final int MSG_WINDOW_STATE = 107;
    public static final int MSG_SWITCH_MODE = 108;

    // ==================== 序列化 ====================

    /**
     * 序列化 ROI 列表。
     *
     * <pre>msgType=2 body: [2] count + per-ROI [2]x,y,w,h (BE int16)</pre>
     */
    public static byte[] serializeRois(ROIData[] rois) {
        int count = (rois != null) ? rois.length : 0;
        ByteBuffer buf = ByteBuffer.allocate(2 + count * 8).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) count);
        if (rois != null) {
            for (ROIData r : rois) {
                buf.putShort((short) r.x);
                buf.putShort((short) r.y);
                buf.putShort((short) r.w);
                buf.putShort((short) r.h);
            }
        }
        return buf.array();
    }
}
