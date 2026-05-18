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
 *
 * <p>分组说明：所有可配置项按功能域分组，便于后续在界面上分组调整。
 */
@Slf4j
public final class AppConfig {

    // ============================================================
    // 1. 静态资源常量 (Final) — 程序的物理路径，不可更改
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
    // 2. 远程资源与下载
    // ============================================================
    /**
     * 使用内置资源（否则从远程下载）
     */
    public static boolean INTERNAL_RESOURCE = false;
    /**
     * 远程瓦片 URL 列表
     */
    public static String[] MAP_REMOTE_URLS = new String[0];
    /**
     * 远程瓦片 URL 名称列表
     */
    public static String[] MAP_REMOTE_URL_NAME = new String[0];
    /**
     * 远程瓦片 URL 排序权重
     */
    public static int[] MAP_REMOTE_URL_SORT = new int[0];
    /**
     * 地图资源信息页 URL
     */
    public static String MAP_RESOURCE_INFO_URL = "https://wiki.biligame.com/rocom/大地图";
    /**
     * 地图资源点数据 URL
     */
    public static String MAP_RESOURCE_POINT_URL = "https://wiki.biligame.com/rocom/Data:Mapnew/point.json";

    // --- 下载器参数 ---
    /**
     * HTTP 连接超时（毫秒）
     */
    public static int DOWNLOAD_CONNECT_TIMEOUT = 10000;
    /**
     * HTTP 读取超时（毫秒）
     */
    public static int DOWNLOAD_READ_TIMEOUT = 30000;
    /**
     * 下载失败最大重试次数
     */
    public static int DOWNLOAD_MAX_RETRY = 1;
    /**
     * 并发下载虚拟线程数
     */
    public static int DOWNLOAD_THREAD_COUNT = 32;
    /**
     * 瓦片下载间隔（毫秒）
     */
    public static long DOWNLOAD_TILE_DELAY_MS = 30;
    /**
     * 图标下载间隔（毫秒）
     */
    public static long DOWNLOAD_ICON_DELAY_MS = 100;
    /**
     * 瓦片分块持久化批次大小
     */
    public static int DOWNLOAD_CHUNK_SIZE = 100;

    // ============================================================
    // 3. 窗口与捕获
    // ============================================================
    /**
     * 目标游戏窗口标题
     */
    public static String TARGET_WINDOW_NAME = "洛克王国：世界";
    /**
     * 应用主窗口标题
     */
    public static String APP_MAIN_TITLE = "洛克王国地图";
    /**
     * 目标捕获帧率
     */
    public static int TARGET_CAPTURE_FPS = 30;
    /**
     * 连续黑帧最大数量（超过则断开）
     */
    public static int MAX_BLACK_FRAMES = 30;
    /**
     * 显示录制区域边框
     */
    public static boolean SHOW_MONITOR_BORDER = false;

    // --- 捕获引擎参数 ---
    /**
     * 黑帧检测采样字节数
     */
    public static int CAPTURE_BLACK_SAMPLE_SIZE = 100;
    /**
     * 帧率统计日志间隔（毫秒）
     */
    public static int CAPTURE_STATS_INTERVAL = 10000;
    /**
     * capture.exe 进程优雅停止等待秒数
     */
    public static int CAPTURE_PROCESS_SHUTDOWN_WAIT = 3;

    // ============================================================
    // 4. Socket 与子进程管理
    // ============================================================
    /**
     * ServerSocket 待处理连接队列深度
     */
    public static int SOCKET_BACKLOG = 1;
    /**
     * Socket accept 线程 join 超时（毫秒）
     */
    public static int SOCKET_ACCEPT_JOIN_TIMEOUT = 2000;

    // --- sift_match.exe 子进程 ---
    /**
     * 崩溃后最小重启间隔（毫秒）
     */
    public static long SIFT_RESTART_MIN_INTERVAL = 5000;
    /**
     * 重启前等待旧进程退出的延迟（毫秒）
     */
    public static long SIFT_RESTART_DELAY = 1000;
    /**
     * 子进程优雅停止等待秒数
     */
    public static int SIFT_PROCESS_STOP_TIMEOUT = 3;

    // ============================================================
    // 5. UI 与交互配置
    // ============================================================
    /**
     * JavaFX 主题名称
     */
    public static String THEME = "PrimerDark";
    /**
     * UI 基础字号
     */
    public static int UI_FONT_SIZE = 14;
    /**
     * 默认是否启用跟随模式
     */
    public static boolean DEFAULT_FOLLOW_MODE = false;
    /**
     * 跟随模式默认缩放值
     */
    public static double DEFAULT_FOLLOW_SCALE = 1.5;
    /**
     * 地图瓦片缩放级别
     */
    public static int MAP_ZOOM = 7;
    /**
     * 瓦片最小缩放级别
     */
    public static int MAP_MIN_ZOOM = 4;
    /**
     * 瓦片最大缩放级别
     */
    public static int MAP_MAX_ZOOM = 8;
    /**
     * JSON 配置中的缩放级别
     */
    public static int JSON_ZOOM = 7;
    /**
     * 坐标平滑系数（EMA alpha）
     */
    public static double COORDINATE_SMOOTH_FACTOR = 0.8;
    /**
     * 资源变灰检测距离（世界像素）
     */
    public static double GRAY_DISTANCE = 25;
    /**
     * 启用物资采集统计
     */
    public static boolean MATERIAL_COLLECTION = false;

