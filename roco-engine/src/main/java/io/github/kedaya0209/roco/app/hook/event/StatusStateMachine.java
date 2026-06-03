package io.github.kedaya0209.roco.app.hook.event;

import net.jcip.annotations.ThreadSafe;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 状态机 — 确保状态轮播消息按合法生命周期流转。
 *
 * <p>每个状态键（capture/sift/minimap）独立跟踪当前状态，非法转换跳过并警告。
 * 状态机无副作用，仅维护状态和校验规则；事件发布由调用方负责。</p>
 *
 * <h3>状态图</h3>
 * <pre>{@code
 * Capture: IDLE ─→ LOADING ─→ READY ─→ DISCONNECTED ─→ READY (自动重连)
 *                  │                            │
 *                  ├→ START_FAILED ─→ LOADING    ├→ LOADING (手动重连)
 *                  └→ RETRY ───────→ LOADING     └→ RETRY
 *
 * SIFT:    IDLE ─→ LOADING ─→ READY ─→ DISCONNECTED ─→ READY (重启完成)
 *                  │
 *                  └→ FAILED
 *                  │
 *                  └→ DISCONNECTED
 *
 * Minimap: IDLE ─→ TRACKING ⇄ LOST
 * }</pre>
 */
@ThreadSafe
@Slf4j
public final class StatusStateMachine {

    // ======================== 状态枚举 ========================

    @ThreadSafe
    public enum State {
        IDLE,
        LOADING,
        READY,
        RETRY,
        START_FAILED,
        FAILED,
        DISCONNECTED,
        LOST,
        TRACKING,
        ACTIVE,
        PAUSED
    }

    // ======================== 状态键 ========================

    @ThreadSafe
    public enum StatusKey {
        CAPTURE,
        SIFT,
        MINIMAP,
        MATCH
    }

    // ======================== 合法转换表 ========================

    private static final Map<StatusKey, Map<State, Set<State>>> TRANSITIONS = Map.of(
            StatusKey.CAPTURE, Map.of(
                    State.IDLE, Set.of(State.LOADING, State.RETRY),
                    State.LOADING, Set.of(State.READY, State.START_FAILED, State.RETRY, State.DISCONNECTED),
                    State.READY, Set.of(State.DISCONNECTED),
                    State.RETRY, Set.of(State.LOADING),
                    State.START_FAILED, Set.of(State.LOADING),
                    State.DISCONNECTED, Set.of(State.LOADING, State.RETRY, State.READY)
            ),
            StatusKey.SIFT, Map.of(
                    State.IDLE, Set.of(State.LOADING),
                    State.LOADING, Set.of(State.READY, State.FAILED, State.DISCONNECTED),
                    State.READY, Set.of(State.DISCONNECTED),
                    State.DISCONNECTED, Set.of(State.READY)
            ),
            StatusKey.MINIMAP, Map.of(
                    State.IDLE, Set.of(State.TRACKING, State.LOST),
                    State.TRACKING, Set.of(State.LOST),
                    State.LOST, Set.of(State.TRACKING)
            ),
            StatusKey.MATCH, Map.of(
                    State.IDLE, Set.of(State.ACTIVE),
                    State.ACTIVE, Set.of(State.PAUSED),
                    State.PAUSED, Set.of(State.ACTIVE)
            )
    );

    // ======================== 实例状态 ========================

    private final EnumMap<StatusKey, State> currentStates = new EnumMap<>(StatusKey.class);

    // ======================== 单例 ========================

    private StatusStateMachine() {
        for (StatusKey key : StatusKey.values()) {
            currentStates.put(key, State.IDLE);
        }
    }

    @ThreadSafe
    private static class Holder {
        static final StatusStateMachine INSTANCE = new StatusStateMachine();
    }

    public static StatusStateMachine getInstance() {
        return Holder.INSTANCE;
    }

    // ======================== 公共 API ========================

    /**
     * 尝试从当前状态转换到目标状态。
     *
     * @param key      状态键
     * @param newState 目标状态
     * @return true 表示转换合法（已更新状态），false 表示非法转换（状态未变）
     */
    public synchronized boolean tryTransition(StatusKey key, State newState) {
        State current = currentStates.get(key);
        if (current == newState) {
            return true; // 已在目标状态，视为合法
        }

        Map<State, Set<State>> keyTransitions = TRANSITIONS.get(key);
        if (keyTransitions == null) {
            log.warn("未知状态键: {}", key);
            return false;
        }

        Set<State> allowed = keyTransitions.get(current);
        if (allowed != null && allowed.contains(newState)) {
            currentStates.put(key, newState);
            log.debug("状态转换: {}: {} → {}", key, current, newState);
            return true;
        }

        log.warn("状态转换非法: {}: {} → {}", key, current, newState);
        return false;
    }

    /**
     * 获取指定状态键的当前状态。
     */
    public synchronized State currentState(StatusKey key) {
        return currentStates.get(key);
    }

    /**
     * 重置指定键到 IDLE 状态。
     */
    public synchronized void reset(StatusKey key) {
        currentStates.put(key, State.IDLE);
    }

    /**
     * 重置所有状态到 IDLE。
     */
    public synchronized void resetAll() {
        for (StatusKey key : StatusKey.values()) {
            currentStates.put(key, State.IDLE);
        }
    }

}
