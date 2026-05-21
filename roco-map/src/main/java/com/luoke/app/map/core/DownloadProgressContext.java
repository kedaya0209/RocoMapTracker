package com.luoke.app.map.core;

import lombok.Getter;
import lombok.Setter;

import net.jcip.annotations.ThreadSafe;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * 下载进度上下文 - 单例模式
 * 用于实时统计 BFS 探测下载的进度，解决界面黑屏问题
 */
@ThreadSafe
public final class DownloadProgressContext {
    private static final DownloadProgressContext INSTANCE = new DownloadProgressContext();
    @Getter
    private final AtomicInteger totalTasks = new AtomicInteger(0);
    @Getter
    private final AtomicInteger completedTasks = new AtomicInteger(0);
    @Getter
    @Setter
    private volatile String statusText = "等待中...";
    private BiConsumer<Integer, Integer> onProgressUpdate;

    private DownloadProgressContext() {
    }

    public static DownloadProgressContext getInstance() {
        return INSTANCE;
    }

    public void reset(String mapTag) {
        totalTasks.set(0);
        completedTasks.set(0);
        statusText = mapTag;
        notifyListener();
    }

    public void addTask() {
        totalTasks.incrementAndGet();
        notifyListener();
    }

    public void finishTask() {
        completedTasks.incrementAndGet();
        notifyListener();
    }

    public void setOnProgressUpdate(BiConsumer<Integer, Integer> listener) {
        this.onProgressUpdate = listener;
    }

    private void notifyListener() {
        if (onProgressUpdate != null) {
            onProgressUpdate.accept(completedTasks.get(), totalTasks.get());
        }
    }
}