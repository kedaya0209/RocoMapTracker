package com.luoke.app.ui.component.setting;

import net.jcip.annotations.NotThreadSafe;
import com.luoke.app.config.ConfigPersistence;
import com.luoke.app.config.RenderConfig;
import com.luoke.app.config.SocketConfig;
import com.luoke.app.config.UiConfig;
import com.luoke.app.config.CaptureConfig;
import com.luoke.app.config.PlayerConfig;
import com.luoke.app.config.StatsConfig;
import com.luoke.app.config.MiniMapConfig;
import com.luoke.app.config.ViewConfig;
import com.luoke.app.config.DownloadConfig;
import com.luoke.app.config.OcrConfig;
import com.luoke.app.config.SiftConfig;
import com.luoke.app.config.NavigConfig;
import com.luoke.app.config.UpdateConfig;
import com.luoke.app.config.BuildConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.ui.component.TitleBar;
import com.luoke.app.context.CameraContext;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.ui.component.RouteManagerStage;
import com.luoke.app.ui.service.ThemeManager;
import com.luoke.app.update.UpdateManager;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 设置分类定义 — 集中管理所有配置项的元数据定义。
 * 提供 builder 方法（cat / bool / integer / ...）和预构建的分类列表。
 */
@NotThreadSafe
@Slf4j
public final class SettingDefinitions {

    /**
     * 所有配置分类
     */
    public static final List<SettingCategory> CATEGORIES = buildCategories();

    private SettingDefinitions() {
    }

    /**
     * 获取 TitleBar 实例（可能为 null）
     */
    private static TitleBar getTitleBar() {
        try {
            return TitleBar.getInstance();
        } catch (Exception e) {
            return null;
        }
    }

    // ================================================================
    // 查找
    // ================================================================

    /**
     * 根据 key 查找对应的设置定义
     */
    public static SettingDef findDef(String key) {
        for (SettingCategory cat : CATEGORIES) {
            for (SettingDef def : cat.fields()) {
                if (def.key().equals(key)) return def;
            }
        }
        return null;
    }

    // ================================================================
    // Builder 辅助方法
    // ================================================================

    private static SettingCategory cat(String name, String icon, SettingDef... fields) {
        return new SettingCategory(name, icon, List.of(fields));
    }

