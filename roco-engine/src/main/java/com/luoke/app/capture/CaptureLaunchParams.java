package com.luoke.app.capture;

import net.jcip.annotations.ThreadSafe;

/**
 * 截图启动参数值对象 — 封装崩溃恢复所需的启动参数。
 *
 * <p>从 {@link CaptureHandler} 的散落字段（launchHwnd、launchMaxFps、launchExePath）抽取，
 * 使崩溃恢复参数内聚为一个不可变对象。
 */
@ThreadSafe
public record CaptureLaunchParams(long hwnd, int maxFps, String exePath) {
}
