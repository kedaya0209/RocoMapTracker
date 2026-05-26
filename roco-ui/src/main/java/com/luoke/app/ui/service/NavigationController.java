package com.luoke.app.ui.service;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.NavigConfig;
import com.luoke.app.context.CameraContext;
import lombok.extern.slf4j.Slf4j;

/**
 * 导航模式旋转控制器 — 偏转检测、EMA 平滑、防抖延迟、匀速旋转动画。
 *
 * <p>核心逻辑：
 * <ol>
 *   <li>接收最新 playerAngle</li>
 *   <li>瞬跳过滤（单帧变化 &gt; 45° → 重同步 smoothAngle，防止卡死）</li>
 *   <li>EMA 平滑（alpha=0.3，响应快于平滑）</li>
 *   <li>偏差 &ge; MAX_DEFLECTION_ANGLE 且持续超过 ROTATION_DELAY_MS → 提交旋转目标</li>
 *   <li>偏差回落 &lt; MAX_DEFLECTION_ANGLE → 重置延迟计数器</li>
 *   <li>匀速旋转动画：navAngle 以 MAX_ROTATION_RATE °/帧 向目标逼近</li>
 * </ol>
 */
@NotThreadSafe
@Slf4j
public class NavigationController {

    /** EMA 平滑系数（越高响应越快） */
    private static final double EMA_ALPHA = 0.3;
    /** 瞬跳检测阈值（度），单帧角度变化超过此值视为瞬跳 */
    private static final double JUMP_THRESHOLD = 45.0;
    /** 匀速旋转速率（度/帧），~180°/s @ 60fps */
    private static final double MAX_ROTATION_RATE = 3.0;

    private final CameraContext cameraContext;

    /** EMA 平滑后的角度 */
    private double smoothAngle;
    private boolean smoothInitialized;
    /** 最后提交的旋转目标角度（navAngle 动画的目标） */
    private double lastCommittedAngle;
    /** 当前显示的旋转角度（匀速动画逼近 lastCommittedAngle） */
    private double navAngle;
    /** 偏差连续超过阈值的时间戳（0=未超过） */
    private long excessStartTime;
    /** 上次旋转提交时间戳（旋转冷却用） */
    private long lastRotationTime;

    public NavigationController() {
        this.cameraContext = CameraContext.getInstance();
    }

    /**
     * 每帧调用，传入最新玩家角度。
     */
    public void update(double rawAngle, boolean navMode) {
        if (!navMode) {
            if (smoothInitialized) reset();
            return;
        }

        // 初始化平滑值 & 显示角度
        if (!smoothInitialized) {
            smoothAngle = rawAngle;
            lastCommittedAngle = rawAngle;
            navAngle = rawAngle;
            smoothInitialized = true;
            cameraContext.setNavAngle(rawAngle);
            return;
        }

        // 瞬跳过滤 + 重同步：delta 过大时重新对齐，防止 smoothAngle 卡死
        double delta = normalizeDelta(rawAngle - smoothAngle);
        if (Math.abs(delta) > JUMP_THRESHOLD) {
            smoothAngle = rawAngle;
            animateRotation();
            return;
        }

        // EMA 平滑
        smoothAngle = normalizeAngle(smoothAngle + EMA_ALPHA * delta);

        // 计算与上次提交角度的偏差
        double deviation = normalizeDelta(smoothAngle - lastCommittedAngle);

        // 防抖延迟
        if (Math.abs(deviation) >= NavigConfig.MAX_DEFLECTION_ANGLE) {
            if (excessStartTime == 0) {
                excessStartTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - excessStartTime >= NavigConfig.ROTATION_DELAY_MS) {
                // 持续超过阈值足够久 → 提交新旋转目标（但受冷却间隔限制）
                long now = System.currentTimeMillis();
                if (now - lastRotationTime >= NavigConfig.ROTATION_INTERVAL_MS) {
                    lastCommittedAngle = smoothAngle;
                    lastRotationTime = now;
                    excessStartTime = 0;
                }
            }
        } else {
            // 偏差回落 → 重置计时
            excessStartTime = 0;
        }

        // 匀速旋转动画
        animateRotation();
    }

    /**
     * navAngle 匀速逼近 lastCommittedAngle。
     */
    private void animateRotation() {
        double diff = normalizeDelta(lastCommittedAngle - navAngle);
        if (Math.abs(diff) < 0.3) {
            // 已到达目标
            if (navAngle != lastCommittedAngle) {
                navAngle = lastCommittedAngle;
                cameraContext.setNavAngle(navAngle);
            }
            return;
        }

        double step = Math.min(Math.abs(diff), MAX_ROTATION_RATE);
        navAngle = normalizeAngle(navAngle + Math.signum(diff) * step);
        cameraContext.setNavAngle(navAngle);
    }

    private void reset() {
        smoothInitialized = false;
        smoothAngle = 0;
        lastCommittedAngle = 0;
        navAngle = 0;
        excessStartTime = 0;
        lastRotationTime = 0;
        cameraContext.setNavAngle(0);
    }

    /**
     * 归一化角度到 [0, 360)
     */
    private static double normalizeAngle(double angle) {
        double a = angle % 360.0;
        if (a < 0) a += 360.0;
        return a;
    }

    /**
     * 归一化角度差到 [-180, 180]
     */
    private static double normalizeDelta(double delta) {
        while (delta > 180.0) delta -= 360.0;
        while (delta < -180.0) delta += 360.0;
        return delta;
    }
}
