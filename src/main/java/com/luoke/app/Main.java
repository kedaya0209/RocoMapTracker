package com.luoke.app;

import com.luoke.app.ui.ModernCanvasApp;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Core;

@Slf4j
public class Main {

    static {
        try {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            log.info("✅ OpenCV 环境初始化成功，版本: {}", Core.VERSION);
        } catch (Throwable e) {
            log.error("❌ OpenCV 初始化失败，请检查是否引入了 openpnp 依赖", e);
        }
    }

    public static void main(String[] args) {
        Application.launch(ModernCanvasApp.class, args);
    }
}