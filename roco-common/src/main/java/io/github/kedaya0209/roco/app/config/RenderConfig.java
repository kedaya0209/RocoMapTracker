package io.github.kedaya0209.roco.app.config;

import net.jcip.annotations.NotThreadSafe;
import java.util.Properties;

/**
 * 渲染与动效配置持久化 
 */
@NotThreadSafe
public final class RenderConfig {

    private RenderConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    // ============================================================
    // 字段声明
    // ============================================================
    /** 资源点图标视口/缓存尺寸（像素） */
    public static double ICON_SIZE = 32;
    /** 瓦片基础尺寸（像素） */
    public static int TILE_SIZE = 256;
    /** 缩放稳定所需帧数（约 165ms） */
    public static int SCALE_STABLE_THRESHOLD = 5;
    /** 瓦片视口外预加载缓冲区倍数 */
    public static double TILE_BUFFER_MULTIPLIER = 1.5;
    /** 玩家图标绘制尺寸（像素） */
    public static double PLAYER_IMG_SIZE = 72;
    /** 玩家图标显示尺寸（ImageView） */
    public static double PLAYER_VIEW_SIZE = 36;
    /** 非活跃路线描边宽度 */
    public static double ROUTE_INACTIVE_WIDTH = 2.0;
    /** 活跃路线描边宽度 */
    public static double ROUTE_ACTIVE_WIDTH = 3.0;
    /** 路径节点锚点半径 */
    public static double ROUTE_NODE_RADIUS = 4.5;
    /** 绘制模式预览虚线长度 */
    public static double ROUTE_DASH_LENGTH = 5.0;
    /** Hover 高亮图标尺寸 */
    public static double HOVER_ICON_SIZE = 38;
    /** Hover 外发光高亮色 */
    public static String HOVER_GLOW_COLOR = "#00BFFF";
    /** 变灰重检测玩家移动阈值（世界像素） */
    public static double GRAY_CHECK_THRESHOLD = 10;
    /** 渲染循环帧间隔（毫秒），约 30 FPS */
    public static long RENDER_FRAME_INTERVAL_MS = 33;
    /** 波纹圈数 */
    public static int RIPPLE_COUNT = 3;
    /** 每帧波纹进度增量 */
    public static double RIPPLE_STEP = 0.008;
    /** 波纹描边宽度 */
    public static double RIPPLE_STROKE_WIDTH = 1.5;
    /** 波纹初始透明度 */
    public static double RIPPLE_ALPHA = 0.35;
    /** 光环呼吸频率系数 */
    public static double HALO_BREATHE_FREQ = 0.03;
    /** 光环呼吸透明度最小值 */
    public static double HALO_BREATHE_MIN_ALPHA = 0.08;
    /** 光环呼吸透明度最大值 */
    public static double HALO_BREATHE_MAX_ALPHA = 0.20;
    /** 光环描边宽度 */
    public static double HALO_STROKE_WIDTH = 1.0;
    /** 滑入动画时长（毫秒） */
    public static int TOAST_FADE_IN_MS = 400;
    /** 滑出动画时长（毫秒） */
    public static int TOAST_FADE_OUT_MS = 400;
    /** 显示停顿时长（秒） */
    public static int TOAST_DISPLAY_SEC = 3;
    /** 侧边栏滑入/滑出动画时长（毫秒） */
    public static int SIDEBAR_ANIM_MS = 250;
    /** 洞穴模式下大陆瓦片背景透明度 */
    public static double CAVE_MAINLAND_OPACITY = 0.3;