    // --- 窗口尺寸 ---
    /**
     * 窗口边缘拖拽缩放感应区宽度（像素）
     */
    public static int RESIZE_MARGIN = 8;
    /**
     * 窗口最小宽度
     */
    public static double MIN_WINDOW_WIDTH = 400;
    /**
     * 窗口最小高度
     */
    public static double MIN_WINDOW_HEIGHT = 300;
    /**
     * 初始窗口宽度
     */
    public static double INITIAL_WINDOW_WIDTH = 1100;
    /**
     * 初始窗口高度
     */
    public static double INITIAL_WINDOW_HEIGHT = 800;

    // --- 地图交互 ---
    /**
     * 滚轮缩放因子（>1 放大，<1 缩小）
     */
    public static double INTERACTIVE_ZOOM_FACTOR = 1.1;
    /**
     * 跟随模式最小缩放
     */
    public static double INTERACTIVE_FOLLOW_MIN_SCALE = 0.3;
    /**
     * 跟随模式最大缩放
     */
    public static double INTERACTIVE_FOLLOW_MAX_SCALE = 5.0;
    /**
     * 资源点鼠标悬停检测半径（逻辑像素）
     */
    public static double HOVER_DETECT_RADIUS = 16.0;
    /**
     * 路径节点点击/拖拽检测半径（逻辑像素）
     */
    public static double NODE_CLICK_THRESHOLD = 15.0;
    /**
     * 路径节点插入检测距离（逻辑像素）
     */
    public static double NODE_INSERT_THRESHOLD = 12.0;
    /**
     * 地图最大视觉缩放比例
     */
    public static double MAP_VIEW_MAX_SCALE = 15.0;

    // --- UI 布局 ---
    /**
     * 侧边栏宽度
     */
    public static double SIDEBAR_WIDTH = 240;
    /**
     * 侧边栏列表视图宽度
     */
    public static double SIDEBAR_LIST_WIDTH = 210;
    /**
     * 统计面板字体名称
     */
    public static String STATS_FONT_NAME = "Microsoft YaHei";
    /**
     * 统计面板字号
     */
    public static int STATS_FONT_SIZE = 13;
    /**
     * 统计面板内边距
     */
    public static int STATS_PADDING = 5;
    /**
     * 物资采集面板宽度
     */
    public static double RESOURCE_COUNTER_WIDTH = 220;
    /**
     * 物资采集面板不透明度
     */
    public static double RESOURCE_COUNTER_OPACITY = 0.88;
    /**
     * Toast 最大宽度
     */
    public static double TOAST_MAX_WIDTH = 400;
    /**
     * Toast 最大高度
     */
    public static double TOAST_MAX_HEIGHT = 50;
    /**
     * 搜索后台更新条目高度
     */
    public static int WIKI_ITEM_HEIGHT = 38;

    // ============================================================
    // 6. 渲染与瓦片
    // ============================================================
    /**
     * 资源点图标视口/缓存尺寸（像素）
     */
    public static double ICON_SIZE = 32;
    /**
     * 瓦片基础尺寸（像素）
     */
    public static int TILE_SIZE = 256;
    /**
     * 缩放稳定所需帧数（约 165ms）
     */
    public static int SCALE_STABLE_THRESHOLD = 5;
    /**
     * 瓦片视口外预加载缓冲区倍数
     */
    public static double TILE_BUFFER_MULTIPLIER = 3.0;

    // --- 玩家渲染 ---
    /**
     * 玩家图标绘制尺寸（像素）
     */
    public static double PLAYER_IMG_SIZE = 36;
    /**
     * 玩家图标显示尺寸（ImageView）
     */
    public static double PLAYER_VIEW_SIZE = 72;
    /**
     * 无朝向时的回退圆点半径
     */
    public static double PLAYER_DOT_RADIUS = 6;
    /**
     * 回退圆点描边宽度
     */
    public static double PLAYER_DOT_STROKE_WIDTH = 1.5;

    // --- 路线渲染 ---
    /**
     * 非活跃路线描边宽度
     */
    public static double ROUTE_INACTIVE_WIDTH = 2.0;
    /**
     * 活跃路线描边宽度
     */
    public static double ROUTE_ACTIVE_WIDTH = 3.0;
    /**
     * 路径节点锚点半径
     */
    public static double ROUTE_NODE_RADIUS = 4.5;
    /**
     * 绘制模式预览虚线长度
     */
    public static double ROUTE_DASH_LENGTH = 5.0;

    // --- Hover 渲染 ---
    /**
     * Hover 高亮图标尺寸
     */
    public static double HOVER_ICON_SIZE = 38;
    /**
     * Hover 外发光高亮色
     */
    public static String HOVER_GLOW_COLOR = "#00BFFF";

    // --- 变灰重检测 ---
    /**
     * 变灰重检测玩家移动阈值（世界像素）
     */
    public static double GRAY_CHECK_THRESHOLD = 10;

    // ============================================================
    // 7. 动效与动画
    // ============================================================
    /**
     * 渲染循环帧间隔（毫秒），约 30 FPS
     */
    public static long RENDER_FRAME_INTERVAL_MS = 33;

