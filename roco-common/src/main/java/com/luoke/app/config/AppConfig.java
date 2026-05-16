package com.luoke.app.config;

import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * 应用程序配置管理类
 * * 逻辑说明：
 * 1. final 字段：系统常量（路径、模型名），硬编码在程序中，不持久化。
 * 2. 非 final 字段：用户参数（开关、阈值），支持运行时修改并持久化到 properties 文件。
 */
@Slf4j
public final class AppConfig {

    // ============================================================
    // 1. 静态资源常量 (Final) - 程序的物理路径，不可更改
    // ============================================================
    public static final String CAPTURE_EXE = "/capture/capture.exe";
    public static final String SIFT_MATCH_EXE = "/sift/sift_match.exe";
    public static final String SOURCE_INIT = "/source/init";
    public static final String MAP_RESOURCE_PATH = "/source/map/map_G.png";
    public static final String MAP_RESOURCE_DIR = "/source/map/";
    public static final String SHOW_MAP = "/source/map/WorldMap_Show.png";
    public static final String SIFT_MAP = "/source/map/WorldMap_SIFT.png";
    public static final String ICON_DIR = "/source/icon/";
    public static final String PLAYER_ICON_PATH = "/source/icon/player.png";
    public static final String RESOURCE_ICON_DIR = "/source/point/";
    public static final String RESOURCE_COLLECT_SET = "/source/point/collect_set.txt";
    public static final String RESOURCE_POINT_CONFIG_PATH = "/source/point/resource_config.json";
    public static final String INTERNAL_RESOURCE_POINT_CONFIG_PATH = "/source/point/internal_resource_point.json";

    public static final String MODEL_DIR = "/model/";
    public static final String OCR_REC_MODEL = "ch_PP-OCRv4_rec_mobile.onnx";
    public static final String OCR_DET_MODEL = "ch_PP-OCRv4_det_mobile.onnx";
    public static final String PPOCR_KEYS = "ppocr_keys_v1.txt";
    public static final String ARROW_MODEL_NAME = "arrow_fp32.onnx";
    public static final String PATHS = "/source/map_paths.json";
    public static final String INTERNAL_PATHS = "/source/internal_map_paths.json";
    public static final String ICON = "/icon/rmt.svg";
    public static final String GHOST = "/icon/ghost.svg";

    private static final String CONFIG_FILE_NAME = "app_config.properties";

    // ============================================================
    // 2. 动态配置变量 (非 Final) - 允许持久化
    // ============================================================
    // 资源与远程
    public static boolean INTERNAL_RESOURCE = false;
    public static String[] MAP_REMOTE_URLS = new String[0];
    public static String[] MAP_REMOTE_URL_NAME = new String[0];
    public static int[] MAP_REMOTE_URL_SORT = new int[0];
    public static String MAP_RESOURCE_INFO_URL = "https://wiki.biligame.com/rocom/大地图";
    public static String MAP_RESOURCE_POINT_URL = "https://wiki.biligame.com/rocom/Data:Mapnew/point.json";

    // 窗口与捕获
    public static String TARGET_WINDOW_NAME = "洛克王国：世界";
    public static String APP_MAIN_TITLE = "洛克王国地图";
    public static int TARGET_CAPTURE_FPS = 30;
    public static int MAX_BLACK_FRAMES = 30;
    public static boolean SHOW_MONITOR_BORDER = false;

    // UI与相机交互
    public static String THEME = "PrimerDark";
    public static int UI_FONT_SIZE = 14;
    public static boolean DEFAULT_FOLLOW_MODE = false;
    public static double DEFAULT_FOLLOW_SCALE = 1.5;
    public static int MAP_ZOOM = 7;
    public static int MAP_MIN_ZOOM = 4;
    public static int MAP_MAX_ZOOM = 8;
    public static int JSON_ZOOM = 7;
    public static double COORDINATE_SMOOTH_FACTOR = 0.8;
    public static double GRAY_DISTANCE = 10;
    public static boolean MATERIAL_COLLECTION = false;