    public static void load(Properties prop) {
        ICON_SIZE = ConfigHelper.getDouble(prop, "icon.size", ICON_SIZE);
        TILE_SIZE = ConfigHelper.getInt(prop, "tile.size", TILE_SIZE);
        SCALE_STABLE_THRESHOLD = ConfigHelper.getInt(prop, "scale.stable.threshold", SCALE_STABLE_THRESHOLD);
        TILE_BUFFER_MULTIPLIER = ConfigHelper.getDouble(prop, "tile.buffer.multiplier", TILE_BUFFER_MULTIPLIER);
        PLAYER_IMG_SIZE = ConfigHelper.getDouble(prop, "player.img.size", PLAYER_IMG_SIZE);
        PLAYER_VIEW_SIZE = ConfigHelper.getDouble(prop, "player.view.size", PLAYER_VIEW_SIZE);
        GRAY_CHECK_THRESHOLD = ConfigHelper.getDouble(prop, "gray.check.threshold", GRAY_CHECK_THRESHOLD);
        HOVER_ICON_SIZE = ConfigHelper.getDouble(prop, "hover.icon.size", HOVER_ICON_SIZE);
        HOVER_GLOW_COLOR = ConfigHelper.getStr(prop, "hover.glow.color", HOVER_GLOW_COLOR);
        ROUTE_INACTIVE_WIDTH = ConfigHelper.getDouble(prop, "route.inactive.width", ROUTE_INACTIVE_WIDTH);
        ROUTE_ACTIVE_WIDTH = ConfigHelper.getDouble(prop, "route.active.width", ROUTE_ACTIVE_WIDTH);
        ROUTE_NODE_RADIUS = ConfigHelper.getDouble(prop, "route.node.radius", ROUTE_NODE_RADIUS);
        RIPPLE_COUNT = ConfigHelper.getInt(prop, "ripple.count", RIPPLE_COUNT);
        RIPPLE_STEP = ConfigHelper.getDouble(prop, "ripple.step", RIPPLE_STEP);
        HALO_BREATHE_FREQ = ConfigHelper.getDouble(prop, "halo.breathe.freq", HALO_BREATHE_FREQ);
        TOAST_DISPLAY_SEC = ConfigHelper.getInt(prop, "toast.display.sec", TOAST_DISPLAY_SEC);
        TOAST_FADE_IN_MS = ConfigHelper.getInt(prop, "toast.fade.in.ms", TOAST_FADE_IN_MS);
        TOAST_FADE_OUT_MS = ConfigHelper.getInt(prop, "toast.fade.out.ms", TOAST_FADE_OUT_MS);
        SIDEBAR_ANIM_MS = ConfigHelper.getInt(prop, "sidebar.anim.ms", SIDEBAR_ANIM_MS);
        CAVE_MAINLAND_OPACITY = ConfigHelper.getDouble(prop, "cave.mainland.opacity", CAVE_MAINLAND_OPACITY);
    }

    public static void save(StringBuilder sb) {
        sb.append("# 资源点图标视口/缓存尺寸（像素）\n");
        sb.append("icon.size=").append(ICON_SIZE).append("\n");
        sb.append("# 瓦片基础尺寸（像素）\n");
        sb.append("tile.size=").append(TILE_SIZE).append("\n");
        sb.append("# 缩放稳定所需帧数\n");
        sb.append("scale.stable.threshold=").append(SCALE_STABLE_THRESHOLD).append("\n");
        sb.append("# 瓦片视口外预加载缓冲区倍数\n");
        sb.append("tile.buffer.multiplier=").append(TILE_BUFFER_MULTIPLIER).append("\n");
        sb.append("# 玩家图标绘制尺寸（像素）\n");
        sb.append("player.img.size=").append(PLAYER_IMG_SIZE).append("\n");
        sb.append("# 玩家图标显示尺寸（ImageView）\n");
        sb.append("player.view.size=").append(PLAYER_VIEW_SIZE).append("\n");
        sb.append("# 变灰重检测玩家移动阈值（世界像素）\n");
        sb.append("gray.check.threshold=").append(GRAY_CHECK_THRESHOLD).append("\n");
        sb.append("# Hover 高亮图标尺寸\n");
        sb.append("hover.icon.size=").append(HOVER_ICON_SIZE).append("\n");
        sb.append("# Hover 外发光高亮色\n");
        sb.append("hover.glow.color=").append(HOVER_GLOW_COLOR).append("\n");
        sb.append("# 非活跃路线描边宽度\n");
        sb.append("route.inactive.width=").append(ROUTE_INACTIVE_WIDTH).append("\n");
        sb.append("# 活跃路线描边宽度\n");
        sb.append("route.active.width=").append(ROUTE_ACTIVE_WIDTH).append("\n");
        sb.append("# 路径节点锚点半径\n");
        sb.append("route.node.radius=").append(ROUTE_NODE_RADIUS).append("\n");
        sb.append("# 波纹圈数\n");
        sb.append("ripple.count=").append(RIPPLE_COUNT).append("\n");
        sb.append("# 每帧波纹进度增量\n");
        sb.append("ripple.step=").append(RIPPLE_STEP).append("\n");
        sb.append("# 光环呼吸频率系数\n");
        sb.append("halo.breathe.freq=").append(HALO_BREATHE_FREQ).append("\n");
        sb.append("# Toast 显示停顿时长（秒）\n");
        sb.append("toast.display.sec=").append(TOAST_DISPLAY_SEC).append("\n");
        sb.append("# 侧边栏滑入/滑出动画时长（毫秒）\n");
        sb.append("sidebar.anim.ms=").append(SIDEBAR_ANIM_MS).append("\n");
        sb.append("# 洞穴模式下大陆瓦片背景透明度\n");
        sb.append("cave.mainland.opacity=").append(CAVE_MAINLAND_OPACITY).append("\n\n");
    }
}