    // --- 潮汐波纹 ---
    /**
     * 波纹圈数
     */
    public static int RIPPLE_COUNT = 3;
    /**
     * 每帧波纹进度增量
     */
    public static double RIPPLE_STEP = 0.008;
    /**
     * 波纹描边宽度
     */
    public static double RIPPLE_STROKE_WIDTH = 1.5;
    /**
     * 波纹初始透明度
     */
    public static double RIPPLE_ALPHA = 0.35;

    // --- 拾取光环（呼吸） ---
    /**
     * 光环呼吸频率系数
     */
    public static double HALO_BREATHE_FREQ = 0.03;
    /**
     * 光环呼吸透明度最小值
     */
    public static double HALO_BREATHE_MIN_ALPHA = 0.08;
    /**
     * 光环呼吸透明度最大值
     */
    public static double HALO_BREATHE_MAX_ALPHA = 0.20;
    /**
     * 光环描边宽度
     */
    public static double HALO_STROKE_WIDTH = 1.0;

    // --- Toast 通知动画 ---
    /**
     * 滑入动画时长（毫秒）
     */
    public static int TOAST_FADE_IN_MS = 400;
    /**
     * 滑出动画时长（毫秒）
     */
    public static int TOAST_FADE_OUT_MS = 400;
    /**
     * 显示停顿时长（秒）
     */
    public static int TOAST_DISPLAY_SEC = 3;

    // --- 面板动画 ---
    /**
     * 侧边栏滑入/滑出动画时长（毫秒）
     */
    public static int SIDEBAR_ANIM_MS = 250;
    /**
     * 物资面板淡入淡出时长（毫秒）
     */
    public static int PANEL_FADE_MS = 300;

    // ============================================================
    // 8. SIFT 与匹配参数
    // ============================================================
    /**
     * 匹配器类型（SIFT / SIFT-PCA / SIFT-ULTRA / SIFT-PCA-ULTRA）
     */
    public static String MAP_MATCHAER = "SIFT-ULTRA";
    /**
     * 缩放因子
     */
    public static double SCALE_FACTOR = 1.0;

    // --- SIFT 检测器 ---
    /**
     * SIFT 最大特征点数（0=无限制）
     */
    public static int SIFT_N_FEATURES = 0;
    /**
     * SIFT 每层组数
     */
    public static int SIFT_N_OCTAVE_LAYERS = 3;
    /**
     * SIFT 对比度阈值
     */
    public static double SIFT_CONTRAST_THRESHOLD = 0.001;
    /**
     * SIFT 边缘阈值
     */
    public static double SIFT_EDGE_THRESHOLD = 50.0;
    /**
     * SIFT sigma
     */
    public static double SIFT_SIGMA = 1.6;

    // --- FLANN 索引 ---
    /**
     * FLANN KD 树数量
     */
    public static int FLANN_KD_TREES = 1;
    /**
     * FLANN 搜索检查次数
     */
    public static int FLANN_SEARCH_CHECKS = 24;

    // --- 重叠分块训练 ---
    /**
     * 训练瓦片基础尺寸（像素）
     */
    public static int SIFT_TILE_SIZE = 2000;
    /**
     * 训练瓦片重叠宽度（像素）
     */
    public static int SIFT_TILE_OVERLAP = 200;
    /**
     * 启用分块的地图像素阈值
     */
    public static long SIFT_LARGE_MAP_THRESHOLD = 3000L * 3000L;
    /**
     * 重叠区域特征去重距离（像素）
     */
    public static float SIFT_DEDUP_DISTANCE = 4.0f;

    // --- 匹配过滤 ---
    /**
     * 比率测试阈值
     */
    public static float MATCH_RATIO_THRESHOLD = 0.6f;
    /**
     * 匹配点最小数量
     */
    public static int MATCH_MIN_COUNT = 10;
    /**
     * 空间过滤搜索半径（像素）
     */
    public static int SEARCH_RADIUS = 500;

    // --- RANSAC ---
    /**
     * RANSAC 重投影误差阈值
     */
    public static double RANSAC_REPROJ_THRESHOLD = 10.0;
    /**
     * RANSAC 最大迭代次数
     */
    public static int RANSAC_MAX_ITERS = 200;
    /**
     * RANSAC 置信度
     */
    public static double RANSAC_CONFIDENCE = 0.95;

    // --- ROI 与匹配调度 ---
    /**
     * 小地图 ROI 万分比坐标 X
     */
    public static int ROI_MAP_X = 8900;
    /**
     * 小地图 ROI 万分比坐标 Y
     */
    public static int ROI_MAP_Y = 300;
    /**
     * 小地图 ROI 万分比宽度
     */
    public static int ROI_MAP_W = 1000;
    /**
     * 小地图 ROI 万分比高度（0=自动正方形）
     */
    public static int ROI_MAP_H = 0;
    /**
     * SIFT 匹配等待超时（毫秒）
     */
    public static long MATCH_TIMEOUT_MS = 500;

    // --- 箭头检测 ---
    /**
     * 箭头 CNN 裁剪尺寸（像素，正方形）
     */
    public static int ARROW_CROP_SIZE = 64;

