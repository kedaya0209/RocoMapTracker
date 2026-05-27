package com.luoke.app.ui.service;

import lombok.Getter;
import net.jcip.annotations.ThreadSafe;
import atlantafx.base.theme.*;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.UiConfig;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * 主题管理器 — 主题列表、应用、切换。
 * 从 ModernCanvasApp 拆分，遵循单一职责原则。
 */
@ThreadSafe
public class ThemeManager {

    /** 当前主题的样式表 URL，供 Scene 添加以确保 inline style 的 CSS 变量能正确解析 */
    @Getter
    private static volatile String currentStylesheetUrl;

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
                        && (url.contains("atlanfx") || url.contains("theme")));
                scene.getStylesheets().add(currentStylesheetUrl);
                scene.getRoot().applyCss();
            }
        }
    }

    public static void switchTheme(String name) {
        UiConfig.THEME = name;
        ConfigPersistence.save();
        applyTheme(name);
    }
}
