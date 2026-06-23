package io.github.kedaya0209.roco.app;

import io.github.kedaya0209.roco.app.utils.EnvironmentUtil;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.ModernCanvasApp;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;

import java.io.File;

@Slf4j
@NotThreadSafe
public class Main {

    static {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
                log.error("未捕获异常 [{}]: {}", thread.getName(), throwable.getMessage(), throwable));
    }

    static void main(String[] args) {
        preloadNativeLibraries();
        Application.launch(ModernCanvasApp.class, args);
    }

    /**
     * DLL 已由 stub 从 .rmtldr 提取到 dll/，VC++ DLL 已由 stub Phase 11 加载。
     * 使用 System.loadLibrary(短名) 注册到 GraalVM NativeLibraries —— stub Phase 9
     * 已通过 AddDllDirectory(dll/) 将 dll/ 加入 Windows DLL 搜索路径，
     * LoadLibraryW 能正确找到并加载 DLL（含 Unicode 路径处理）。
     */
    private static void preloadNativeLibraries() {
        if (!EnvironmentUtil.isNative()) return;

        File exeDir = FilePathUtil.getAppRootDir().toFile();
        File dllDir = new File(exeDir, "dll");

        // VC++ DLL 已在 stub Phase 11 加载，此处仅做 Java 侧注册
        // awt/java 必须在 JDK 内部 System.loadLibrary 前注册
        String[] dlls = {
                "vcruntime140", "vcruntime140_1",
                "msvcp140", "msvcp140_1", "msvcp140_2",
                "prism_common", "prism_d3d", "prism_sw",
                "glass", "decora_sse", "javafx_font", "javafx_iio",
                "java"
        };
        for (String dll : dlls) {
            try {
                System.loadLibrary(dll);
            } catch (UnsatisfiedLinkError e) {
                log.warn("DLL 预加载失败: {} - {}", dll, e.getMessage());
            }
        }

        System.setProperty("javafx.cachedir", dllDir.getAbsolutePath());
        log.info("DLL 准备完成: {}", dllDir.getAbsolutePath());
    }
}