    // ============================================================
    // 9. 玩家状态追踪
    // ============================================================
    /**
     * 位置平滑 EMA 衰减因子（越低越平滑但滞后）
     */
    public static double PLAYER_EMA_ALPHA = 0.35;
    /**
     * 瞬移检测阈值（世界坐标单位）
     */
    public static double PLAYER_TELEPORT_THRESHOLD = 150.0;
    /**
     * 速度估计 EMA 衰减因子
     */
    public static double PLAYER_VELOCITY_EMA_ALPHA = 0.5;
    /**
     * 地图丢失前连续失败次数
     */
    public static int PLAYER_MAP_LOST_THRESHOLD = 5;

    // ============================================================
    // 10. OCR 参数
    // ============================================================
    /**
     * OCR 并发信号量大小
     */
    public static int OCR_CORE_SIZE = 1;

    // --- 扫描与稳定性 ---
    /**
     * OCR 扫描最小间隔（毫秒）
     */
    public static long OCR_SCAN_INTERVAL = 200;
    /**
     * OCR 稳定性判定连续次数
     */
    public static int OCR_STABILITY_THRESHOLD = 2;

    // --- 线程池 ---
    /**
     * OCR 线程池核心/最大线程数
     */
    public static int OCR_THREAD_POOL_SIZE = 2;
    /**
     * OCR 任务队列容量
     */
    public static int OCR_TASK_QUEUE_CAPACITY = 10;
    /**
     * OCR 任务超时（毫秒，超过丢弃）
     */
    public static long OCR_TASK_TIMEOUT_MS = 500;

    // --- OCR ROI ---
    /**
     * OCR ROI 万分比坐标 X
     */
    public static int ROI_OCR_X = 8750;
    /**
     * OCR ROI 万分比坐标 Y
     */
    public static int ROI_OCR_Y = 2070;
    /**
     * OCR ROI 万分比宽度
     */
    public static int ROI_OCR_W = 1100;
    /**
     * OCR ROI 万分比高度
     */
    public static int ROI_OCR_H = 2100;

    // --- OCR 识别参数 ---
    /**
     * 识别标准高度（像素）
     */
    public static int OCR_REC_STD_HEIGHT = 52;
    /**
     * 文本检测热力图阈值
     */
    public static float OCR_TEXT_HEAT_THRESHOLD = 0.20f;
    /**
     * 检测到文本后垂直扩展像素
     */
    public static int OCR_EXPAND_Y = 4;
    /**
     * 检测输入填充对齐值
     */
    public static int OCR_DET_ALIGNMENT = 32;
    /**
     * 识别输入宽度对齐值
     */
    public static int OCR_REC_WIDTH_ALIGNMENT = 8;
    /**
     * 二值化阈值（低于此值为文本）
     */
    public static int OCR_BINARY_THRESHOLD = 150;
    /**
     * 文本行最小高度过滤（像素）
     */
    public static int OCR_MIN_RECT_HEIGHT = 5;
    /**
     * OCR 名称最小长度（纯文本无数量时）
     */
    public static int OCR_NAME_MIN_LENGTH = 2;

    // ============================================================
    // 11. 小地图检测参数
    // ============================================================
    /**
     * 缩小检测宽度（像素）
     */
    public static int MM_SMALL_WIDTH = 120;
    /**
     * 黑边比例阈值
     */
    public static double MM_BLACK_RATIO_THRESHOLD = 0.15;
    /**
     * 圆心偏移比例阈值
     */
    public static double MM_CENTER_OFFSET_RATIO = 0.2;
    /**
     * 中值滤波核大小
     */
    public static int MM_MEDIAN_BLUR_KERNEL = 5;
    /**
     * HoughCircles dp
     */
    public static double MM_HOUGH_DP = 1.2;
    /**
     * HoughCircles param1（Canny 高阈值）
     */
    public static double MM_HOUGH_PARAM1 = 50;
    /**
     * HoughCircles param2（累加器阈值）
     */
    public static double MM_HOUGH_PARAM2 = 35;
    /**
     * 边缘黑边像素灰度阈值
     */
    public static int MM_BLACK_PIXEL_THRESHOLD = 150;
    /**
     * 边缘采样点数
     */
    public static int MM_EDGE_SAMPLE_COUNT = 120;
    /**
     * 边缘采样角度步长（度）
     */
    public static double MM_EDGE_SAMPLE_STEP = 3.0;

    // ============================================================
    // 12. 统计显示
    // ============================================================
    /**
     * 显示匹配耗时
     */
    public static boolean SHOW_STATS_MATCH_TIME = true;
    /**
     * 显示朝向检测耗时
     */
    public static boolean SHOW_STATS_DIR_TIME = true;
    /**
     * 显示 FPS
     */
    public static boolean SHOW_STATS_FPS = true;

    // --- 统计辅助 ---
    /**
     * FPS 计算窗口（毫秒）
     */
    public static int STATS_FPS_WINDOW_MS = 1000;
    /**
     * 资源点空间网格单元大小（屏幕坐标）
     */
    public static int GRID_CELL_SIZE = 120;

    static {
        loadConfig();
    }

    private AppConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    // ============================================================
    // 持久化：save() / loadConfig() / overrideFromProperties()
    // ============================================================

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