    private static SettingDef bool(String key, String label,
                                   Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.BOOLEAN, null, null, false, getter, setter);
    }

    private static SettingDef bool(String key, String label, Runnable onApply,
                                   Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.BOOLEAN, null, onApply, false, getter, setter);
    }

    private static SettingDef bool(String key, String label, boolean restart,
                                   Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.BOOLEAN, null, null, restart, getter, setter);
    }

    private static SettingDef integer(String key, String label,
                                      Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.INTEGER, null, null, false, getter, setter);
    }

    private static SettingDef integer(String key, String label, boolean restart,
                                      Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.INTEGER, null, null, restart, getter, setter);
    }

    private static SettingDef integer(String key, String label, Runnable onApply,
                                      Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.INTEGER, null, onApply, false, getter, setter);
    }

    private static SettingDef long_(String key, String label,
                                    Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.LONG, null, null, false, getter, setter);
    }

    private static SettingDef long_(String key, String label, boolean restart,
                                    Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.LONG, null, null, restart, getter, setter);
    }

    private static SettingDef long_(String key, String label, Runnable onApply,
                                    Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.LONG, null, onApply, false, getter, setter);
    }

    private static SettingDef doub(String key, String label,
                                   Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.DOUBLE, null, null, false, getter, setter);
    }

    private static SettingDef doub(String key, String label, boolean restart,
                                   Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.DOUBLE, null, null, restart, getter, setter);
    }

    private static SettingDef doub(String key, String label, Runnable onApply,
                                   Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.DOUBLE, null, onApply, false, getter, setter);
    }

    private static SettingDef str(String key, String label,
                                  Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.STRING, null, null, false, getter, setter);
    }

    private static SettingDef str(String key, String label, boolean restart,
                                  Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.STRING, null, null, restart, getter, setter);
    }

    private static SettingDef str(String key, String label, Runnable onApply,
                                  Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.STRING, null, onApply, false, getter, setter);
    }

    private static SettingDef combo(String key, String label, Supplier<String[]> options, Runnable onApply,
                                    Supplier<Object> getter, Consumer<Object> setter) {
        return new SettingDef(key, label, SettingType.COMBO, options, onApply, false, getter, setter);
    }

    // ================================================================
    // 分类定义
    // ================================================================

    private static List<SettingCategory> buildCategories() {
        return List.of(
                cat("资源", "/icon/resources.svg",
                        bool("INTERNAL_RESOURCE", "使用内置资源", true,
                                () -> DownloadConfig.INTERNAL_RESOURCE,
                                v -> DownloadConfig.INTERNAL_RESOURCE = (Boolean) v),
                        integer("TARGET_CAPTURE_FPS", "目标帧率", true,
                                () -> CaptureConfig.TARGET_CAPTURE_FPS,
                                v -> CaptureConfig.TARGET_CAPTURE_FPS = (Integer) v),
                        str("MAP_RESOURCE_INFO_URL", "地图信息URL",
                                () -> DownloadConfig.MAP_RESOURCE_INFO_URL,
                                v -> DownloadConfig.MAP_RESOURCE_INFO_URL = (String) v),
                        str("MAP_RESOURCE_POINT_URL", "资源点数据URL",
                                () -> DownloadConfig.MAP_RESOURCE_POINT_URL,
                                v -> DownloadConfig.MAP_RESOURCE_POINT_URL = (String) v)
                ),
                cat("UI", "/icon/theme.svg",
                        combo("THEME", "主题",
                                ThemeManager::getAvailableThemes,
                                () -> ThemeManager.switchTheme(UiConfig.THEME),
                                () -> UiConfig.THEME,
                                v -> UiConfig.THEME = (String) v),
                        integer("UI_FONT_SIZE", "基础字号",
                                () -> UiConfig.UI_FONT_SIZE,
                                v -> UiConfig.UI_FONT_SIZE = (Integer) v),
                        integer("MAP_ZOOM", "瓦片缩放级别", true,
                                () -> ViewConfig.MAP_ZOOM,
                                v -> ViewConfig.MAP_ZOOM = (Integer) v),
                        doub("COORDINATE_SMOOTH_FACTOR", "坐标平滑系数",
                                () -> ViewConfig.COORDINATE_SMOOTH_FACTOR,
                                v -> ViewConfig.COORDINATE_SMOOTH_FACTOR = (Double) v),
                        doub("MIN_WINDOW_WIDTH", "最小窗口宽度",
                                () -> UiConfig.MIN_WINDOW_WIDTH,
                                v -> UiConfig.MIN_WINDOW_WIDTH = (Double) v),
                        doub("MIN_WINDOW_HEIGHT", "最小窗口高度",
                                () -> UiConfig.MIN_WINDOW_HEIGHT,
                                v -> UiConfig.MIN_WINDOW_HEIGHT = (Double) v),
                        doub("INITIAL_WINDOW_WIDTH", "初始窗口宽度",
                                () -> ViewConfig.INITIAL_WINDOW_WIDTH,
                                v -> ViewConfig.INITIAL_WINDOW_WIDTH = (Double) v),
                        doub("INITIAL_WINDOW_HEIGHT", "初始窗口高度",
                                () -> ViewConfig.INITIAL_WINDOW_HEIGHT,
                                v -> ViewConfig.INITIAL_WINDOW_HEIGHT = (Double) v),
                        doub("SIDEBAR_LIST_WIDTH", "侧边栏列表宽度",
                                () -> UiConfig.SIDEBAR_LIST_WIDTH,
                                v -> UiConfig.SIDEBAR_LIST_WIDTH = (Double) v),
                        doub("TOAST_MAX_WIDTH", "Toast最大宽度",
                                () -> UiConfig.TOAST_MAX_WIDTH,
                                v -> UiConfig.TOAST_MAX_WIDTH = (Double) v),
                        doub("TOAST_MAX_HEIGHT", "Toast最大高度",
                                () -> UiConfig.TOAST_MAX_HEIGHT,
                                v -> UiConfig.TOAST_MAX_HEIGHT = (Double) v),
                        integer("WIKI_ITEM_HEIGHT", "WIKI条目高度",
                                () -> UiConfig.WIKI_ITEM_HEIGHT,
                                v -> UiConfig.WIKI_ITEM_HEIGHT = (Integer) v)
                ),
                cat("交互", "/icon/interactive.svg",
                        doub("MAP_VIEW_MAX_SCALE", "最大视觉缩放",
                                () -> UiConfig.MAP_VIEW_MAX_SCALE,
                                v -> UiConfig.MAP_VIEW_MAX_SCALE = (Double) v),
                        integer("RESIZE_MARGIN", "窗口拖拽感应区",
                                () -> UiConfig.RESIZE_MARGIN,
                                v -> UiConfig.RESIZE_MARGIN = (Integer) v),
                        doub("INTERACTIVE_ZOOM_FACTOR", "滚轮缩放因子",
                                () -> UiConfig.INTERACTIVE_ZOOM_FACTOR,
                                v -> UiConfig.INTERACTIVE_ZOOM_FACTOR = (Double) v),
                        doub("HOVER_DETECT_RADIUS", "悬停检测半径",
                                () -> UiConfig.HOVER_DETECT_RADIUS,
                                v -> UiConfig.HOVER_DETECT_RADIUS = (Double) v)
                ),
                cat("渲染", "/icon/render.svg",
                        integer("SCALE_STABLE_THRESHOLD", "缩放稳定帧数",
                                () -> RenderConfig.SCALE_STABLE_THRESHOLD,
                                v -> RenderConfig.SCALE_STABLE_THRESHOLD = (Integer) v),
                        doub("TILE_BUFFER_MULTIPLIER", "预加载缓冲区",
                                () -> RenderConfig.TILE_BUFFER_MULTIPLIER,
                                v -> RenderConfig.TILE_BUFFER_MULTIPLIER = (Double) v),
                        doub("HOVER_ICON_SIZE", "Hover高亮尺寸",
                                () -> RenderConfig.HOVER_ICON_SIZE,
                                v -> RenderConfig.HOVER_ICON_SIZE = (Double) v),
                        str("HOVER_GLOW_COLOR", "Hover发光色",
                                () -> RenderConfig.HOVER_GLOW_COLOR,
                                v -> RenderConfig.HOVER_GLOW_COLOR = (String) v),
                        doub("GRAY_CHECK_THRESHOLD", "变灰重检测阈值",
                                () -> RenderConfig.GRAY_CHECK_THRESHOLD,
                                v -> RenderConfig.GRAY_CHECK_THRESHOLD = (Double) v)
                ),
                cat("动效", "/icon/motion.svg",
                        long_("RENDER_FRAME_INTERVAL_MS", "渲染帧间隔(ms)", true,
                                () -> RenderConfig.RENDER_FRAME_INTERVAL_MS,
                                v -> RenderConfig.RENDER_FRAME_INTERVAL_MS = (Long) v),
                        integer("TOAST_FADE_IN_MS", "Toast滑入时长(ms)",
                                () -> RenderConfig.TOAST_FADE_IN_MS,
                                v -> RenderConfig.TOAST_FADE_IN_MS = (Integer) v),
                        integer("TOAST_FADE_OUT_MS", "Toast滑出时长(ms)",
                                () -> RenderConfig.TOAST_FADE_OUT_MS,
                                v -> RenderConfig.TOAST_FADE_OUT_MS = (Integer) v),
                        integer("TOAST_DISPLAY_SEC", "Toast停留时长(s)",
                                () -> RenderConfig.TOAST_DISPLAY_SEC,
                                v -> RenderConfig.TOAST_DISPLAY_SEC = (Integer) v),
                        integer("SIDEBAR_ANIM_MS", "侧边栏动画时长(ms)",
                                () -> RenderConfig.SIDEBAR_ANIM_MS,
                                v -> RenderConfig.SIDEBAR_ANIM_MS = (Integer) v),
                        integer("PANEL_FADE_MS", "面板淡入淡出(ms)",
                                () -> RenderConfig.PANEL_FADE_MS,
                                v -> RenderConfig.PANEL_FADE_MS = (Integer) v)
                ),
                cat("路线管理", "/icon/route.svg",
                        new SettingDef("OPEN_ROUTE_MANAGER", "打开路线管理面板", SettingType.BUTTON,
                                null, () -> RouteManagerStage.getInstance().show(),
                                false, null, null),
                        doub("NODE_CLICK_THRESHOLD", "节点点击半径",
                                () -> ViewConfig.NODE_CLICK_THRESHOLD,
                                v -> ViewConfig.NODE_CLICK_THRESHOLD = (Double) v),
                        doub("ROUTE_INACTIVE_WIDTH", "非活跃路线宽度",
                                () -> RenderConfig.ROUTE_INACTIVE_WIDTH,
                                v -> RenderConfig.ROUTE_INACTIVE_WIDTH = (Double) v),
                        doub("ROUTE_ACTIVE_WIDTH", "活跃路线宽度",
                                () -> RenderConfig.ROUTE_ACTIVE_WIDTH,
                                v -> RenderConfig.ROUTE_ACTIVE_WIDTH = (Double) v),
                        doub("ROUTE_NODE_RADIUS", "节点锚点半径",
                                () -> RenderConfig.ROUTE_NODE_RADIUS,
                                v -> RenderConfig.ROUTE_NODE_RADIUS = (Double) v)
                ),
                cat("物资面板", "/icon/summary.svg",
                        bool("MATERIAL_COLLECTION", "物资采集统计",
                                () -> ViewConfig.MATERIAL_COLLECTION,
                                v -> ViewConfig.MATERIAL_COLLECTION = (Boolean) v),
                        doub("RESOURCE_COUNTER_WIDTH", "面板宽度",
                                () -> UiConfig.RESOURCE_COUNTER_WIDTH,
                                v -> UiConfig.RESOURCE_COUNTER_WIDTH = (Double) v),
                        doub("RESOURCE_COUNTER_OPACITY", "面板透明度",
                                () -> UiConfig.RESOURCE_COUNTER_OPACITY,
                                v -> UiConfig.RESOURCE_COUNTER_OPACITY = (Double) v)
                ),
                cat("视角跟随", "/icon/navigation.svg",
                        bool("NAVIGATION_ENABLED", "启用视角跟随",
                                () -> {
                                    boolean enabled = NavigConfig.NAVIGATION_ENABLED;
                                    CameraContext.getInstance().setNavMode(enabled);
                                    TitleBar titleBar = getTitleBar();
                                    if (titleBar != null) titleBar.setNavModeFromExternal(enabled);
                                    ConfigPersistence.save();
                                },
                                () -> NavigConfig.NAVIGATION_ENABLED,
                                v -> NavigConfig.NAVIGATION_ENABLED = (Boolean) v),
                        bool("AUTO_FOLLOW_MODE", "进入视角跟随时自动开启跟随",
                                ConfigPersistence::save,
                                () -> NavigConfig.AUTO_FOLLOW_MODE,
                                v -> NavigConfig.AUTO_FOLLOW_MODE = (Boolean) v),
                        doub("MAX_DEFLECTION_ANGLE", "最大偏转角度(度)",
                                () -> NavigConfig.MAX_DEFLECTION_ANGLE,
                                v -> NavigConfig.MAX_DEFLECTION_ANGLE = (Double) v),
                        long_("ROTATION_DELAY_MS", "地图旋转延迟(ms)",
                                () -> NavigConfig.ROTATION_DELAY_MS,
                                v -> NavigConfig.ROTATION_DELAY_MS = (Long) v),
                        long_("ROTATION_INTERVAL_MS", "旋转最小间隔(ms)",
                                () -> NavigConfig.ROTATION_INTERVAL_MS,
                                v -> NavigConfig.ROTATION_INTERVAL_MS = (Long) v),
                        doub("NAV_WINDOW_OPACITY", "窗口默认透明度",
                                () -> NavigConfig.NAV_WINDOW_OPACITY,
                                v -> NavigConfig.NAV_WINDOW_OPACITY = (Double) v),
                        doub("DEBOUNCE_THRESHOLD", "防抖阈值(度)",
                                () -> NavigConfig.DEBOUNCE_THRESHOLD,
                                v -> NavigConfig.DEBOUNCE_THRESHOLD = (Double) v)
                ),
                cat("玩家", "/icon/player.svg",
                        doub("PLAYER_IMG_SIZE", "玩家图标尺寸",
                                () -> RenderConfig.PLAYER_IMG_SIZE,
                                v -> RenderConfig.PLAYER_IMG_SIZE = (Double) v),
                        doub("PLAYER_VIEW_SIZE", "玩家显示尺寸",
                                () -> RenderConfig.PLAYER_VIEW_SIZE,
                                v -> RenderConfig.PLAYER_VIEW_SIZE = (Double) v),
                        doub("PLAYER_DOT_RADIUS", "回退圆点半径",
                                () -> RenderConfig.PLAYER_DOT_RADIUS,
                                v -> RenderConfig.PLAYER_DOT_RADIUS = (Double) v),
                        integer("RIPPLE_COUNT", "波纹圈数",
                                () -> RenderConfig.RIPPLE_COUNT,
                                v -> RenderConfig.RIPPLE_COUNT = (Integer) v),
                        doub("RIPPLE_STEP", "波纹进度增量",
                                () -> RenderConfig.RIPPLE_STEP,
                                v -> RenderConfig.RIPPLE_STEP = (Double) v),
                        doub("RIPPLE_STROKE_WIDTH", "波纹描边宽度",
                                () -> RenderConfig.RIPPLE_STROKE_WIDTH,
                                v -> RenderConfig.RIPPLE_STROKE_WIDTH = (Double) v),
                        doub("RIPPLE_ALPHA", "波纹初始透明度",
                                () -> RenderConfig.RIPPLE_ALPHA,
                                v -> RenderConfig.RIPPLE_ALPHA = (Double) v),
                        doub("HALO_BREATHE_FREQ", "光环呼吸频率",
                                () -> RenderConfig.HALO_BREATHE_FREQ,
                                v -> RenderConfig.HALO_BREATHE_FREQ = (Double) v),
                        doub("HALO_BREATHE_MIN_ALPHA", "光环最小透明度",
                                () -> RenderConfig.HALO_BREATHE_MIN_ALPHA,
                                v -> RenderConfig.HALO_BREATHE_MIN_ALPHA = (Double) v),
                        doub("HALO_BREATHE_MAX_ALPHA", "光环最大透明度",
                                () -> RenderConfig.HALO_BREATHE_MAX_ALPHA,
                                v -> RenderConfig.HALO_BREATHE_MAX_ALPHA = (Double) v),
                        doub("HALO_STROKE_WIDTH", "光环描边宽度",
                                () -> RenderConfig.HALO_STROKE_WIDTH,
                                v -> RenderConfig.HALO_STROKE_WIDTH = (Double) v),
                        doub("GRAY_DISTANCE", "光环检测半径",
                                () -> ViewConfig.GRAY_DISTANCE,
                                v -> ViewConfig.GRAY_DISTANCE = (Double) v)
                ),
                cat("玩家追踪", "/icon/follow.svg",
                        doub("PLAYER_EMA_ALPHA", "位置平滑因子",
                                () -> PlayerConfig.PLAYER_EMA_ALPHA,
                                v -> PlayerConfig.PLAYER_EMA_ALPHA = (Double) v),
                        doub("PLAYER_VELOCITY_EMA_ALPHA", "速度平滑因子",
                                () -> PlayerConfig.PLAYER_VELOCITY_EMA_ALPHA,
                                v -> PlayerConfig.PLAYER_VELOCITY_EMA_ALPHA = (Double) v),
                        bool("DEFAULT_FOLLOW_MODE", "默认跟踪",
                                () -> CameraContext.getInstance().setFollowMode(ViewConfig.DEFAULT_FOLLOW_MODE),
                                () -> ViewConfig.DEFAULT_FOLLOW_MODE,
                                v -> ViewConfig.DEFAULT_FOLLOW_MODE = (Boolean) v),
                        doub("DEFAULT_FOLLOW_SCALE", "地图缩放值",
                                () -> CameraContext.getInstance().setFollowScale(ViewConfig.DEFAULT_FOLLOW_SCALE),
                                () -> ViewConfig.DEFAULT_FOLLOW_SCALE,
                                v -> ViewConfig.DEFAULT_FOLLOW_SCALE = (Double) v)
                ),
                cat("匹配", "/icon/match.svg",
                        combo("MAP_MATCHAER", "匹配器类型",
                                () -> {
                                    try {
                                        return SwitchMapMatcher.getInstance().getMatchers().toArray(new String[0]);
                                    } catch (Exception e) { // SwitchMapMatcher 可能因原生库异常失败，保留宽泛捕获
                                        return new String[]{"SIFT", "SIFT-PCA", "SIFT-ULTRA", "SIFT-PCA-ULTRA"};
                                    }
                                },
                                () -> {
                                    try {
                                        SwitchMapMatcher.getInstance().switchMapMatcher(SiftConfig.MAP_MATCHAER);
                                    } catch (Exception e) {
                                        log.warn("切换匹配器失败", e);
                                    }
                                },
                                () -> SiftConfig.MAP_MATCHAER,
                                v -> SiftConfig.MAP_MATCHAER = (String) v),
                        doub("SCALE_FACTOR", "缩放因子",
                                () -> SiftConfig.SCALE_FACTOR,
                                v -> SiftConfig.SCALE_FACTOR = (Double) v),
                        integer("SIFT_N_FEATURES", "SIFT最大特征数", true,
                                () -> SiftConfig.SIFT_N_FEATURES,
                                v -> SiftConfig.SIFT_N_FEATURES = (Integer) v),
                        integer("SIFT_N_OCTAVE_LAYERS", "SIFT每层组数", true,
                                () -> SiftConfig.SIFT_N_OCTAVE_LAYERS,
                                v -> SiftConfig.SIFT_N_OCTAVE_LAYERS = (Integer) v),
                        doub("SIFT_CONTRAST_THRESHOLD", "SIFT对比度阈值", true,
                                () -> SiftConfig.SIFT_CONTRAST_THRESHOLD,
                                v -> SiftConfig.SIFT_CONTRAST_THRESHOLD = (Double) v),
                        doub("SIFT_EDGE_THRESHOLD", "SIFT边缘阈值", true,
                                () -> SiftConfig.SIFT_EDGE_THRESHOLD,
                                v -> SiftConfig.SIFT_EDGE_THRESHOLD = (Double) v),
                        doub("SIFT_SIGMA", "SIFT sigma", true,
                                () -> SiftConfig.SIFT_SIGMA,
                                v -> SiftConfig.SIFT_SIGMA = (Double) v),
                        integer("FLANN_KD_TREES", "FLANN KD树数", true,
                                () -> SiftConfig.FLANN_KD_TREES,
                                v -> SiftConfig.FLANN_KD_TREES = (Integer) v),
                        integer("FLANN_SEARCH_CHECKS", "FLANN搜索检查数", true,
                                () -> SiftConfig.FLANN_SEARCH_CHECKS,
                                v -> SiftConfig.FLANN_SEARCH_CHECKS = (Integer) v),
                        doub("MATCH_RATIO_THRESHOLD", "比率测试阈值", true,
                                () -> SiftConfig.MATCH_RATIO_THRESHOLD,
                                v -> SiftConfig.MATCH_RATIO_THRESHOLD = ((Number) v).floatValue()),
                        integer("MATCH_MIN_COUNT", "最小匹配点数", true,
                                () -> SiftConfig.MATCH_MIN_COUNT,
                                v -> SiftConfig.MATCH_MIN_COUNT = (Integer) v),
                        integer("SEARCH_RADIUS", "搜索半径(px)",
                                () -> SiftConfig.SEARCH_RADIUS,
                                v -> SiftConfig.SEARCH_RADIUS = (Integer) v),
                        doub("RANSAC_REPROJ_THRESHOLD", "RANSAC误差阈值", true,
                                () -> SiftConfig.RANSAC_REPROJ_THRESHOLD,
                                v -> SiftConfig.RANSAC_REPROJ_THRESHOLD = (Double) v),
                        integer("RANSAC_MAX_ITERS", "RANSAC迭代次数", true,
                                () -> SiftConfig.RANSAC_MAX_ITERS,
                                v -> SiftConfig.RANSAC_MAX_ITERS = (Integer) v),
                        doub("RANSAC_CONFIDENCE", "RANSAC置信度", true,
                                () -> SiftConfig.RANSAC_CONFIDENCE,
                                v -> SiftConfig.RANSAC_CONFIDENCE = (Double) v),
                        integer("ROI_MAP_X", "小地图ROI X(万分比)", true,
                                () -> SiftConfig.ROI_MAP_X,
                                v -> SiftConfig.ROI_MAP_X = (Integer) v),
                        integer("ROI_MAP_Y", "小地图ROI Y(万分比)", true,
                                () -> SiftConfig.ROI_MAP_Y,
                                v -> SiftConfig.ROI_MAP_Y = (Integer) v),
                        integer("ROI_MAP_W", "小地图ROI宽度(万分比)", true,
                                () -> SiftConfig.ROI_MAP_W,
                                v -> SiftConfig.ROI_MAP_W = (Integer) v),
                        integer("ROI_MAP_H", "小地图ROI高度(万分比)", true,
                                () -> SiftConfig.ROI_MAP_H,
                                v -> SiftConfig.ROI_MAP_H = (Integer) v),
                        long_("MATCH_TIMEOUT_MS", "匹配超时(ms)",
                                () -> SiftConfig.MATCH_TIMEOUT_MS,
                                v -> SiftConfig.MATCH_TIMEOUT_MS = (Long) v),
                        integer("ARROW_CROP_SIZE", "箭头CNN裁剪尺寸", true,
                                () -> SiftConfig.ARROW_CROP_SIZE,
                                v -> SiftConfig.ARROW_CROP_SIZE = (Integer) v),
                        integer("SIFT_TILE_SIZE", "训练瓦片尺寸(px)", true,
                                () -> SiftConfig.SIFT_TILE_SIZE,
                                v -> SiftConfig.SIFT_TILE_SIZE = (Integer) v),
                        integer("SIFT_TILE_OVERLAP", "训练瓦片重叠(px)", true,
                                () -> SiftConfig.SIFT_TILE_OVERLAP,
                                v -> SiftConfig.SIFT_TILE_OVERLAP = (Integer) v),
                        long_("SIFT_LARGE_MAP_THRESHOLD", "分块地图阈值(px²)", true,
                                () -> SiftConfig.SIFT_LARGE_MAP_THRESHOLD,
                                v -> SiftConfig.SIFT_LARGE_MAP_THRESHOLD = (Long) v)
                ),
                cat("小地图", "/icon/minimap.svg",
                        integer("MM_SMALL_WIDTH", "缩小检测宽度(px)",
                                () -> MiniMapConfig.MM_SMALL_WIDTH,
                                v -> MiniMapConfig.MM_SMALL_WIDTH = (Integer) v),
                        doub("MM_BLACK_RATIO_THRESHOLD", "黑边比例阈值",
                                () -> MiniMapConfig.MM_BLACK_RATIO_THRESHOLD,
                                v -> MiniMapConfig.MM_BLACK_RATIO_THRESHOLD = (Double) v),
                        doub("MM_CENTER_OFFSET_RATIO", "圆心偏移阈值",
                                () -> MiniMapConfig.MM_CENTER_OFFSET_RATIO,
                                v -> MiniMapConfig.MM_CENTER_OFFSET_RATIO = (Double) v),
                        integer("MM_MEDIAN_BLUR_KERNEL", "中值滤波核",
                                () -> MiniMapConfig.MM_MEDIAN_BLUR_KERNEL,
                                v -> MiniMapConfig.MM_MEDIAN_BLUR_KERNEL = (Integer) v),
                        doub("MM_HOUGH_DP", "Hough dp",
                                () -> MiniMapConfig.MM_HOUGH_DP,
                                v -> MiniMapConfig.MM_HOUGH_DP = (Double) v),
                        doub("MM_HOUGH_PARAM1", "Hough param1",
                                () -> MiniMapConfig.MM_HOUGH_PARAM1,
                                v -> MiniMapConfig.MM_HOUGH_PARAM1 = (Double) v),
                        doub("MM_HOUGH_PARAM2", "Hough param2",
                                () -> MiniMapConfig.MM_HOUGH_PARAM2,
                                v -> MiniMapConfig.MM_HOUGH_PARAM2 = (Double) v),
                        integer("MM_BLACK_PIXEL_THRESHOLD", "黑边灰度阈值",
                                () -> MiniMapConfig.MM_BLACK_PIXEL_THRESHOLD,
                                v -> MiniMapConfig.MM_BLACK_PIXEL_THRESHOLD = (Integer) v),
                        integer("MM_EDGE_SAMPLE_COUNT", "边缘采样点数",
                                () -> MiniMapConfig.MM_EDGE_SAMPLE_COUNT,
                                v -> MiniMapConfig.MM_EDGE_SAMPLE_COUNT = (Integer) v),
                        doub("MM_EDGE_SAMPLE_STEP", "边缘采样步长(°)",
                                () -> MiniMapConfig.MM_EDGE_SAMPLE_STEP,
                                v -> MiniMapConfig.MM_EDGE_SAMPLE_STEP = (Double) v)
                ),
                cat("OCR", "/icon/ocr.svg",
                        integer("OCR_CORE_SIZE", "并发信号量", true,
                                () -> OcrConfig.OCR_CORE_SIZE,
                                v -> OcrConfig.OCR_CORE_SIZE = (Integer) v),
                        long_("OCR_SCAN_INTERVAL", "扫描间隔(ms)", true,
                                () -> OcrConfig.OCR_SCAN_INTERVAL,
                                v -> OcrConfig.OCR_SCAN_INTERVAL = (Long) v),
                        integer("OCR_STABILITY_THRESHOLD", "稳定判定次数",
                                () -> OcrConfig.OCR_STABILITY_THRESHOLD,
                                v -> OcrConfig.OCR_STABILITY_THRESHOLD = (Integer) v),
                        integer("OCR_THREAD_POOL_SIZE", "线程池大小", true,
                                () -> OcrConfig.OCR_THREAD_POOL_SIZE,
                                v -> OcrConfig.OCR_THREAD_POOL_SIZE = (Integer) v),
                        integer("OCR_TASK_QUEUE_CAPACITY", "任务队列容量", true,
                                () -> OcrConfig.OCR_TASK_QUEUE_CAPACITY,
                                v -> OcrConfig.OCR_TASK_QUEUE_CAPACITY = (Integer) v),
                        long_("OCR_TASK_TIMEOUT_MS", "任务超时(ms)", true,
                                () -> OcrConfig.OCR_TASK_TIMEOUT_MS,
                                v -> OcrConfig.OCR_TASK_TIMEOUT_MS = (Long) v),
                        integer("ROI_OCR_X", "OCR ROI X(万分比)", true,
                                () -> OcrConfig.ROI_OCR_X,
                                v -> OcrConfig.ROI_OCR_X = (Integer) v),
                        integer("ROI_OCR_Y", "OCR ROI Y(万分比)", true,
                                () -> OcrConfig.ROI_OCR_Y,
                                v -> OcrConfig.ROI_OCR_Y = (Integer) v),
                        integer("ROI_OCR_W", "OCR ROI宽度(万分比)", true,
                                () -> OcrConfig.ROI_OCR_W,
                                v -> OcrConfig.ROI_OCR_W = (Integer) v),
                        integer("ROI_OCR_H", "OCR ROI高度(万分比)", true,
                                () -> OcrConfig.ROI_OCR_H,
                                v -> OcrConfig.ROI_OCR_H = (Integer) v),
                        integer("OCR_REC_STD_HEIGHT", "识别标准高度(px)", true,
                                () -> OcrConfig.OCR_REC_STD_HEIGHT,
                                v -> OcrConfig.OCR_REC_STD_HEIGHT = (Integer) v),
                        doub("OCR_TEXT_HEAT_THRESHOLD", "文本热度阈值",
                                () -> OcrConfig.OCR_TEXT_HEAT_THRESHOLD,
                                v -> OcrConfig.OCR_TEXT_HEAT_THRESHOLD = ((Number) v).floatValue()),
                        integer("OCR_EXPAND_Y", "文本垂直扩展(px)",
                                () -> OcrConfig.OCR_EXPAND_Y,
                                v -> OcrConfig.OCR_EXPAND_Y = (Integer) v),
                        integer("OCR_DET_ALIGNMENT", "检测对齐值", true,
                                () -> OcrConfig.OCR_DET_ALIGNMENT,
                                v -> OcrConfig.OCR_DET_ALIGNMENT = (Integer) v),
                        integer("OCR_REC_WIDTH_ALIGNMENT", "识别宽度对齐", true,
                                () -> OcrConfig.OCR_REC_WIDTH_ALIGNMENT,
                                v -> OcrConfig.OCR_REC_WIDTH_ALIGNMENT = (Integer) v),
                        integer("OCR_BINARY_THRESHOLD", "二值化阈值",
                                () -> OcrConfig.OCR_BINARY_THRESHOLD,
                                v -> OcrConfig.OCR_BINARY_THRESHOLD = (Integer) v),
                        integer("OCR_MIN_RECT_HEIGHT", "文本最小高度(px)",
                                () -> OcrConfig.OCR_MIN_RECT_HEIGHT,
                                v -> OcrConfig.OCR_MIN_RECT_HEIGHT = (Integer) v),
                        integer("OCR_NAME_MIN_LENGTH", "名称最小长度",
                                () -> OcrConfig.OCR_NAME_MIN_LENGTH,
                                v -> OcrConfig.OCR_NAME_MIN_LENGTH = (Integer) v)
                ),
                cat("统计面板", "/icon/statistics.svg",
                        bool("SHOW_STATS_MATCH_TIME", "显示匹配耗时（算法匹配总耗时）",
                                () -> StatsConfig.SHOW_STATS_MATCH_TIME,
                                v -> StatsConfig.SHOW_STATS_MATCH_TIME = (Boolean) v),
                        bool("SHOW_STATS_DIR_TIME", "显示朝向耗时（玩家角色朝向耗时）",
                                () -> StatsConfig.SHOW_STATS_DIR_TIME,
                                v -> StatsConfig.SHOW_STATS_DIR_TIME = (Boolean) v),
                        bool("SHOW_STATS_SIFT_MINIMAP_TIME", "显示小地图检测耗时（匹配耗时子项）",
                                () -> StatsConfig.SHOW_STATS_SIFT_MINIMAP_TIME,
                                v -> StatsConfig.SHOW_STATS_SIFT_MINIMAP_TIME = (Boolean) v),
                        bool("SHOW_STATS_SIFT_EXTRACT_TIME", "显示特征提取耗时（匹配耗时子项）",
                                () -> StatsConfig.SHOW_STATS_SIFT_EXTRACT_TIME,
                                v -> StatsConfig.SHOW_STATS_SIFT_EXTRACT_TIME = (Boolean) v),
                        bool("SHOW_STATS_SIFT_FLANN_TIME", "显示FLANN匹配耗时（匹配耗时子项）",
                                () -> StatsConfig.SHOW_STATS_SIFT_FLANN_TIME,
                                v -> StatsConfig.SHOW_STATS_SIFT_FLANN_TIME = (Boolean) v),
                        bool("SHOW_STATS_FPS", "显示FPS",
                                () -> StatsConfig.SHOW_STATS_FPS,
                                v -> StatsConfig.SHOW_STATS_FPS = (Boolean) v),
                        integer("STATS_FPS_WINDOW_MS", "FPS计算窗口(ms)",
                                () -> StatsConfig.STATS_FPS_WINDOW_MS,
                                v -> StatsConfig.STATS_FPS_WINDOW_MS = (Integer) v),
                        str("STATS_FONT_NAME", "统计字体名称",
                                () -> UiConfig.STATS_FONT_NAME,
                                v -> UiConfig.STATS_FONT_NAME = (String) v),
                        integer("STATS_FONT_SIZE", "统计字号",
                                () -> UiConfig.STATS_FONT_SIZE,
                                v -> UiConfig.STATS_FONT_SIZE = (Integer) v),
                        integer("STATS_PADDING", "统计面板边距",
                                () -> UiConfig.STATS_PADDING,
                                v -> UiConfig.STATS_PADDING = (Integer) v),
                        integer("GRID_CELL_SIZE", "网格单元尺寸",
                                () -> StatsConfig.GRID_CELL_SIZE,
                                v -> StatsConfig.GRID_CELL_SIZE = (Integer) v)
                ),
                cat("下载", "/icon/download.svg",
                        integer("DOWNLOAD_CONNECT_TIMEOUT", "连接超时(ms)",
                                () -> DownloadConfig.DOWNLOAD_CONNECT_TIMEOUT,
                                v -> DownloadConfig.DOWNLOAD_CONNECT_TIMEOUT = (Integer) v),
                        integer("DOWNLOAD_READ_TIMEOUT", "读取超时(ms)",
                                () -> DownloadConfig.DOWNLOAD_READ_TIMEOUT,
                                v -> DownloadConfig.DOWNLOAD_READ_TIMEOUT = (Integer) v),
                        integer("DOWNLOAD_MAX_RETRY", "最大重试次数",
                                () -> DownloadConfig.DOWNLOAD_MAX_RETRY,
                                v -> DownloadConfig.DOWNLOAD_MAX_RETRY = (Integer) v),
                        integer("DOWNLOAD_THREAD_COUNT", "并发线程数",
                                () -> DownloadConfig.DOWNLOAD_THREAD_COUNT,
                                v -> DownloadConfig.DOWNLOAD_THREAD_COUNT = (Integer) v),
                        long_("DOWNLOAD_TILE_DELAY_MS", "瓦片间隔(ms)",
                                () -> DownloadConfig.DOWNLOAD_TILE_DELAY_MS,
                                v -> DownloadConfig.DOWNLOAD_TILE_DELAY_MS = (Long) v),
                        long_("DOWNLOAD_ICON_DELAY_MS", "图标间隔(ms)",
                                () -> DownloadConfig.DOWNLOAD_ICON_DELAY_MS,
                                v -> DownloadConfig.DOWNLOAD_ICON_DELAY_MS = (Long) v),
                        integer("DOWNLOAD_CHUNK_SIZE", "分块批次大小",
                                () -> DownloadConfig.DOWNLOAD_CHUNK_SIZE,
                                v -> DownloadConfig.DOWNLOAD_CHUNK_SIZE = (Integer) v)
                ),
                cat("捕获", "/icon/capture.svg",
                        integer("CAPTURE_BLACK_SAMPLE_SIZE", "黑帧采样字节", false,
                                () -> CaptureConfig.CAPTURE_BLACK_SAMPLE_SIZE,
                                v -> CaptureConfig.CAPTURE_BLACK_SAMPLE_SIZE = (Integer) v),
                        integer("CAPTURE_STATS_INTERVAL", "帧率统计间隔(ms)", false,
                                () -> CaptureConfig.CAPTURE_STATS_INTERVAL,
                                v -> CaptureConfig.CAPTURE_STATS_INTERVAL = (Integer) v),
                        integer("CAPTURE_PROCESS_SHUTDOWN_WAIT", "进程等待(s)", false,
                                () -> CaptureConfig.CAPTURE_PROCESS_SHUTDOWN_WAIT,
                                v -> CaptureConfig.CAPTURE_PROCESS_SHUTDOWN_WAIT = (Integer) v),
                        integer("MAX_BLACK_FRAMES", "最大连续黑帧", false,
                                () -> CaptureConfig.MAX_BLACK_FRAMES,
                                v -> CaptureConfig.MAX_BLACK_FRAMES = (Integer) v),
                        str("TARGET_WINDOW_NAME", "窗口标题", true,
                                () -> CaptureConfig.TARGET_WINDOW_NAME,
                                v -> CaptureConfig.TARGET_WINDOW_NAME = (String) v)
                ),
                cat("进程", "/icon/processor.svg",
                        integer("SOCKET_BACKLOG", "Socket待处理队列", true,
                                () -> SocketConfig.SOCKET_BACKLOG,
                                v -> SocketConfig.SOCKET_BACKLOG = (Integer) v),
                        integer("SOCKET_ACCEPT_JOIN_TIMEOUT", "Accept超时(ms)", true,
                                () -> SocketConfig.SOCKET_ACCEPT_JOIN_TIMEOUT,
                                v -> SocketConfig.SOCKET_ACCEPT_JOIN_TIMEOUT = (Integer) v),
                        long_("SIFT_RESTART_MIN_INTERVAL", "最小重启间隔(ms)", true,
                                () -> SocketConfig.SIFT_RESTART_MIN_INTERVAL,
                                v -> SocketConfig.SIFT_RESTART_MIN_INTERVAL = (Long) v),
                        long_("SIFT_RESTART_DELAY", "重启延迟(ms)", true,
                                () -> SocketConfig.SIFT_RESTART_DELAY,
                                v -> SocketConfig.SIFT_RESTART_DELAY = (Long) v),
                        integer("SIFT_PROCESS_STOP_TIMEOUT", "进程停止超时(s)", true,
                                () -> SocketConfig.SIFT_PROCESS_STOP_TIMEOUT,
                                v -> SocketConfig.SIFT_PROCESS_STOP_TIMEOUT = (Integer) v)
                ),
                cat("更新", "/icon/update.svg",
                        bool("CHECK_ENABLED", "自动检查更新",
                                () -> UpdateConfig.CHECK_ENABLED,
                                v -> UpdateConfig.CHECK_ENABLED = (Boolean) v),
                        integer("CHECK_INTERVAL_HOURS", "检查间隔(小时)",
                                () -> UpdateConfig.CHECK_INTERVAL_HOURS,
                                v -> UpdateConfig.CHECK_INTERVAL_HOURS = (Integer) v),
                        bool("AUTO_DOWNLOAD", "发现更新时自动下载",
                                () -> UpdateConfig.AUTO_DOWNLOAD,
                                v -> UpdateConfig.AUTO_DOWNLOAD = (Boolean) v),
                        combo("DOWNLOAD_SOURCE", "下载源",
                                () -> new String[]{"github", "jsdelivr"},
                                null,
                                () -> UpdateConfig.DOWNLOAD_SOURCE,
                                v -> UpdateConfig.DOWNLOAD_SOURCE = (String) v),
                        new SettingDef("CHECK_NOW", "检查更新", SettingType.BUTTON,
                                null, () -> UpdateManager.getInstance().manualCheck(null),
                                false, null, null)
                ),
                cat("关于", "/icon/about.svg",
                        new SettingDef("ABOUT_VERSION", "当前版本", SettingType.STRING,
                                null, null, false,
                                () -> BuildConfig.APP_VERSION, null),
                        new SettingDef("ABOUT_BUILD_TIME", "构建时间", SettingType.STRING,
                                null, null, false,
                                () -> BuildConfig.BUILD_TIMESTAMP, null),
                        new SettingDef("ABOUT_REPO", "GitHub仓库", SettingType.STRING,
                                null, null, false,
                                () -> "https://github.com/kedaya0209/RocoMapTracker", null)
                )
        );
    }
}
