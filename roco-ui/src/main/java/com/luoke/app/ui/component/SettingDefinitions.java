package com.luoke.app.ui.component;

import com.luoke.app.config.AppConfig;
import com.luoke.app.context.CameraContext;
import com.luoke.app.macher.map.SwitchMapMatcher;
import com.luoke.app.ui.service.ThemeManager;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.function.Supplier;

/**
 * 设置分类定义 — 集中管理所有配置项的元数据定义。
 * 提供 builder 方法（cat / bool / integer / ...）和预构建的分类列表。
 */
@Slf4j
public final class SettingDefinitions {

    /**
     * 所有配置分类
     */
    public static final List<SettingCategory> CATEGORIES = buildCategories();

    private SettingDefinitions() {
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

    private static SettingDef bool(String key, String label) {
        return new SettingDef(key, label, SettingType.BOOLEAN, null, null, false);
    }

    private static SettingDef bool(String key, String label, Runnable onApply) {
        return new SettingDef(key, label, SettingType.BOOLEAN, null, onApply, false);
    }

    private static SettingDef bool(String key, String label, boolean restart) {
        return new SettingDef(key, label, SettingType.BOOLEAN, null, null, restart);
    }

    private static SettingDef integer(String key, String label) {
        return new SettingDef(key, label, SettingType.INTEGER, null, null, false);
    }

    private static SettingDef integer(String key, String label, boolean restart) {
        return new SettingDef(key, label, SettingType.INTEGER, null, null, restart);
    }

    private static SettingDef integer(String key, String label, Runnable onApply) {
        return new SettingDef(key, label, SettingType.INTEGER, null, onApply, false);
    }

    private static SettingDef long_(String key, String label) {
        return new SettingDef(key, label, SettingType.LONG, null, null, false);
    }

    private static SettingDef long_(String key, String label, boolean restart) {
        return new SettingDef(key, label, SettingType.LONG, null, null, restart);
    }

    private static SettingDef long_(String key, String label, Runnable onApply) {
        return new SettingDef(key, label, SettingType.LONG, null, onApply, false);
    }

    private static SettingDef doub(String key, String label) {
        return new SettingDef(key, label, SettingType.DOUBLE, null, null, false);
    }

    private static SettingDef doub(String key, String label, boolean restart) {
        return new SettingDef(key, label, SettingType.DOUBLE, null, null, restart);
    }

    private static SettingDef doub(String key, String label, Runnable onApply) {
        return new SettingDef(key, label, SettingType.DOUBLE, null, onApply, false);
    }

    private static SettingDef str(String key, String label) {
        return new SettingDef(key, label, SettingType.STRING, null, null, false);
    }

    private static SettingDef str(String key, String label, boolean restart) {
        return new SettingDef(key, label, SettingType.STRING, null, null, restart);
    }

    private static SettingDef str(String key, String label, Runnable onApply) {
        return new SettingDef(key, label, SettingType.STRING, null, onApply, false);
    }

    private static SettingDef combo(String key, String label, Supplier<String[]> options, Runnable onApply) {
        return new SettingDef(key, label, SettingType.COMBO, options, onApply, false);
    }

    // ================================================================
    // 分类定义
    // ================================================================

    private static List<SettingCategory> buildCategories() {
        return List.of(
                cat("资源", "/icon/resources.svg",
                        bool("INTERNAL_RESOURCE", "使用内置资源", true),
                        integer("TARGET_CAPTURE_FPS", "目标帧率", true),
                        bool("MATERIAL_COLLECTION", "物资采集统计"),
                        str("TARGET_WINDOW_NAME", "窗口标题", true),
                        str("MAP_RESOURCE_INFO_URL", "地图信息URL"),
                        str("MAP_RESOURCE_POINT_URL", "资源点数据URL")
                ),
                cat("UI", "/icon/theme.svg",
                        combo("THEME", "主题",
                                ThemeManager::getAvailableThemes,
                                () -> ThemeManager.switchTheme(AppConfig.THEME)),
                        integer("UI_FONT_SIZE", "基础字号"),
                        bool("DEFAULT_FOLLOW_MODE", "默认跟随模式",
                                () -> CameraContext.getInstance().setFollowMode(AppConfig.DEFAULT_FOLLOW_MODE)),
                        doub("DEFAULT_FOLLOW_SCALE", "跟随缩放值",
                                () -> CameraContext.getInstance().setFollowScale(AppConfig.DEFAULT_FOLLOW_SCALE)),
                        integer("MAP_ZOOM", "瓦片缩放级别", true),
                        doub("MAP_VIEW_MAX_SCALE", "最大视觉缩放"),
                        doub("COORDINATE_SMOOTH_FACTOR", "坐标平滑系数"),
                        integer("RESIZE_MARGIN", "窗口拖拽感应区"),
                        doub("MIN_WINDOW_WIDTH", "最小窗口宽度"),
                        doub("MIN_WINDOW_HEIGHT", "最小窗口高度"),
                        doub("INITIAL_WINDOW_WIDTH", "初始窗口宽度"),
                        doub("INITIAL_WINDOW_HEIGHT", "初始窗口高度"),
                        doub("INTERACTIVE_ZOOM_FACTOR", "滚轮缩放因子"),
                        doub("HOVER_DETECT_RADIUS", "悬停检测半径"),
                        doub("NODE_CLICK_THRESHOLD", "节点点击半径"),
                        doub("SIDEBAR_WIDTH", "侧边栏宽度"),
                        doub("SIDEBAR_LIST_WIDTH", "侧边栏列表宽度"),
                        str("STATS_FONT_NAME", "统计字体名称"),
                        integer("STATS_FONT_SIZE", "统计字号"),
                        integer("STATS_PADDING", "统计面板边距"),
                        doub("RESOURCE_COUNTER_WIDTH", "物资面板宽度"),
                        doub("RESOURCE_COUNTER_OPACITY", "物资面板透明度"),
                        doub("TOAST_MAX_WIDTH", "Toast最大宽度"),
                        doub("TOAST_MAX_HEIGHT", "Toast最大高度"),
                        integer("WIKI_ITEM_HEIGHT", "WIKI条目高度")
                ),
                cat("渲染", "/icon/render.svg",
                        integer("SCALE_STABLE_THRESHOLD", "缩放稳定帧数"),
                        doub("TILE_BUFFER_MULTIPLIER", "预加载缓冲区"),
                        doub("ROUTE_INACTIVE_WIDTH", "非活跃路线宽度"),
                        doub("ROUTE_ACTIVE_WIDTH", "活跃路线宽度"),
                        doub("ROUTE_NODE_RADIUS", "节点锚点半径"),
                        doub("HOVER_ICON_SIZE", "Hover高亮尺寸"),
                        str("HOVER_GLOW_COLOR", "Hover发光色"),
                        doub("GRAY_CHECK_THRESHOLD", "变灰重检测阈值")
                ),
                cat("动效", "/icon/motion.svg",
                        long_("RENDER_FRAME_INTERVAL_MS", "渲染帧间隔(ms)", true),
                        integer("TOAST_FADE_IN_MS", "Toast滑入时长(ms)"),
                        integer("TOAST_FADE_OUT_MS", "Toast滑出时长(ms)"),
                        integer("TOAST_DISPLAY_SEC", "Toast停留时长(s)"),
                        integer("SIDEBAR_ANIM_MS", "侧边栏动画时长(ms)"),
                        integer("PANEL_FADE_MS", "面板淡入淡出(ms)")
                ),
                cat("玩家", "/icon/player.svg",
                        doub("PLAYER_IMG_SIZE", "玩家图标尺寸"),
                        doub("PLAYER_VIEW_SIZE", "玩家显示尺寸"),
                        doub("PLAYER_DOT_RADIUS", "回退圆点半径"),
                        integer("RIPPLE_COUNT", "波纹圈数"),
                        doub("RIPPLE_STEP", "波纹进度增量"),
                        doub("RIPPLE_STROKE_WIDTH", "波纹描边宽度"),
                        doub("RIPPLE_ALPHA", "波纹初始透明度"),
                        doub("HALO_BREATHE_FREQ", "光环呼吸频率"),
                        doub("HALO_BREATHE_MIN_ALPHA", "光环最小透明度"),
                        doub("HALO_BREATHE_MAX_ALPHA", "光环最大透明度"),
                        doub("HALO_STROKE_WIDTH", "光环描边宽度"),
                        doub("GRAY_DISTANCE", "光环检测半径")
                ),
                cat("玩家追踪", "/icon/follow.svg",
                        doub("PLAYER_EMA_ALPHA", "位置平滑因子"),
                        doub("PLAYER_TELEPORT_THRESHOLD", "瞬移检测阈值"),
                        doub("PLAYER_VELOCITY_EMA_ALPHA", "速度平滑因子"),
                        integer("PLAYER_MAP_LOST_THRESHOLD", "地图丢失阈值")
                ),
                cat("匹配", "/icon/match.svg",
                        combo("MAP_MATCHAER", "匹配器类型",
                                () -> {
                                    try {
                                        return SwitchMapMatcher.getInstance().getMatchers().toArray(new String[0]);
                                    } catch (Exception e) {
                                        return new String[]{"SIFT", "SIFT-PCA", "SIFT-ULTRA", "SIFT-PCA-ULTRA"};
                                    }
                                },
                                () -> {
                                    try {
                                        SwitchMapMatcher.getInstance().switchMapMatcher(AppConfig.MAP_MATCHAER);
                                    } catch (Exception e) {
                                        log.warn("切换匹配器失败", e);
                                    }
                                }),
                        doub("SCALE_FACTOR", "缩放因子"),
                        integer("SIFT_N_FEATURES", "SIFT最大特征数", true),
                        integer("SIFT_N_OCTAVE_LAYERS", "SIFT每层组数", true),
                        doub("SIFT_CONTRAST_THRESHOLD", "SIFT对比度阈值", true),
                        doub("SIFT_EDGE_THRESHOLD", "SIFT边缘阈值", true),
                        doub("SIFT_SIGMA", "SIFT sigma", true),
                        integer("FLANN_KD_TREES", "FLANN KD树数", true),
                        integer("FLANN_SEARCH_CHECKS", "FLANN搜索检查数", true),
                        doub("MATCH_RATIO_THRESHOLD", "比率测试阈值", true),
                        integer("MATCH_MIN_COUNT", "最小匹配点数", true),
                        integer("SEARCH_RADIUS", "搜索半径(px)"),
                        doub("RANSAC_REPROJ_THRESHOLD", "RANSAC误差阈值", true),
                        integer("RANSAC_MAX_ITERS", "RANSAC迭代次数", true),
                        doub("RANSAC_CONFIDENCE", "RANSAC置信度", true),
                        integer("ROI_MAP_X", "小地图ROI X(万分比)", true),
                        integer("ROI_MAP_Y", "小地图ROI Y(万分比)", true),
                        integer("ROI_MAP_W", "小地图ROI宽度(万分比)", true),
                        integer("ROI_MAP_H", "小地图ROI高度(万分比)", true),
                        long_("MATCH_TIMEOUT_MS", "匹配超时(ms)"),
                        integer("ARROW_CROP_SIZE", "箭头CNN裁剪尺寸", true),
                        integer("SIFT_TILE_SIZE", "训练瓦片尺寸(px)", true),
                        integer("SIFT_TILE_OVERLAP", "训练瓦片重叠(px)", true),
                        long_("SIFT_LARGE_MAP_THRESHOLD", "分块地图阈值(px²)", true)
                ),
                cat("小地图", "/icon/minimap.svg",
                        integer("MM_SMALL_WIDTH", "缩小检测宽度(px)"),
                        doub("MM_BLACK_RATIO_THRESHOLD", "黑边比例阈值"),
                        doub("MM_CENTER_OFFSET_RATIO", "圆心偏移阈值"),
                        integer("MM_MEDIAN_BLUR_KERNEL", "中值滤波核"),
                        doub("MM_HOUGH_DP", "Hough dp"),
                        doub("MM_HOUGH_PARAM1", "Hough param1"),
                        doub("MM_HOUGH_PARAM2", "Hough param2"),
                        integer("MM_BLACK_PIXEL_THRESHOLD", "黑边灰度阈值"),
                        integer("MM_EDGE_SAMPLE_COUNT", "边缘采样点数"),
                        doub("MM_EDGE_SAMPLE_STEP", "边缘采样步长(°)")
                ),
                cat("OCR", "/icon/ocr.svg",
                        integer("OCR_CORE_SIZE", "并发信号量", true),
                        long_("OCR_SCAN_INTERVAL", "扫描间隔(ms)", true),
                        integer("OCR_STABILITY_THRESHOLD", "稳定判定次数"),
                        integer("OCR_THREAD_POOL_SIZE", "线程池大小", true),
                        integer("OCR_TASK_QUEUE_CAPACITY", "任务队列容量", true),
                        long_("OCR_TASK_TIMEOUT_MS", "任务超时(ms)", true),
                        integer("ROI_OCR_X", "OCR ROI X(万分比)", true),
                        integer("ROI_OCR_Y", "OCR ROI Y(万分比)", true),
                        integer("ROI_OCR_W", "OCR ROI宽度(万分比)", true),
                        integer("ROI_OCR_H", "OCR ROI高度(万分比)", true),
                        integer("OCR_REC_STD_HEIGHT", "识别标准高度(px)", true),
                        doub("OCR_TEXT_HEAT_THRESHOLD", "文本热度阈值"),
                        integer("OCR_EXPAND_Y", "文本垂直扩展(px)"),
                        integer("OCR_DET_ALIGNMENT", "检测对齐值", true),
                        integer("OCR_REC_WIDTH_ALIGNMENT", "识别宽度对齐", true),
                        integer("OCR_BINARY_THRESHOLD", "二值化阈值"),
                        integer("OCR_MIN_RECT_HEIGHT", "文本最小高度(px)"),
                        integer("OCR_NAME_MIN_LENGTH", "名称最小长度")
                ),
                cat("统计", "/icon/statistics.svg",
                        bool("SHOW_STATS_MATCH_TIME", "显示匹配耗时"),
                        bool("SHOW_STATS_DIR_TIME", "显示朝向耗时"),
                        bool("SHOW_STATS_FPS", "显示FPS"),
                        integer("STATS_FPS_WINDOW_MS", "FPS计算窗口(ms)"),
                        integer("GRID_CELL_SIZE", "网格单元尺寸")
                ),
                cat("下载", "/icon/download.svg",
                        integer("DOWNLOAD_CONNECT_TIMEOUT", "连接超时(ms)"),
                        integer("DOWNLOAD_READ_TIMEOUT", "读取超时(ms)"),
                        integer("DOWNLOAD_MAX_RETRY", "最大重试次数"),
                        integer("DOWNLOAD_THREAD_COUNT", "并发线程数"),
                        long_("DOWNLOAD_TILE_DELAY_MS", "瓦片间隔(ms)"),
                        long_("DOWNLOAD_ICON_DELAY_MS", "图标间隔(ms)"),
                        integer("DOWNLOAD_CHUNK_SIZE", "分块批次大小")
                ),
                cat("捕获", "/icon/capture.svg",
                        integer("CAPTURE_BLACK_SAMPLE_SIZE", "黑帧采样字节", true),
                        integer("CAPTURE_STATS_INTERVAL", "帧率统计间隔(ms)", true),
                        integer("CAPTURE_PROCESS_SHUTDOWN_WAIT", "进程等待(s)", true),
                        integer("MAX_BLACK_FRAMES", "最大连续黑帧", true),
                        bool("SHOW_MONITOR_BORDER", "录制区域边框")
                ),
                cat("进程", "/icon/processor.svg",
                        integer("SOCKET_BACKLOG", "Socket待处理队列", true),
                        integer("SOCKET_ACCEPT_JOIN_TIMEOUT", "Accept超时(ms)", true),
                        long_("SIFT_RESTART_MIN_INTERVAL", "最小重启间隔(ms)", true),
                        long_("SIFT_RESTART_DELAY", "重启延迟(ms)", true),
                        integer("SIFT_PROCESS_STOP_TIMEOUT", "进程停止超时(s)", true)
                )
        );
    }
}
