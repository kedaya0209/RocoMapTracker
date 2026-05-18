package com.luoke.app.ui.util;

import com.luoke.app.Main;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class RestartUtils {

    public static void restart() {
        try {
            List<String> command = new ArrayList<>();

            // 兼容 GraalVM Native Image
            // 获取当前运行的 exe 绝对路径
            String nativeImage = new File(Main.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath())
                    .getName();

            command.add(nativeImage);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.start();

            Platform.exit();
            System.exit(0);
        } catch (IOException e) {
            log.error("程序重启发生异常,e:", e);
        }
    }
}