    // 算法参数
    public static String MAP_MATCHAER = "SIFT-ULTRA";
    public static int OCR_CORE_SIZE = 1;
    public static double SCALE_FACTOR = 1.0;
    public static int SIFT_N_FEATURES = 0;
    public static int SIFT_N_OCTAVE_LAYERS = 3;
    public static double SIFT_CONTRAST_THRESHOLD = 0.001;
    public static double SIFT_EDGE_THRESHOLD = 50.0;
    public static double SIFT_SIGMA = 1.6;

    public static float MATCH_RATIO_THRESHOLD = 0.6f;
    public static int MATCH_MIN_COUNT = 10;
    public static double RANSAC_REPROJ_THRESHOLD = 10.0;
    public static int RANSAC_MAX_ITERS = 200;
    public static double RANSAC_CONFIDENCE = 0.95;
    public static int SEARCH_RADIUS = 500;

    public static boolean SHOW_STATS_MAP_TIME = true;
    public static boolean SHOW_STATS_CIRCLE_MASK = true;
    public static boolean SHOW_STATS_MATCH_TIME = true;
    public static boolean SHOW_STATS_DIR_TIME = true;
    public static boolean SHOW_STATS_FPS = true;

    static {
        loadConfig();
    }

    private AppConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    /**
     * 将当前内存中的配置持久化到磁盘
     * 只负责写非 final 的变量
     */
    public static void save() {
        try {
            File configFile = ResourceUtils.getExternalFile(CONFIG_FILE_NAME);
            StringBuilder sb = new StringBuilder();
            sb.append("# ==============================================\n");
            sb.append("# 洛克导航 - 用户配置文件 (UTF-8)\n");
            sb.append("# 注：路径等常量在代码中固定，此处仅存储可调参数\n");
            sb.append("# ==============================================\n\n");

            sb.append("# --- 基础设置 ---\n");
            sb.append("internal.resource=").append(INTERNAL_RESOURCE).append("\n");
            sb.append("target.window.name=").append(TARGET_WINDOW_NAME).append("\n");
            sb.append("target.capture.fps=").append(TARGET_CAPTURE_FPS).append("\n");
            sb.append("show.monitor.border=").append(SHOW_MONITOR_BORDER).append("\n\n");

            sb.append("# --- UI与交互 ---\n");
            sb.append("ui.theme=").append(THEME).append("\n");
            sb.append("ui.font.size=").append(UI_FONT_SIZE).append("\n");
            sb.append("map.zoom=").append(MAP_ZOOM).append("\n");
            sb.append("coordinate.smooth.factor=").append(COORDINATE_SMOOTH_FACTOR).append("\n");
            sb.append("gray.distance=").append(GRAY_DISTANCE).append("\n");

            sb.append("# --- 算法核心 ---\n");
            sb.append("map.matcher=").append(MAP_MATCHAER).append("\n");
            sb.append("scale.factor=").append(SCALE_FACTOR).append("\n");
            sb.append("sift.n.features=").append(SIFT_N_FEATURES).append("\n");
            sb.append("match.ratio.threshold=").append(MATCH_RATIO_THRESHOLD).append("\n");
            sb.append("ransac.max.iters=").append(RANSAC_MAX_ITERS).append("\n\n");

            sb.append("# --- 统计显示 ---\n");
            sb.append("show.stats.fps=").append(SHOW_STATS_FPS).append("\n");
            sb.append("show.stats.circle.mask=").append(SHOW_STATS_CIRCLE_MASK).append("\n");
            sb.append("show.stats.match.time=").append(SHOW_STATS_MATCH_TIME).append("\n\n");

            sb.append("# --- 远程资源 ---\n");
            sb.append("map.remote.urls=").append(String.join(",", MAP_REMOTE_URLS)).append("\n");
            sb.append("map.remote.url.name=").append(String.join(",", MAP_REMOTE_URL_NAME)).append("\n");
            String sortArr = Arrays.stream(MAP_REMOTE_URL_SORT).mapToObj(String::valueOf).collect(Collectors.joining(","));
            sb.append("map.remote.url.sort=").append(sortArr).append("\n");

            try (FileOutputStream fos = new FileOutputStream(configFile);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                fos.write(0xEF); fos.write(0xBB); fos.write(0xBF); // UTF-8 BOM
                osw.write(sb.toString());
            }
            log.info("✅ 配置文件已保存至: {}", configFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("❌ 配置文件保存失败", e);
        }
    }

    private static void loadConfig() {
        try {
            File configFile = ResourceUtils.getExternalFile(CONFIG_FILE_NAME);
            if (configFile.exists()) {
                Properties prop = new Properties();
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
                    prop.load(reader);
                }
                overrideFromProperties(prop);
                log.info("✅ 已从本地文件加载用户配置");
            } else {
                save(); // 第一次启动生成默认文件
            }
        } catch (Exception e) {
            log.error("❌ 加载配置异常，使用默认值", e);
        }
    }