            // ---- 基础设置 ----
            sb.append("# 使用内置资源（否则从远程下载）\n");
            sb.append("internal.resource=").append(INTERNAL_RESOURCE).append("\n");
            sb.append("# 目标游戏窗口标题\n");
            sb.append("target.window.name=").append(TARGET_WINDOW_NAME).append("\n");
            sb.append("# 目标捕获帧率\n");
            sb.append("target.capture.fps=").append(TARGET_CAPTURE_FPS).append("\n");
            sb.append("# 显示录制区域边框\n");
            sb.append("show.monitor.border=").append(SHOW_MONITOR_BORDER).append("\n");
            sb.append("# 黑帧检测采样字节数\n");
            sb.append("capture.black.sample.size=").append(CAPTURE_BLACK_SAMPLE_SIZE).append("\n");
            sb.append("# 启用物资采集统计\n");
            sb.append("material.collection=").append(MATERIAL_COLLECTION).append("\n\n");

            // ---- 下载与网络 ----
            sb.append("# HTTP 连接超时（毫秒）\n");
            sb.append("download.connect.timeout=").append(DOWNLOAD_CONNECT_TIMEOUT).append("\n");
            sb.append("# HTTP 读取超时（毫秒）\n");
            sb.append("download.read.timeout=").append(DOWNLOAD_READ_TIMEOUT).append("\n");
            sb.append("# 下载失败最大重试次数\n");
            sb.append("download.max.retry=").append(DOWNLOAD_MAX_RETRY).append("\n");
            sb.append("# 并发下载虚拟线程数\n");
            sb.append("download.thread.count=").append(DOWNLOAD_THREAD_COUNT).append("\n");
            sb.append("# 瓦片下载间隔（毫秒）\n");
            sb.append("download.tile.delay.ms=").append(DOWNLOAD_TILE_DELAY_MS).append("\n");
            sb.append("# 图标下载间隔（毫秒）\n");
            sb.append("download.icon.delay.ms=").append(DOWNLOAD_ICON_DELAY_MS).append("\n");
            sb.append("# ServerSocket 待处理连接队列深度\n");
            sb.append("socket.backlog=").append(SOCKET_BACKLOG).append("\n");
            sb.append("# Socket accept 线程 join 超时（毫秒）\n");
            sb.append("socket.accept.join.timeout=").append(SOCKET_ACCEPT_JOIN_TIMEOUT).append("\n");
            sb.append("# SIFT 崩溃后最小重启间隔（毫秒）\n");
            sb.append("sift.restart.min.interval=").append(SIFT_RESTART_MIN_INTERVAL).append("\n");
            sb.append("# 重启前等待旧进程退出的延迟（毫秒）\n");
            sb.append("sift.restart.delay=").append(SIFT_RESTART_DELAY).append("\n");
            sb.append("# 子进程优雅停止等待秒数\n");
            sb.append("sift.process.stop.timeout=").append(SIFT_PROCESS_STOP_TIMEOUT).append("\n\n");

            // ---- UI 与交互 ----
            sb.append("# JavaFX 主题名称\n");
            sb.append("ui.theme=").append(THEME).append("\n");
            sb.append("# UI 基础字号\n");
            sb.append("ui.font.size=").append(UI_FONT_SIZE).append("\n");
            sb.append("# 地图瓦片缩放级别\n");
            sb.append("map.zoom=").append(MAP_ZOOM).append("\n");
            sb.append("# 地图最大视觉缩放比例\n");
            sb.append("map.view.max.scale=").append(MAP_VIEW_MAX_SCALE).append("\n");
            sb.append("# 坐标平滑系数（EMA alpha）\n");
            sb.append("coordinate.smooth.factor=").append(COORDINATE_SMOOTH_FACTOR).append("\n");
            sb.append("# 资源变灰检测距离（世界像素）\n");
            sb.append("gray.distance=").append(GRAY_DISTANCE).append("\n");
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
            sb.append("# 侧边栏宽度\n");
            sb.append("sidebar.width=").append(SIDEBAR_WIDTH).append("\n");
            sb.append("# 统计面板字号\n");
            sb.append("stats.font.size=").append(STATS_FONT_SIZE).append("\n\n");

            // ---- 渲染与动效 ----
            sb.append("# 资源点图标视口/缓存尺寸（像素）\n");
            sb.append("icon.size=").append(ICON_SIZE).append("\n");
            sb.append("# 瓦片基础尺寸（像素）\n");
            sb.append("tile.size=").append(TILE_SIZE).append("\n");
            sb.append("# 玩家图标绘制尺寸（像素）\n");
            sb.append("player.img.size=").append(PLAYER_IMG_SIZE).append("\n");
            sb.append("# 玩家图标显示尺寸（ImageView）\n");
            sb.append("player.view.size=").append(PLAYER_VIEW_SIZE).append("\n");
            sb.append("# 变灰重检测玩家移动阈值（世界像素）\n");
            sb.append("gray.check.threshold=").append(GRAY_CHECK_THRESHOLD).append("\n");
            sb.append("# 波纹圈数\n");
            sb.append("ripple.count=").append(RIPPLE_COUNT).append("\n");
            sb.append("# 每帧波纹进度增量\n");
            sb.append("ripple.step=").append(RIPPLE_STEP).append("\n");
            sb.append("# 光环呼吸频率系数\n");
            sb.append("halo.breathe.freq=").append(HALO_BREATHE_FREQ).append("\n");
            sb.append("# Toast 显示停顿时长（秒）\n");
            sb.append("toast.display.sec=").append(TOAST_DISPLAY_SEC).append("\n");
            sb.append("# 侧边栏滑入/滑出动画时长（毫秒）\n");
            sb.append("sidebar.anim.ms=").append(SIDEBAR_ANIM_MS).append("\n\n");

