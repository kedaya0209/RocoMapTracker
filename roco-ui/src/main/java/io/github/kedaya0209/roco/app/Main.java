package io.github.kedaya0209.roco.app;

import io.github.kedaya0209.roco.app.utils.EnvironmentUtil;
import io.github.kedaya0209.roco.app.utils.FilePathUtil;
import io.github.kedaya0209.roco.app.utils.ResourceUtils;
import net.jcip.annotations.NotThreadSafe;
import io.github.kedaya0209.roco.app.ui.ModernCanvasApp;
import javafx.application.Application;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
     * 将 JavaFX JNI DLL 从 classpath 提取到 exe 所在目录并预加载。
     * 启动器在同一目录已经放下了 vcruntime140.dll / vcruntime140_1.dll，
     * 这里补上 JavaFX 需要的其他 DLL（prism_d3d、glass 等）。
     * <p>
     * 同时设置 {@code javafx.cachedir} 为 exe 所在目录，使 JavaFX
     * 的 NativeLibLoader 也使用此路径，避开默认的
     * {@code ~/.openjfx/cache/}（在中文用户名下会触发 GraalVM
     * {@code System.load} 的 Unicode 缺陷）。
     */
    private static void preloadNativeLibraries() {
        if (!EnvironmentUtil.isNative()) return;
        File exeDir = FilePathUtil.getAppRootDir().toFile();
        File dllDir = new File(exeDir, "dll");
        dllDir.mkdirs();

        System.setProperty("javafx.cachedir", dllDir.getAbsolutePath());
        log.info("javafx.cachedir = {}", dllDir.getAbsolutePath());

        String[] dlls = {
                "ucrtbase", "vcruntime140", "vcruntime140_1",
                "msvcp140", "msvcp140_1", "msvcp140_2",
                "prism_d3d", "glass", "javafx_font", "javafx_iio",
                "prism_common", "prism_sw", "decora_sse"
        };
        for (String dll : dlls) {
            File f = new File(dllDir, dll + ".dll");
            if (!f.exists()) {
                extractDllFromClasspath(dll, f);
            }
            try {
                System.loadLibrary(dll);
            } catch (UnsatisfiedLinkError e) {
                log.warn("DLL 预加载失败: {} - {}", dll, e.getMessage());
            }
        }
    }

    private static void extractDllFromClasspath(String dllName, File destFile) {
        String resourcePath = "/javafx-dll/" + dllName + ".dll";
        try (InputStream in = ResourceUtils.getResourceStream(resourcePath)) {
            destFile.getParentFile().mkdirs();
            Files.copy(in, destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            log.info("提取 DLL: {} → {}", resourcePath, destFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("提取 DLL 失败: {}", resourcePath, e);
        }
    }
}
