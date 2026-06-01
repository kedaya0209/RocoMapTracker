package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * UI 与交互配置持久化 
 */
@NotThreadSafe
public final class UiConfig {

    private UiConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    // ============================================================
    // 字段声明
    // ============================================================
    /** JavaFX 主题名称 */
    public static String THEME = "PrimerDark";
    /** 窗口边缘拖拽缩放感应区宽度（像素） */
    public static int RESIZE_MARGIN = 8;
    /** 窗口最小宽度 */
    public static double MIN_WINDOW_WIDTH = 400;
    /** 窗口最小高度 */
    public static double MIN_WINDOW_HEIGHT = 300;
    /** 滚轮缩放因子（>1 放大，<1 缩小） */
    public static double INTERACTIVE_ZOOM_FACTOR = 1.1;
    /** 资源点鼠标悬停检测半径（逻辑像素） */
    public static double HOVER_DETECT_RADIUS = 16.0;
    /** 地图最大视觉缩放比例 */
    public static double MAP_VIEW_MAX_SCALE = 15.0;
    /** 统计面板字体名称 */
    public static String STATS_FONT_NAME = "Microsoft YaHei";
    /** 统计面板字号 */
    public static int STATS_FONT_SIZE = 13;
    /** 统计面板内边距 */
    public static int STATS_PADDING = 5;
    /** 物资采集面板宽度 */
    public static double RESOURCE_COUNTER_WIDTH = 220;
    /** 物资采集面板不透明度 */
    public static double RESOURCE_COUNTER_OPACITY = 0.88;
    /** Toast 最大宽度 */
    public static double TOAST_MAX_WIDTH = 400;
    /** Toast 最大高度 */
    public static double TOAST_MAX_HEIGHT = 50;
    /** 搜索后台更新条目高度 */
    public static int WIKI_ITEM_HEIGHT = 38;

    public static void load(Properties prop) {
        THEME = ConfigHelper.getStr(prop, "ui.theme", THEME);
        MAP_VIEW_MAX_SCALE = ConfigHelper.getDouble(prop, "map.view.max.scale", MAP_VIEW_MAX_SCALE);
        RESIZE_MARGIN = ConfigHelper.getInt(prop, "resize.margin", RESIZE_MARGIN);
        MIN_WINDOW_WIDTH = ConfigHelper.getDouble(prop, "min.window.width", MIN_WINDOW_WIDTH);
        MIN_WINDOW_HEIGHT = ConfigHelper.getDouble(prop, "min.window.height", MIN_WINDOW_HEIGHT);
        INTERACTIVE_ZOOM_FACTOR = ConfigHelper.getDouble(prop, "interactive.zoom.factor", INTERACTIVE_ZOOM_FACTOR);
        HOVER_DETECT_RADIUS = ConfigHelper.getDouble(prop, "hover.detect.radius", HOVER_DETECT_RADIUS);
        STATS_FONT_NAME = ConfigHelper.getStr(prop, "stats.font.name", STATS_FONT_NAME);
        STATS_FONT_SIZE = ConfigHelper.getInt(prop, "stats.font.size", STATS_FONT_SIZE);
        STATS_PADDING = ConfigHelper.getInt(prop, "stats.padding", STATS_PADDING);
        TOAST_MAX_WIDTH = ConfigHelper.getDouble(prop, "toast.max.width", TOAST_MAX_WIDTH);
        TOAST_MAX_HEIGHT = ConfigHelper.getDouble(prop, "toast.max.height", TOAST_MAX_HEIGHT);
        WIKI_ITEM_HEIGHT = ConfigHelper.getInt(prop, "wiki.item.height", WIKI_ITEM_HEIGHT);
        RESOURCE_COUNTER_WIDTH = ConfigHelper.getDouble(prop, "resource.counter.width", RESOURCE_COUNTER_WIDTH);
        RESOURCE_COUNTER_OPACITY = ConfigHelper.getDouble(prop, "resource.counter.opacity", RESOURCE_COUNTER_OPACITY);
    }

    public static void save(StringBuilder sb) {
        sb.append("# JavaFX 主题名称\n");
        sb.append("ui.theme=").append(THEME).append("\n");
        sb.append("# 地图最大视觉缩放比例\n");
        sb.append("map.view.max.scale=").append(MAP_VIEW_MAX_SCALE).append("\n");
        sb.append("# 窗口边缘拖拽缩放感应区宽度（像素）\n");
        sb.append("resize.margin=").append(RESIZE_MARGIN).append("\n");
        sb.append("# 窗口最小宽度\n");
        sb.append("min.window.width=").append(MIN_WINDOW_WIDTH).append("\n");
        sb.append("# 窗口最小高度\n");
        sb.append("min.window.height=").append(MIN_WINDOW_HEIGHT).append("\n");
        sb.append("# 滚轮缩放因子（>1 放大，<1 缩小）\n");
        sb.append("interactive.zoom.factor=").append(INTERACTIVE_ZOOM_FACTOR).append("\n");
        sb.append("# 资源点鼠标悬停检测半径（逻辑像素）\n");
        sb.append("hover.detect.radius=").append(HOVER_DETECT_RADIUS).append("\n");
        sb.append("# 统计面板字体名称\n");
        sb.append("stats.font.name=").append(STATS_FONT_NAME).append("\n");
        sb.append("# 统计面板字号\n");
        sb.append("stats.font.size=").append(STATS_FONT_SIZE).append("\n");
        sb.append("# 统计面板内边距\n");
        sb.append("stats.padding=").append(STATS_PADDING).append("\n");
        sb.append("# Toast 最大宽度\n");
        sb.append("toast.max.width=").append(TOAST_MAX_WIDTH).append("\n");
        sb.append("# Toast 最大高度\n");
        sb.append("toast.max.height=").append(TOAST_MAX_HEIGHT).append("\n");
        sb.append("# WIKI 条目高度\n");
        sb.append("wiki.item.height=").append(WIKI_ITEM_HEIGHT).append("\n");
        sb.append("# 物资采集面板宽度\n");
        sb.append("resource.counter.width=").append(RESOURCE_COUNTER_WIDTH).append("\n");
        sb.append("# 物资采集面板不透明度\n");
        sb.append("resource.counter.opacity=").append(RESOURCE_COUNTER_OPACITY).append("\n\n");
    }
}
