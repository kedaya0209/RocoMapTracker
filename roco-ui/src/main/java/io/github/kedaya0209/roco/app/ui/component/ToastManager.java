package io.github.kedaya0209.roco.app.ui.component;

import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.config.RenderConfig;
import io.github.kedaya0209.roco.app.hook.event.NotificationType;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Toast 通知管理器 — 单通知模式，重复内容自动去重。
 * <p>
 * 同一时刻只显示一个通知：新通知若与当前显示的或队列中的内容重复则丢弃；
 * 不重复的通知排队，当前通知消失后依次播放。
 * 去重窗口从 {@link RenderConfig#TOAST_DISPLAY_SEC} 读取，支持热重载。
 */
@NotThreadSafe
public class ToastManager {

    private static final double STACK_OFFSET = 56;

    private static final Queue<ToastEntry> queue = new LinkedList<>();
    private static ToastEntry currentEntry = null;
    private static long currentEntryStartTime = 0;
    private static boolean active = false;
    private static StackPane currentParent = null;

    private record ToastEntry(String message, NotificationType type) {}

    /**
     * 在指定父节点中显示 Toast 通知。
     *
     * @param parent  父容器（通常是 rootStack）
     * @param message 通知文本
     * @param type    通知类型
     */
    public static void show(StackPane parent, String message, NotificationType type) {
        ToastEntry entry = new ToastEntry(message, type);
        long displayMs = RenderConfig.TOAST_DISPLAY_SEC * 1000L;

        // 展示时间已过 → 当前条目过期，视作无通知
        if (currentEntry != null && System.currentTimeMillis() - currentEntryStartTime >= displayMs) {
            currentEntry = null;
        }

        // 与当前展示中的通知内容重复 → 丢弃
        if (currentEntry != null && currentEntry.equals(entry)) {
            return;
        }

        // 与队列中等待的通知内容重复 → 丢弃
        if (queue.contains(entry)) {
            return;
        }

        if (!active) {
            currentParent = parent;
            showImmediately(parent, entry);
        } else {
            queue.add(entry);
        }
    }

    private static void showImmediately(StackPane parent, ToastEntry entry) {
        active = true;
        currentEntry = entry;
        currentEntryStartTime = System.currentTimeMillis();

        NotificationToast toast = new NotificationToast(entry.message(), entry.type());
        StackPane.setAlignment(toast, Pos.TOP_CENTER);
        StackPane.setMargin(toast, new Insets(STACK_OFFSET, 0, 0, 0));

        toast.setTranslateX(400);
        toast.setViewOrder(-100);
        parent.getChildren().add(toast);

        // 滑入动画（从右至左）
        Timeline slideIn = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(toast.translateXProperty(), 400)),
                new KeyFrame(Duration.millis(RenderConfig.TOAST_FADE_IN_MS),
                        new KeyValue(toast.translateXProperty(), 0, Interpolator.EASE_OUT))
        );

        // 滑出动画
        Timeline slideOut = new Timeline(
                new KeyFrame(Duration.millis(RenderConfig.TOAST_FADE_OUT_MS),
                        new KeyValue(toast.translateXProperty(), 400, Interpolator.EASE_IN))
        );
        slideOut.setOnFinished(_ -> {
            parent.getChildren().remove(toast);
            active = false;
            currentEntry = null;
            showNext();
        });

        // 播放计时，结束后滑出
        Timeline display = new Timeline(
                new KeyFrame(Duration.seconds(RenderConfig.TOAST_DISPLAY_SEC), _ -> {
                    currentEntry = null;
                    slideOut.play();
                })
        );

        slideIn.play();
        slideIn.setOnFinished(_ -> display.play());
    }

    private static void showNext() {
        if (currentParent == null) return;
        ToastEntry next = queue.poll();
        if (next != null) {
            showImmediately(currentParent, next);
        }
    }
}
