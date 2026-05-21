package com.luoke.app;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.ui.ModernCanvasApp;
import com.luoke.app.utils.ResourceExtractor;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@NotThreadSafe
public class Main {

    static {
        // 全局未捕获异常处理器 — 确保虚拟线程抛出的异常不被静默吞掉
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            log.error("未捕获异常 [{}]: {}", thread.getName(), throwable.getMessage(), throwable);
        });
    }

    static void main(String[] args) {
        ResourceExtractor.extractAll();
        Application.launch(ModernCanvasApp.class, args);
    }
}
