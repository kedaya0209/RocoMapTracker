package io.github.kedaya0209.roco.app.ui.util;

import net.jcip.annotations.ThreadSafe;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;

@Slf4j
@ThreadSafe
public class RestartUtils {

    private static final String RESTART_TASK_NAME = "RocoMapTracker-Restart";

    public static void restart() {
        Path exePathObj = FilePathUtil.getExePath();
        if (exePathObj == null) {
            log.error("无法获取当前可执行文件路径");
            return;
        }
        String exePath = exePathObj.toString();

        // 通过 schtasks 启动新进程，脱离 JobObject 防止被一起杀死
        if (startViaSchtasks(exePath)) {
            log.info("重启任务已通过 schtasks 创建");
        } else {
            // 降级：直接启动（可能被 JobObject 终止）
            log.warn("schtasks 失败，降级使用 ProcessBuilder");
            try {
                new ProcessBuilder(exePath).start();
            } catch (IOException e) {
                log.error("程序重启发生异常", e);
                return;
            }
        }

        Platform.exit();
        System.exit(0);
    }

    private static boolean startViaSchtasks(String exePath) {
        try {
            LocalDateTime future = LocalDateTime.now().plusSeconds(2);
            String startTime = String.format("%02d:%02d", future.getHour(), future.getMinute());

            Process create = new ProcessBuilder(
                    "schtasks.exe", "/create",
                    "/tn", RESTART_TASK_NAME,
                    "/tr", "\"" + exePath + "\"",
                    "/sc", "once",
                    "/st", startTime,
                    "/f",
                    "/rl", "LIMITED"
            ).start();
            if (create.waitFor() != 0) {
                log.warn("schtasks /create 失败");
                return false;
            }

            new ProcessBuilder("schtasks.exe", "/run", "/tn", RESTART_TASK_NAME)
                    .start().waitFor();
            return true;
        } catch (Exception e) {
            log.warn("schtasks 启动失败", e);
            return false;
        }
    }
}