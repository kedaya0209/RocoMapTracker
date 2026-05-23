package com.luoke.app.hook.event;

import net.jcip.annotations.ThreadSafe;

/**
 * 状态轮播事件 — 用于 TitleBar 内联状态轮播展示.
 * 状态轮播事件 — 用于 TitleBar 内联状态轮播展示.
 *
 * <p>使用静态工厂方法创建，避免魔法值：
 * <pre>{@code
 *   StatusCarouselEvent.captureLoading()
 *   StatusCarouselEvent.siftReady()
 * }</pre>
 *
 * <p>每个工厂方法会经过 {@link StatusStateMachine} 校验流转合法性，
 * 非法转换返回 {@code null}（调用方发布到 Hook 时被 {@code instanceof} 自然过滤）。</p>
 *
 * @param key  状态唯一键（去重用，见 {@code KEY_*} 常量）
 * @param text 显示文本
 * @param type 状态类型（决定颜色）
 */
@ThreadSafe
public record StatusCarouselEvent(String key, String text, Type type) {

    // ======================== Key 常量 ========================

    public static final String KEY_CAPTURE = "capture";
    public static final String KEY_SIFT = "sift";
    public static final String KEY_MINIMAP = "minimap";
    public static final String KEY_MATCH = "match";

    // ======================== Capture 状态 ========================

    public static StatusCarouselEvent captureLoading() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.CAPTURE, StatusStateMachine.State.LOADING)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_CAPTURE, "capture加载中", Type.LOADING);
    }

    public static StatusCarouselEvent captureReady() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.CAPTURE, StatusStateMachine.State.READY)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_CAPTURE, "capture加载完成", Type.SUCCESS);
    }

    public static StatusCarouselEvent captureRetry() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.CAPTURE, StatusStateMachine.State.RETRY)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_CAPTURE, "未找到游戏窗口，5秒后重试...", Type.INFO);
    }

    public static StatusCarouselEvent captureStartFailed() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.CAPTURE, StatusStateMachine.State.START_FAILED)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_CAPTURE, "capture启动失败，5秒后重试...", Type.ERROR);
    }

    public static StatusCarouselEvent captureDisconnected() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.CAPTURE, StatusStateMachine.State.DISCONNECTED)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_CAPTURE, "capture断开", Type.ERROR);
    }

    // ======================== SIFT 状态 ========================

    public static StatusCarouselEvent siftLoading() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.SIFT, StatusStateMachine.State.LOADING)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_SIFT, "sift引擎加载中", Type.LOADING);
    }

    public static StatusCarouselEvent siftReady() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.SIFT, StatusStateMachine.State.READY)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_SIFT, "sift引擎加载完成", Type.SUCCESS);
    }

    public static StatusCarouselEvent siftFailed() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.SIFT, StatusStateMachine.State.FAILED)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_SIFT, "sift引擎加载失败", Type.ERROR);
    }

    public static StatusCarouselEvent siftDisconnected() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.SIFT, StatusStateMachine.State.DISCONNECTED)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_SIFT, "sift引擎断开", Type.ERROR);
    }

    // ======================== 小地图状态 ========================

    public static StatusCarouselEvent minimapLost() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.MINIMAP, StatusStateMachine.State.LOST)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_MINIMAP, "未检测到小地图", Type.ERROR);
    }

    public static StatusCarouselEvent minimapFound() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.MINIMAP, StatusStateMachine.State.TRACKING)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_MINIMAP, "检测到小地图，正在跟踪", Type.SUCCESS);
    }

    // ======================== 匹配开关状态 ========================

    public static StatusCarouselEvent matchingPaused() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.MATCH, StatusStateMachine.State.PAUSED)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_MATCH, "匹配已暂停", Type.INFO);
    }

    public static StatusCarouselEvent matchingResumed() {
        if (!StatusStateMachine.getInstance().tryTransition(StatusStateMachine.StatusKey.MATCH, StatusStateMachine.State.ACTIVE)) {
            return null;
        }
        return new StatusCarouselEvent(KEY_MATCH, "匹配已开启", Type.SUCCESS);
    }

    // ======================== 类型枚举 ========================

    @ThreadSafe
    public enum Type {
        /** 加载中 / 进行中 */
        LOADING,
        /** 加载完成 / 已就绪 */
        SUCCESS,
        /** 出错 / 断开 */
        ERROR,
        /** 普通信息 */
        INFO
    }
}
