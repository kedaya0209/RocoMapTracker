package com.luoke.app.capture;

import net.jcip.annotations.ThreadSafe;

/**
 * 全帧模式控制接口 — 消除 SettingsStage 对 CaptureService 的直接依赖
 */
@ThreadSafe
public interface FullFrameControl {
    void setFullFrameMode(boolean enabled);
}
