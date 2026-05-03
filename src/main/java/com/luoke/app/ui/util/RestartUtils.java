package com.luoke.app.ui.util;

import javafx.application.Platform;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

public class RestartUtils {

    public static void restart() {
        try {
            List<String> command = new ArrayList<>();
            
            // 兼容 GraalVM Native Image
            String nativeImage = System.getProperty("org.graalvm.nativeimage.imagepath");
            
            if (nativeImage != null) {
                command.add(nativeImage);
            } else {
                // JVM 环境
                String javaExe = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
                command.add(javaExe);
                command.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
                command.add("-cp");
                command.add(System.getProperty("java.class.path"));
                
                // 获取启动类
                StackTraceElement[] stack = Thread.currentThread().getStackTrace();
                String mainClass = stack[stack.length - 1].getClassName();
                command.add(mainClass);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start();

            Platform.exit();
            System.exit(0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}