package io.github.kedaya0209.roco.app.platform;

import net.jcip.annotations.ThreadSafe;

/**
 * 窗口描述符 — 枚举窗口时返回的元数据。
 *
 * @param hwnd       窗口句柄
 * @param title      窗口标题
 * @param pid        所属进程 PID（用于过滤自身窗口）
 * @param left       窗口左上角 X 坐标（屏幕像素）
 * @param top        窗口左上角 Y 坐标（屏幕像素）
 * @param width      窗口宽度（像素）
 * @param height     窗口高度（像素）
 * @param isMinimized 是否最小化
 */
@ThreadSafe
public record WindowDescriptor(long hwnd, String title, int pid,
                                int left, int top,
                                int width, int height,
                                boolean isMinimized) {}