            // ---- 算法核心 ----
            sb.append("# 匹配器类型（SIFT / SIFT-PCA / SIFT-ULTRA / SIFT-PCA-ULTRA）\n");
            sb.append("map.matcher=").append(MAP_MATCHAER).append("\n");
            sb.append("# 缩放因子\n");
            sb.append("scale.factor=").append(SCALE_FACTOR).append("\n");
            sb.append("# SIFT 最大特征点数（0=无限制）\n");
            sb.append("sift.n.features=").append(SIFT_N_FEATURES).append("\n");
            sb.append("# FLANN KD 树数量\n");
            sb.append("flann.kd.trees=").append(FLANN_KD_TREES).append("\n");
            sb.append("# FLANN 搜索检查次数\n");
            sb.append("flann.search.checks=").append(FLANN_SEARCH_CHECKS).append("\n");
            sb.append("# 比率测试阈值\n");
            sb.append("match.ratio.threshold=").append(MATCH_RATIO_THRESHOLD).append("\n");
            sb.append("# 匹配点最小数量\n");
            sb.append("match.min.count=").append(MATCH_MIN_COUNT).append("\n");
            sb.append("# 空间过滤搜索半径（像素）\n");
            sb.append("search.radius=").append(SEARCH_RADIUS).append("\n");
            sb.append("# RANSAC 最大迭代次数\n");
            sb.append("ransac.max.iters=").append(RANSAC_MAX_ITERS).append("\n");
            sb.append("# SIFT 匹配等待超时（毫秒）\n");
            sb.append("match.timeout.ms=").append(MATCH_TIMEOUT_MS).append("\n");
            sb.append("# 箭头 CNN 裁剪尺寸（像素，正方形）\n");
            sb.append("arrow.crop.size=").append(ARROW_CROP_SIZE).append("\n\n");

            // ---- 玩家追踪 ----
            sb.append("# 位置平滑 EMA 衰减因子（越低越平滑但滞后）\n");
            sb.append("player.ema.alpha=").append(PLAYER_EMA_ALPHA).append("\n");
            sb.append("# 瞬移检测阈值（世界坐标单位）\n");
            sb.append("player.teleport.threshold=").append(PLAYER_TELEPORT_THRESHOLD).append("\n");
            sb.append("# 速度估计 EMA 衰减因子\n");
            sb.append("player.velocity.ema.alpha=").append(PLAYER_VELOCITY_EMA_ALPHA).append("\n");
            sb.append("# 地图丢失前连续失败次数\n");
            sb.append("player.map.lost.threshold=").append(PLAYER_MAP_LOST_THRESHOLD).append("\n\n");

            // ---- OCR ----
            sb.append("# OCR 并发信号量大小\n");
            sb.append("ocr.core.size=").append(OCR_CORE_SIZE).append("\n");
            sb.append("# OCR 扫描最小间隔（毫秒）\n");
            sb.append("ocr.scan.interval=").append(OCR_SCAN_INTERVAL).append("\n");
            sb.append("# OCR 稳定性判定连续次数\n");
            sb.append("ocr.stability.threshold=").append(OCR_STABILITY_THRESHOLD).append("\n");
            sb.append("# OCR 线程池核心/最大线程数\n");
            sb.append("ocr.thread.pool.size=").append(OCR_THREAD_POOL_SIZE).append("\n");
            sb.append("# OCR 任务队列容量\n");
            sb.append("ocr.task.queue.capacity=").append(OCR_TASK_QUEUE_CAPACITY).append("\n");
            sb.append("# OCR 任务超时（毫秒，超过丢弃）\n");
            sb.append("ocr.task.timeout.ms=").append(OCR_TASK_TIMEOUT_MS).append("\n\n");

            // ---- 小地图检测 ----
            sb.append("# 缩小检测宽度（像素）\n");
            sb.append("mm.small.width=").append(MM_SMALL_WIDTH).append("\n");
            sb.append("# 黑边比例阈值\n");
            sb.append("mm.black.ratio.threshold=").append(MM_BLACK_RATIO_THRESHOLD).append("\n");
            sb.append("# 圆心偏移比例阈值\n");
            sb.append("mm.center.offset.ratio=").append(MM_CENTER_OFFSET_RATIO).append("\n\n");

            // ---- 统计显示 ----
            sb.append("# 显示 FPS\n");
            sb.append("show.stats.fps=").append(SHOW_STATS_FPS).append("\n");
            sb.append("# 显示匹配耗时\n");
            sb.append("show.stats.match.time=").append(SHOW_STATS_MATCH_TIME).append("\n");
            sb.append("# 显示朝向检测耗时\n");
            sb.append("show.stats.dir.time=").append(SHOW_STATS_DIR_TIME).append("\n\n");

            // ---- 远程资源 ----
            sb.append("# 远程瓦片 URL 列表\n");
            sb.append("map.remote.urls=").append(String.join(",", MAP_REMOTE_URLS)).append("\n");
            sb.append("# 远程瓦片 URL 名称列表\n");
            sb.append("map.remote.url.name=").append(String.join(",", MAP_REMOTE_URL_NAME)).append("\n");
            String sortArr = Arrays.stream(MAP_REMOTE_URL_SORT).mapToObj(String::valueOf).collect(Collectors.joining(","));
            sb.append("# 远程瓦片 URL 排序权重\n");
            sb.append("map.remote.url.sort=").append(sortArr).append("\n");

