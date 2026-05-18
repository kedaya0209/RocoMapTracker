package com.luoke.app.ui.service;

import atlantafx.base.theme.*;
import com.luoke.app.config.AppConfig;
import javafx.application.Application;

/**
 * 主题管理器 — 主题列表、应用、切换。
 * 纯静态工具类，无内部状态。
 * 从 ModernCanvasApp 拆分，遵循单一职责原则。
 */
public class ThemeManager {

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
        Application.setUserAgentStylesheet(theme.getUserAgentStylesheet());
    }

    public static void switchTheme(String name) {
        AppConfig.THEME = name;
        AppConfig.save();
        applyTheme(name);
    }
}
