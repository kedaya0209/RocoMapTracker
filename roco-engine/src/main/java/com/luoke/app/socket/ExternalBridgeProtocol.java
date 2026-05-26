package com.luoke.app.socket;

import net.jcip.annotations.ThreadSafe;

/**
 * 外部桥接协议常量 — rmt_bridge.py ↔ RocoMapTracker 通信协议。
 * <p>
 * 与 SiftMatchProtocol（Java ↔ C++ 子进程协议）分离，职责独立。
 */
@ThreadSafe
public final class ExternalBridgeProtocol {

    public static final int MSG_EXTERNAL_POSITION = 210; // 废弃
    public static final int MSG_SCENE_CHANGE = 211;
    public static final int MSG_STOP_MATCHING = 212;
    public static final int MSG_START_MATCHING = 213;
    public static final int MSG_AREA_CHANGE = 214;
    public static final int MSG_ITEM_PICKUP = 215;

    private ExternalBridgeProtocol() {
    }
}
