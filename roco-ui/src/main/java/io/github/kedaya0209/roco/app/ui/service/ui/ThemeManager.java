package io.github.kedaya0209.roco.app.ui.service.ui;

import lombok.Getter;
import net.jcip.annotations.ThreadSafe;
import atlantafx.base.theme.*;
import io.github.kedaya0209.roco.app.config.ConfigPersistence;
import io.github.kedaya0209.roco.app.config.UiConfig;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 主题管理器 — 主题列表、应用、切换。
 * 从 ModernCanvasApp 拆分，遵循单一职责原则。
 */
@ThreadSafe
public class ThemeManager {

    /** 当前主题的样式表 URL，供 Scene 添加以确保 inline style 的 CSS 变量能正确解析 */
    @Getter
    private static volatile String currentStylesheetUrl;

    /** 主题变更监听器，用于更新非 Window.getWindows() 能覆盖到的 Scene */
    private static final List<Runnable> themeChangeListeners = new CopyOnWriteArrayList<>();

    /**
     * 注册主题变更监听器。
     * <p>在每次 {@link #applyTheme(String)} 执行完毕后被调用（FX 线程）。
     */
    public static void addThemeChangeListener(Runnable listener) {
        themeChangeListeners.add(listener);
    }

    public static String[] getAvailableThemes() {
        return new String[]{"PrimerDark", "PrimerLight", "NordDark", "NordLight",
                "CupertinoDark", "CupertinoLight", "Dracula"};
    }

    public static void applyTheme(String name) {
        Theme theme = switch (name) {
            case "PrimerLight" -> new PrimerLight();
            case "NordDark" -> new NordDark();
            case "NordLight" -> new NordLight();
            case "CupertinoDark" -> new CupertinoDark();
            case "CupertinoLight" -> new CupertinoLight();
            case "Dracula" -> new Dracula();
            default -> new PrimerDark();
        };
        currentStylesheetUrl = theme.getUserAgentStylesheet();
        Application.setUserAgentStylesheet(currentStylesheetUrl);
        // 强制所有已打开 Stage 重新应用 CSS（设置面板、路线管理等）
        for (Window window : Window.getWindows()) {
            if (window instanceof Stage stage && stage.getScene() != null) {
                Scene scene = stage.getScene();
                scene.getStylesheets().removeIf(url -> url != null
                        && (url.contains("atlantafx") || url.contains("theme")));
                scene.getStylesheets().add(currentStylesheetUrl);
                scene.getRoot().applyCss();
            }
        }
        // 通知注册的监听器（如 SettingsStage），覆盖 Window.getWindows() 无法包含的场景
        for (Runnable listener : themeChangeListeners) {
            listener.run();
        }
    }

    public static void switchTheme(String name) {
        UiConfig.THEME = name;
        ConfigPersistence.save();
        applyTheme(name);
    }
}
