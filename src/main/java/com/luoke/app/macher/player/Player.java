package com.luoke.app.macher.player;

/**
 * 玩家实体 — 检测结果容器。
 */
public class Player {

    private final boolean found;
    private final double angle;

    public Player(boolean found, double angle) {
        this.found = found;
        this.angle = angle;
    }

    public boolean isFound() {
        return found;
    }

    public double getAngle() {
        return angle;
    }
}
