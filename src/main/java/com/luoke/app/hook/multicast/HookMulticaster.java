package com.luoke.app.hook.multicast;

import com.luoke.app.hook.AbstractGenericHook;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.container.HookContainer;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
public class HookMulticaster {

    private static final HookMulticaster INSTANCE = new HookMulticaster();
    private final HookContainer container;
    private final BlockingQueue<HookEventTask> eventQueue;
    private final ExecutorService virtualExecutor;
    private volatile boolean running;
    private HookMulticaster() {
        this.container = HookContainer.getInstance();
        this.eventQueue = new LinkedBlockingQueue<>();
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.running = true;
        startConsumeLoop();
    }

    public static HookMulticaster getInstance() {
        return INSTANCE;
    }

    /**
     * 提交事件入队（生产者无阻塞）
     */
    public void enqueue(HookEventType eventType, Object data) {
        if (!running) {
            return;
        }
        eventQueue.offer(new HookEventTask(eventType, data));
    }

    /**
     * 启动虚拟线程 串行消费队列
     */
    private void startConsumeLoop() {
        virtualExecutor.submit(() -> {
            while (running) {
                try {
                    HookEventTask task = eventQueue.take();
                    dispatch(task.eventType(), task.data());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("钩子事件消费异常", e);
                }
            }
        });
    }

    /**
     * 事件分发核心
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void dispatch(HookEventType eventType, Object data) {
        List<AbstractGenericHook<?>> hookList = container.getHookList(eventType);
        if (hookList.isEmpty()) {
            return;
        }

        boolean uiNeed = isUiEvent(eventType);

        for (AbstractGenericHook<?> hook : hookList) {
            try {
                if (uiNeed) {
                    Platform.runLater(() -> {
                        ((AbstractGenericHook) hook).onEvent(eventType, data);
                    });
                } else {
                    ((AbstractGenericHook) hook).onEvent(eventType, data);
                }
            } catch (Throwable t) {
                log.error("钩子执行异常，event:{}", eventType, t);
            }
        }
    }

    private boolean isUiEvent(HookEventType type) {
        return switch (type) {
            case PLAYER_UPDATE, MAP_CHANGED -> true;
            default -> false;
        };
    }

    /**
     * 优雅关闭
     */
    public void shutdown() {
        running = false;
        virtualExecutor.shutdownNow();
        eventQueue.clear();
    }
}