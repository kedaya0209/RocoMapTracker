package com.luoke.app;

import com.luoke.app.ui.ModernCanvasApp;
import com.luoke.app.utils.FileUtil;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;

@Slf4j
public class Main {

    static {
        try {
            FileUtil.extractAll();
            System.setProperty("org.bytedeco.javacpp.nopointergc", "true");
            Loader.load(opencv_core.class);
            log.info("OpenCV (JavaCPP) 环境初始化成功");
        } catch (Throwable e) {
            log.error("OpenCV 初始化失败", e);
        }
    }

    static void main(String[] args) {
        Application.launch(ModernCanvasApp.class, args);
    }
}