    private static void overrideFromProperties(Properties prop) {
        INTERNAL_RESOURCE = getBool(prop, "internal.resource", INTERNAL_RESOURCE);
        TARGET_WINDOW_NAME = getStr(prop, "target.window.name", TARGET_WINDOW_NAME);
        TARGET_CAPTURE_FPS = getInt(prop, "target.capture.fps", TARGET_CAPTURE_FPS);
        SHOW_MONITOR_BORDER = getBool(prop, "show.monitor.border", SHOW_MONITOR_BORDER);
        THEME = getStr(prop, "ui.theme", THEME);
        UI_FONT_SIZE = getInt(prop, "ui.font.size", UI_FONT_SIZE);
        MAP_ZOOM = getInt(prop, "map.zoom", MAP_ZOOM);
        COORDINATE_SMOOTH_FACTOR = getDouble(prop, "coordinate.smooth.factor", COORDINATE_SMOOTH_FACTOR);
        GRAY_DISTANCE = getDouble(prop, "gray.distance", GRAY_DISTANCE);
        MAP_MATCHAER = getStr(prop, "map.matcher", MAP_MATCHAER);
        SCALE_FACTOR = getDouble(prop, "scale.factor", SCALE_FACTOR);
        SIFT_N_FEATURES = getInt(prop, "sift.n.features", SIFT_N_FEATURES);
        MATCH_RATIO_THRESHOLD = (float) getDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        RANSAC_MAX_ITERS = getInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        SHOW_STATS_FPS = getBool(prop, "show.stats.fps", SHOW_STATS_FPS);
        SHOW_STATS_CIRCLE_MASK = getBool(prop, "show.stats.circle.mask", SHOW_STATS_CIRCLE_MASK);
        SHOW_STATS_MATCH_TIME = getBool(prop, "show.stats.match.time", SHOW_STATS_MATCH_TIME);

        MAP_REMOTE_URLS = getStrArray(prop, "map.remote.urls");
        MAP_REMOTE_URL_NAME = getStrArray(prop, "map.remote.url.name");
        MAP_REMOTE_URL_SORT = getIntArray(prop, "map.remote.url.sort");
    }

    // --- 工具方法 ---
    private static String getStr(Properties prop, String key, String def) {
        String val = prop.getProperty(key);
        return (val == null || val.isBlank()) ? def : val.trim();
    }
    private static int getInt(Properties prop, String key, int def) {
        try { return Integer.parseInt(prop.getProperty(key).trim()); } catch (Exception e) { return def; }
    }
    private static double getDouble(Properties prop, String key, double def) {
        try { return Double.parseDouble(prop.getProperty(key).trim()); } catch (Exception e) { return def; }
    }
    private static boolean getBool(Properties prop, String key, boolean def) {
        String val = prop.getProperty(key);
        return val == null ? def : Boolean.parseBoolean(val.trim());
    }
    private static String[] getStrArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new String[0];
        return Arrays.stream(s.split(",")).map(String::trim).filter(v -> !v.isBlank()).toArray(String[]::new);
    }
    private static int[] getIntArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new int[0];
        try { return Arrays.stream(s.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray(); } catch (Exception e) { return new int[0]; }
    }
}