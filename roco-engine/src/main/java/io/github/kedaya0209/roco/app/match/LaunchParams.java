package io.github.kedaya0209.roco.app.match;

import net.jcip.annotations.ThreadSafe;

/**
 * SIFT 启动参数值对象 — 封装当前活跃变体状态。
 *
 * <p>替代 SiftMatchHandler 中散落的 {@code activeVariant} 字段读写，
 * 使变体状态管理内聚。
 */
@ThreadSafe
public class LaunchParams {

    private volatile SiftVariant activeVariant;

    public LaunchParams(SiftVariant initialVariant) {
        this.activeVariant = initialVariant;
    }

    public SiftVariant get() {
        return activeVariant;
    }

    public void set(SiftVariant variant) {
        this.activeVariant = variant;
    }
}