            try (FileOutputStream fos = new FileOutputStream(configFile);
                 OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                fos.write(0xEF);
                fos.write(0xBB);
                fos.write(0xBF); // UTF-8 BOM
                osw.write(sb.toString());
            }
            log.info("配置文件已保存至: {}", configFile.getAbsolutePath());
        } catch (Exception e) {
            log.error("配置文件保存失败", e);
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
                log.info("已从本地文件加载用户配置");
            } else {
                save(); // 第一次启动生成默认文件
            }
        } catch (Exception e) {
            log.error("加载配置异常，使用默认值", e);
        }
    }

    private static void overrideFromProperties(Properties prop) {
        // --- 基础设置 ---
        INTERNAL_RESOURCE = getBool(prop, "internal.resource", INTERNAL_RESOURCE);
        TARGET_WINDOW_NAME = getStr(prop, "target.window.name", TARGET_WINDOW_NAME);
        TARGET_CAPTURE_FPS = getInt(prop, "target.capture.fps", TARGET_CAPTURE_FPS);
        SHOW_MONITOR_BORDER = getBool(prop, "show.monitor.border", SHOW_MONITOR_BORDER);
        CAPTURE_BLACK_SAMPLE_SIZE = getInt(prop, "capture.black.sample.size", CAPTURE_BLACK_SAMPLE_SIZE);
        MATERIAL_COLLECTION = getBool(prop, "material.collection", MATERIAL_COLLECTION);

        // --- 下载与网络 ---
        DOWNLOAD_CONNECT_TIMEOUT = getInt(prop, "download.connect.timeout", DOWNLOAD_CONNECT_TIMEOUT);
        DOWNLOAD_READ_TIMEOUT = getInt(prop, "download.read.timeout", DOWNLOAD_READ_TIMEOUT);
        DOWNLOAD_MAX_RETRY = getInt(prop, "download.max.retry", DOWNLOAD_MAX_RETRY);
        DOWNLOAD_THREAD_COUNT = getInt(prop, "download.thread.count", DOWNLOAD_THREAD_COUNT);
        DOWNLOAD_TILE_DELAY_MS = getLong(prop, "download.tile.delay.ms", DOWNLOAD_TILE_DELAY_MS);
        DOWNLOAD_ICON_DELAY_MS = getLong(prop, "download.icon.delay.ms", DOWNLOAD_ICON_DELAY_MS);
        SOCKET_BACKLOG = getInt(prop, "socket.backlog", SOCKET_BACKLOG);
        SOCKET_ACCEPT_JOIN_TIMEOUT = getInt(prop, "socket.accept.join.timeout", SOCKET_ACCEPT_JOIN_TIMEOUT);
        SIFT_RESTART_MIN_INTERVAL = getLong(prop, "sift.restart.min.interval", SIFT_RESTART_MIN_INTERVAL);
        SIFT_RESTART_DELAY = getLong(prop, "sift.restart.delay", SIFT_RESTART_DELAY);
        SIFT_PROCESS_STOP_TIMEOUT = getInt(prop, "sift.process.stop.timeout", SIFT_PROCESS_STOP_TIMEOUT);

        // --- UI 与交互 ---
        THEME = getStr(prop, "ui.theme", THEME);
        UI_FONT_SIZE = getInt(prop, "ui.font.size", UI_FONT_SIZE);
        MAP_ZOOM = getInt(prop, "map.zoom", MAP_ZOOM);
        MAP_VIEW_MAX_SCALE = getDouble(prop, "map.view.max.scale", MAP_VIEW_MAX_SCALE);
        COORDINATE_SMOOTH_FACTOR = getDouble(prop, "coordinate.smooth.factor", COORDINATE_SMOOTH_FACTOR);
        GRAY_DISTANCE = getDouble(prop, "gray.distance", GRAY_DISTANCE);
        RESIZE_MARGIN = getInt(prop, "resize.margin", RESIZE_MARGIN);
        MIN_WINDOW_WIDTH = getDouble(prop, "min.window.width", MIN_WINDOW_WIDTH);
        MIN_WINDOW_HEIGHT = getDouble(prop, "min.window.height", MIN_WINDOW_HEIGHT);
        INTERACTIVE_ZOOM_FACTOR = getDouble(prop, "interactive.zoom.factor", INTERACTIVE_ZOOM_FACTOR);
        HOVER_DETECT_RADIUS = getDouble(prop, "hover.detect.radius", HOVER_DETECT_RADIUS);
        SIDEBAR_WIDTH = getDouble(prop, "sidebar.width", SIDEBAR_WIDTH);
        STATS_FONT_SIZE = getInt(prop, "stats.font.size", STATS_FONT_SIZE);

        // --- 渲染与动效 ---
        ICON_SIZE = getDouble(prop, "icon.size", ICON_SIZE);
        TILE_SIZE = getInt(prop, "tile.size", TILE_SIZE);
        PLAYER_IMG_SIZE = getDouble(prop, "player.img.size", PLAYER_IMG_SIZE);
        PLAYER_VIEW_SIZE = getDouble(prop, "player.view.size", PLAYER_VIEW_SIZE);
        GRAY_CHECK_THRESHOLD = getDouble(prop, "gray.check.threshold", GRAY_CHECK_THRESHOLD);
        RIPPLE_COUNT = getInt(prop, "ripple.count", RIPPLE_COUNT);
        RIPPLE_STEP = getDouble(prop, "ripple.step", RIPPLE_STEP);
        HALO_BREATHE_FREQ = getDouble(prop, "halo.breathe.freq", HALO_BREATHE_FREQ);
        TOAST_DISPLAY_SEC = getInt(prop, "toast.display.sec", TOAST_DISPLAY_SEC);
        SIDEBAR_ANIM_MS = getInt(prop, "sidebar.anim.ms", SIDEBAR_ANIM_MS);

        // --- 算法核心 ---
        MAP_MATCHAER = getStr(prop, "map.matcher", MAP_MATCHAER);
        SCALE_FACTOR = getDouble(prop, "scale.factor", SCALE_FACTOR);
        SIFT_N_FEATURES = getInt(prop, "sift.n.features", SIFT_N_FEATURES);
        FLANN_KD_TREES = getInt(prop, "flann.kd.trees", FLANN_KD_TREES);
        FLANN_SEARCH_CHECKS = getInt(prop, "flann.search.checks", FLANN_SEARCH_CHECKS);
        MATCH_RATIO_THRESHOLD = (float) getDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        MATCH_MIN_COUNT = getInt(prop, "match.min.count", MATCH_MIN_COUNT);
        SEARCH_RADIUS = getInt(prop, "search.radius", SEARCH_RADIUS);
        RANSAC_MAX_ITERS = getInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        MATCH_TIMEOUT_MS = getLong(prop, "match.timeout.ms", MATCH_TIMEOUT_MS);
        ARROW_CROP_SIZE = getInt(prop, "arrow.crop.size", ARROW_CROP_SIZE);

        // --- 玩家追踪 ---
        PLAYER_EMA_ALPHA = getDouble(prop, "player.ema.alpha", PLAYER_EMA_ALPHA);
        PLAYER_TELEPORT_THRESHOLD = getDouble(prop, "player.teleport.threshold", PLAYER_TELEPORT_THRESHOLD);
        PLAYER_VELOCITY_EMA_ALPHA = getDouble(prop, "player.velocity.ema.alpha", PLAYER_VELOCITY_EMA_ALPHA);
        PLAYER_MAP_LOST_THRESHOLD = getInt(prop, "player.map.lost.threshold", PLAYER_MAP_LOST_THRESHOLD);

        // --- OCR ---
        OCR_CORE_SIZE = getInt(prop, "ocr.core.size", OCR_CORE_SIZE);
        OCR_SCAN_INTERVAL = getLong(prop, "ocr.scan.interval", OCR_SCAN_INTERVAL);
        OCR_STABILITY_THRESHOLD = getInt(prop, "ocr.stability.threshold", OCR_STABILITY_THRESHOLD);
        OCR_THREAD_POOL_SIZE = getInt(prop, "ocr.thread.pool.size", OCR_THREAD_POOL_SIZE);
        OCR_TASK_QUEUE_CAPACITY = getInt(prop, "ocr.task.queue.capacity", OCR_TASK_QUEUE_CAPACITY);
        OCR_TASK_TIMEOUT_MS = getLong(prop, "ocr.task.timeout.ms", OCR_TASK_TIMEOUT_MS);

        // --- 小地图检测 ---
        MM_SMALL_WIDTH = getInt(prop, "mm.small.width", MM_SMALL_WIDTH);
        MM_BLACK_RATIO_THRESHOLD = getDouble(prop, "mm.black.ratio.threshold", MM_BLACK_RATIO_THRESHOLD);
        MM_CENTER_OFFSET_RATIO = getDouble(prop, "mm.center.offset.ratio", MM_CENTER_OFFSET_RATIO);

        // --- 统计显示 ---
        SHOW_STATS_FPS = getBool(prop, "show.stats.fps", SHOW_STATS_FPS);
        SHOW_STATS_MATCH_TIME = getBool(prop, "show.stats.match.time", SHOW_STATS_MATCH_TIME);
        SHOW_STATS_DIR_TIME = getBool(prop, "show.stats.dir.time", SHOW_STATS_DIR_TIME);

        // --- 远程资源 ---
        MAP_REMOTE_URLS = getStrArray(prop, "map.remote.urls");
        MAP_REMOTE_URL_NAME = getStrArray(prop, "map.remote.url.name");
        MAP_REMOTE_URL_SORT = getIntArray(prop, "map.remote.url.sort");
    }

    // ============================================================
    // 工具方法
    // ============================================================
    private static String getStr(Properties prop, String key, String def) {
        String val = prop.getProperty(key);
        return (val == null || val.isBlank()) ? def : val.trim();
    }

    private static int getInt(Properties prop, String key, int def) {
        try {
            return Integer.parseInt(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static long getLong(Properties prop, String key, long def) {
        try {
            return Long.parseLong(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double getDouble(Properties prop, String key, double def) {
        try {
            return Double.parseDouble(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
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
        try {
            return Arrays.stream(s.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
        } catch (Exception e) {
            return new int[0];
        }
    }
}
