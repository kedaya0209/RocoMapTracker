package io.github.kedaya0209.roco.app.ui.state;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.hook.AppEvents;
import io.github.kedaya0209.roco.app.hook.event.FollowModeEvent;
import io.github.kedaya0209.roco.app.hook.event.NavModeEvent;
import io.github.kedaya0209.roco.app.hook.event.PlayerStateEvent;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

/**
 * 事件桥接器 — 订阅 {@link AppEvents} 并将数据编组到 JavaFX 线程以更新状态类。
 * <p>
 * 在 {@code ModernCanvasApp.buildMainUi()} 开头调用 {@link #init()}。
 * 所有状态更新通过 {@link Platform#runLater(Runnable)} 确保在 FX 线程执行。
 * </p>
 */
@NotThreadSafe
@Slf4j
public final class StateBridge {

    private static volatile boolean initialized = false;

    private StateBridge() {
    }

    public static void init() {
        if (initialized) return;
        synchronized (StateBridge.class) {
            if (initialized) return;
            initialized = true;
        }

        log.info("StateBridge initialized");

        // === 玩家状态 ===
        AppEvents.subscribe(PlayerStateEvent.class, event -> Platform.runLater(() ->
                ViewportState.getInstance().updatePlayerPosition(event.x(), event.y(), event.angle())));

        // === 相机模式 ===
        AppEvents.subscribe(FollowModeEvent.class, event -> Platform.runLater(() ->
                ViewportState.getInstance().setFollowMode(event.followMode())));

        AppEvents.subscribe(NavModeEvent.class, event -> Platform.runLater(() ->
                ViewportState.getInstance().setNavMode(event.enabled())));
    }
}
