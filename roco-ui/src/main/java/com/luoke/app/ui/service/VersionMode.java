package com.luoke.app.ui.service;

import net.jcip.annotations.ThreadSafe;

/**
 * 应用版本模式枚举。
 */
@ThreadSafe
public enum VersionMode {
    /** 标准版 — 基于图像识别，导航/路线/标记等基础功能 */
    STANDARD,
    /** 高级版 — 标准版 + 网络抓包资源统计 */
    ADVANCED
}
