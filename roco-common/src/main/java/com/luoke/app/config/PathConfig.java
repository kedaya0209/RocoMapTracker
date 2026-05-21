package com.luoke.app.config;

import net.jcip.annotations.ThreadSafe;

/**
 * 路径常量 — 系统物理路径，不可更改。
 */
@ThreadSafe
public final class PathConfig {

    private PathConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    // ============================================================
    // 可执行文件
    // ============================================================
    public static final String CAPTURE_EXE = "/capture/RocoMapTracker-capture.exe";
    public static final String SIFT_MATCH_EXE = "/sift/RocoMapTracker-sift_match.exe";

    // ============================================================
    // 资源初始化
    // ============================================================
    public static final String SOURCE_INIT = "/source/init";

    // ============================================================
    // 地图资源
    // ============================================================
    public static final String MAP_RESOURCE_PATH = "/source/map/map_G.png";
    public static final String MAP_RESOURCE_DIR = "/source/map/";
    public static final String SHOW_MAP = "/source/map/WorldMap_Show.png";
    public static final String SIFT_MAP = "/source/map/WorldMap_SIFT.png";

    // ============================================================
    // 图标
    // ============================================================
    public static final String ICON_DIR = "/source/icon/";
    public static final String PLAYER_ICON_PATH = "/source/icon/player.png";
    public static final String RESOURCE_ICON_DIR = "/source/point/";

    // ============================================================
    // 资源点
    // ============================================================
    public static final String RESOURCE_COLLECT_SET = "/source/point/collect_set.txt";
    public static final String RESOURCE_POINT_CONFIG_PATH = "/source/point/resource_config.json";
    public static final String INTERNAL_RESOURCE_POINT_CONFIG_PATH = "/source/point/internal_resource_point.json";

    // ============================================================
    // 模型文件
    // ============================================================
    public static final String MODEL_DIR = "/model/";
    public static final String OCR_REC_MODEL = "ch_PP-OCRv4_rec_mobile.onnx";
    public static final String OCR_DET_MODEL = "ch_PP-OCRv4_det_mobile.onnx";
    public static final String PPOCR_KEYS = "ppocr_keys_v1.txt";
    // ============================================================
    // 配置文件
    // ============================================================
    public static final String PATHS = "/source/map_paths.json";
    public static final String INTERNAL_PATHS = "/source/internal_map_paths.json";
    public static final String ICON = "/icon/rmt.svg";
    public static final String GHOST = "/icon/ghost.svg";
    public static final String NAVIGATION = "/icon/navigation.svg";
    public static final String MATCH_TOGGLE = "/icon/match_toggle.svg";
    public static final String CONFIG_FILE_NAME = "app_config.properties";
}
