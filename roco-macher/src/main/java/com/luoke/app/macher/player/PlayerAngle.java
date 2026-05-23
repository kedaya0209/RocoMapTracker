package com.luoke.app.macher.player;

import net.jcip.annotations.ThreadSafe;

/**
 * 玩家实体 — 检测结果容器。
 */
@ThreadSafe
public record PlayerAngle(double angle) {
}
