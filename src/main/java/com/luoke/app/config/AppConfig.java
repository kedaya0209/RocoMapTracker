package com.luoke.app.config;

import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

@Slf4j
public final class AppConfig {

    public static final String FOLLOW_PLAYER = "跟随玩家";

    // ====================== 配置文件名称 ======================
    private static final String CONFIG_FILE_NAME = "app-config.properties";

    public static boolean SHOW_MONITOR_BORDER = false;

    // ====================== 【内置默认配置】 ======================
    // 资源文件路径（本地资源，不动）
    public static String SOURCE_ROOT_DIR = "/source/";
    public static String SOURCE_INIT = "/source/init";
    public static String MAP_RESOURCE_PATH = "/source/map/map_G.png";
    public static String MAP_RESOURCE_DIR = "/source/map/";
    public static String ICON_DIR = "/source/icon/";
    public static String PLAYER_ICON_PATH = "/source/icon/player.png";
    public static String RESOURCE_ICON_DIR = "/source/point/";
    public static String RESOURCE_POINT_CONFIG_PATH = "/source/point/resource_config.json";

    // ====================== 【新加：网络爬虫地图 URL 数组】 ======================
    public static String[] MAP_REMOTE_URLS = new String[0];
    public static String[] MAP_REMOTE_URL_NAME = new String[0];
    public static int[] MAP_REMOTE_URL_SORT = new int[0];
    public static int MAP_ZOOM = 7;
    public static int MAP_MIN_ZOOM = 4;
    public static int MAP_MAX_ZOOM = 8;
    public static int JSON_ZOOM = 7;
    public static String MAP_RESOURCE_INFO_URL = "https://wiki.biligame.com/rocom/大地图";
    public static String MAP_RESOURCE_POINT_URL = "https://wiki.biligame.com/rocom/Data:Mapnew/point.json";

    // 目标游戏窗口 & 主程序窗口
    public static String TARGET_WINDOW_NAME = "洛克王国：世界";
    public static String APP_MAIN_TITLE = "洛克导航";
    public static int MAIN_WINDOW_DEFAULT_WIDTH = 1000;
    public static int MAIN_WINDOW_DEFAULT_HEIGHT = 700;

    // UI 样式配置
    public static int UI_FONT_SIZE = 14;
    public static int TOP_BAR_SPACING = 12;
    public static int TOP_BAR_PADDING_VERTICAL = 10;
    public static int TOP_BAR_PADDING_HORIZONTAL = 15;

    // 相机 & 视角控制
    public static boolean DEFAULT_FOLLOW_MODE = false;
    public static double DEFAULT_FOLLOW_SCALE = 1.5;
    public static double MIN_SCALE_LIMIT = 0.1;
    public static double MAX_SCALE_LIMIT = 15.0;

    // 玩家图标渲染
    public static double PLAYER_ICON_DRAW_SIZE = 34.0;
    public static double PLAYER_ROTATE_LERP_FACTOR = 0.15;

    // 坐标平滑
    public static double COORDINATE_SMOOTH_FACTOR = 0.8;

    // 目标捕获帧率 FPS
    public static int TARGET_CAPTURE_FPS = 30;

    // 界面状态提示文本
    public static String STATUS_STARTING = "启动中...";
    public static String STATUS_FIND_WINDOW = "查找洛克王国：世界中";
    public static String STATUS_MINIMAP_NOT_FOUND = "❌ 小地图未找到";
    public static String STATUS_MATCH_FAILED = "❌ 匹配失败";
    public static String STATUS_PLAYER_NOT_FOUND = "⚠️ 未找到玩家";
    public static String STATUS_RUNNING = "视奸洛克王国：世界中";

    // ====================== 统计面板显示配置 ======================
    public static boolean SHOW_STATS_MAP_TIME = true;
    public static boolean SHOW_STATS_MATCH_TIME = true;
    public static boolean SHOW_STATS_DIR_TIME = true;
    public static boolean SHOW_STATS_FPS = true;

    // ====================== SIFT 特征匹配配置 ======================
    public static double SCALE_FACTOR = 1.0;
    public static int SIFT_N_FEATURES = 0;
    public static int SIFT_N_OCTAVE_LAYERS = 3;
    public static double SIFT_CONTRAST_THRESHOLD = 0.001;
    public static double SIFT_EDGE_THRESHOLD = 50.0;
    public static double SIFT_SIGMA = 1.6;
    public static boolean SIFT_ENABLE_128 = false;

    // ====================== 匹配过滤阈值 ======================
    public static float MATCH_RATIO_THRESHOLD = 0.6f;
    public static int MATCH_MIN_COUNT = 10;

    // ====================== RANSAC 单应性矩阵参数 ======================
    public static double RANSAC_REPROJ_THRESHOLD = 10.0;
    public static int RANSAC_MAX_ITERS = 200;
    public static double RANSAC_CONFIDENCE = 0.95;

    //采集配置
    public static double GRAY_DISTANCE = 12;

    // ====================== 自动加载配置 ======================
    static {
        loadConfig();
    }

    private AppConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    private static void loadConfig() {
        try {
            File configFile = FileUtil.getRelativeFile(CONFIG_FILE_NAME);
            Properties prop = new Properties();

            if (configFile.exists()) {
                readConfig(configFile, prop);
            } else {
                generateDefaultConfigWithComments(configFile);
                log.info("✅ 已自动生成默认配置文件：{}", configFile.getAbsolutePath());
                readConfig(configFile, prop);
            }

        } catch (Exception e) {
            log.error("❌ 配置加载失败，使用内置默认值", e);
        }
    }

    private static void readConfig(File configFile, Properties prop) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            prop.load(reader);
            log.info("✅ 配置文件加载成功：{}", configFile.getAbsolutePath());
        }
        overrideFromProperties(prop);
    }

    private static void generateDefaultConfigWithComments(File configFile) throws Exception {
        String configContent = """
                # ==============================================
                # 洛克导航 - 配置文件
                # 自动生成，修改后重启程序生效
                # ==============================================
                
                # ---------------- 全局开关 ----------------
                # 是否在被监视窗口显示捕获边框
                show.monitor.border=false
                
                # ---------------- 资源采集（置灰距离） ----------------
                # 玩家靠近多少像素时，可采集资源自动变灰
                gray.distance=12
                
                # ---------------- 本地资源路径 ----------------
                # 根资源目录
                source.root.dir=/source/
                # 初始化标记文件
                source.init=/source/init
                # 大地图图片路径
                map.resource.path=/source/map/map_G.png
                # 地图资源目录
                map.resource.dir=/source/map/
                # 图标目录
                icon.dir=/source/icon/
                # 玩家箭头图标
                player.icon.path=/source/icon/player.png
                # 资源点图标目录
                resource.icon.dir=/source/point/
                # 资源点配置 JSON
                resource.point.config.path=/source/point/resource_config.json
                
                # ---------------- 远程地图瓦片配置 ----------------
                # 地图瓦片 URL（多个用英文逗号分隔）
                map.remote.urls=
                # 地图层级（对应 URL 顺序）
                map.remote.url.sort=
                # 地图名称（对应 URL 顺序）
                map.remote.url.name=
                
                # 地图默认缩放等级
                map.zoom=7
                map.min.zoom=4
                map.max.zoom=8
                # JSON 坐标使用的缩放等级
                json.zoom=7
                
                # ---------------- 地图资源爬取地址 ----------------
                # 大地图信息页
                map.resource.info.url=https://wiki.biligame.com/rocom/大地图
                # 资源点位 JSON
                map.resource.point.url=https://wiki.biligame.com/rocom/Data:Mapnew/point.json
                
                # ---------------- 游戏窗口与程序窗口 ----------------
                # 要捕获的游戏窗口名称
                target.window.name=洛克王国：世界
                # 本程序窗口标题
                app.title=洛克导航
                # 主窗口默认大小
                main.window.width=1000
                main.window.height=700

                # ---------------- UI 样式 ----------------
                # 界面字体大小
                ui.font.size=14
                # 顶部工具栏间距
                ui.top.bar.spacing=12
                ui.top.bar.padding.vertical=10
                ui.top.bar.padding.horizontal=15
                
                # ---------------- 相机视角控制 ----------------
                # 默认是否跟随玩家
                camera.follow.mode.default=false
                # 跟随视角默认缩放
                camera.follow.scale.default=1.5
                # 地图缩放限制
                map.scale.min=0.1
                map.scale.max=15.0
                
                # ---------------- 玩家图标渲染 ----------------
                # 玩家图标绘制大小
                player.icon.draw.size=34.0
                # 玩家旋转平滑系数
                player.rotate.lerp.factor=0.15

                # ---------------- 坐标平滑 ----------------
                coordinate.smooth.factor=0.8

                # ---------------- 捕获帧率 ----------------
                target.capture.fps=30
                
                # ---------------- 状态栏提示文本 ----------------
                status.starting=启动中...
                status.find.window=查找洛克王国：世界中
                status.minimap.not.found=❌ 小地图未找到
                status.match.failed=❌ 地图匹配失败
                status.player.not.found=⚠️ 未找到玩家箭头
                status.running=视奸洛克王国：世界中
                
                # ---------------- 统计面板显示 ----------------
                show.stats.map.time=true
                show.stats.match.time=true
                show.stats.dir.time=true
                show.stats.fps=true

                # ==============================================
                # SIFT 图像匹配核心参数（不懂不要乱改）
                # ==============================================
                # 图像缩放系数（0=自动）
                scale.factor=1.0
                # SIFT 特征点数量（0=全部）
                sift.n.features=0
                # 金字塔层数
                sift.n.octave.layers=3
                # 对比度阈值
                sift.contrast.threshold=0.001
                # 边缘过滤阈值
                sift.edge.threshold=50.0
                # 高斯模糊系数
                sift.sigma=1.6
                # 是否启用 128 维描述符
                sift.enable.128=false
                
                # ---------------- 匹配过滤 ----------------
                match.ratio.threshold=0.6
                match.min.count=10
                
                # ---------------- RANSAC 定位算法 ----------------
                ransac.reproj.threshold=10.0
                ransac.max.iters=200
                ransac.confidence=0.95
                """;

        try (FileOutputStream fos = new FileOutputStream(configFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {

            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);
            writer.write(configContent);
        }
    }

    private static void overrideFromProperties(Properties prop) {
        SOURCE_ROOT_DIR = getStr(prop, "source.root.dir", SOURCE_ROOT_DIR);
        SOURCE_INIT = getStr(prop, "source.init", SOURCE_INIT);
        MAP_RESOURCE_PATH = getStr(prop, "map.resource.path", MAP_RESOURCE_PATH);
        MAP_RESOURCE_DIR = getStr(prop, "map.resource.dir", MAP_RESOURCE_DIR);
        ICON_DIR = getStr(prop, "icon.dir", ICON_DIR);
        PLAYER_ICON_PATH = getStr(prop, "player.icon.path", PLAYER_ICON_PATH);
        RESOURCE_ICON_DIR = getStr(prop, "resource.icon.dir", RESOURCE_ICON_DIR);
        RESOURCE_POINT_CONFIG_PATH = getStr(prop, "resource.point.config.path", RESOURCE_POINT_CONFIG_PATH);

        SHOW_MONITOR_BORDER = getBool(prop, "show.monitor.border", SHOW_MONITOR_BORDER);

        MAP_REMOTE_URLS = getStrArray(prop, "map.remote.urls");
        MAP_REMOTE_URL_SORT = getIntArray(prop, "map.remote.url.sort");
        MAP_REMOTE_URL_NAME = getStrArray(prop, "map.remote.url.name");

        MAP_ZOOM = getInt(prop, "map.zoom", MAP_ZOOM);
        MAP_MIN_ZOOM = getInt(prop, "map.min.zoom", MAP_MIN_ZOOM);
        MAP_MAX_ZOOM = getInt(prop, "map.max.zoom", MAP_MAX_ZOOM);
        JSON_ZOOM = getInt(prop, "json.zoom", JSON_ZOOM);

        MAP_RESOURCE_INFO_URL = clean(getStr(prop, "map.resource.info.url", MAP_RESOURCE_INFO_URL));
        MAP_RESOURCE_POINT_URL = clean(getStr(prop, "map.resource.point.url", MAP_RESOURCE_POINT_URL));

        TARGET_WINDOW_NAME = getStr(prop, "target.window.name", TARGET_WINDOW_NAME);
        APP_MAIN_TITLE = getStr(prop, "app.title", APP_MAIN_TITLE);
        MAIN_WINDOW_DEFAULT_WIDTH = getInt(prop, "main.window.width", MAIN_WINDOW_DEFAULT_WIDTH);
        MAIN_WINDOW_DEFAULT_HEIGHT = getInt(prop, "main.window.height", MAIN_WINDOW_DEFAULT_HEIGHT);

        UI_FONT_SIZE = getInt(prop, "ui.font.size", UI_FONT_SIZE);
        TOP_BAR_SPACING = getInt(prop, "ui.top.bar.spacing", TOP_BAR_SPACING);
        TOP_BAR_PADDING_VERTICAL = getInt(prop, "ui.top.bar.padding.vertical", TOP_BAR_PADDING_VERTICAL);
        TOP_BAR_PADDING_HORIZONTAL = getInt(prop, "ui.top.bar.padding.horizontal", TOP_BAR_PADDING_HORIZONTAL);

        DEFAULT_FOLLOW_MODE = getBool(prop, "camera.follow.mode.default", DEFAULT_FOLLOW_MODE);
        DEFAULT_FOLLOW_SCALE = getDouble(prop, "camera.follow.scale.default", DEFAULT_FOLLOW_SCALE);
        MIN_SCALE_LIMIT = getDouble(prop, "map.scale.min", MIN_SCALE_LIMIT);
        MAX_SCALE_LIMIT = getDouble(prop, "map.scale.max", MAX_SCALE_LIMIT);

        PLAYER_ICON_DRAW_SIZE = getDouble(prop, "player.icon.draw.size", PLAYER_ICON_DRAW_SIZE);
        PLAYER_ROTATE_LERP_FACTOR = getDouble(prop, "player.rotate.lerp.factor", PLAYER_ROTATE_LERP_FACTOR);
        COORDINATE_SMOOTH_FACTOR = getDouble(prop, "coordinate.smooth.factor", COORDINATE_SMOOTH_FACTOR);
        TARGET_CAPTURE_FPS = getInt(prop, "target.capture.fps", TARGET_CAPTURE_FPS);

        SHOW_STATS_MAP_TIME = getBool(prop, "show.stats.map.time", SHOW_STATS_MAP_TIME);
        SHOW_STATS_MATCH_TIME = getBool(prop, "show.stats.match.time", SHOW_STATS_MATCH_TIME);
        SHOW_STATS_DIR_TIME = getBool(prop, "show.stats.dir.time", SHOW_STATS_DIR_TIME);
        SHOW_STATS_FPS = getBool(prop, "show.stats.fps", SHOW_STATS_FPS);

        STATUS_STARTING = getStr(prop, "status.starting", STATUS_STARTING);
        STATUS_FIND_WINDOW = getStr(prop, "status.find.window", STATUS_FIND_WINDOW);
        STATUS_MINIMAP_NOT_FOUND = getStr(prop, "status.minimap.not.found", STATUS_MINIMAP_NOT_FOUND);
        STATUS_MATCH_FAILED = getStr(prop, "status.match.failed", STATUS_MATCH_FAILED);
        STATUS_PLAYER_NOT_FOUND = getStr(prop, "status.player.not.found", STATUS_PLAYER_NOT_FOUND);
        STATUS_RUNNING = getStr(prop, "status.running", STATUS_RUNNING);

        SCALE_FACTOR = getDouble(prop, "scale.factor", SCALE_FACTOR);
        SIFT_N_FEATURES = getInt(prop, "sift.n.features", SIFT_N_FEATURES);
        SIFT_N_OCTAVE_LAYERS = getInt(prop, "sift.n.octave.layers", SIFT_N_OCTAVE_LAYERS);
        SIFT_CONTRAST_THRESHOLD = getDouble(prop, "sift.contrast.threshold", SIFT_CONTRAST_THRESHOLD);
        SIFT_EDGE_THRESHOLD = getDouble(prop, "sift.edge.threshold", SIFT_EDGE_THRESHOLD);
        SIFT_SIGMA = getDouble(prop, "sift.sigma", SIFT_SIGMA);
        SIFT_ENABLE_128 = getBool(prop, "sift.enable.128", SIFT_ENABLE_128);

        MATCH_RATIO_THRESHOLD = (float) getDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        MATCH_MIN_COUNT = getInt(prop, "match.min.count", MATCH_MIN_COUNT);

        RANSAC_REPROJ_THRESHOLD = getDouble(prop, "ransac.reproj.threshold", RANSAC_REPROJ_THRESHOLD);
        RANSAC_MAX_ITERS = getInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        RANSAC_CONFIDENCE = getDouble(prop, "ransac.confidence", RANSAC_CONFIDENCE);

        GRAY_DISTANCE = getDouble(prop, "gray.distance", GRAY_DISTANCE);
    }

    private static String getStr(Properties prop, String key, String def) {
        String val = prop.getProperty(key);
        return val == null ? def : val.trim();
    }

    private static int getInt(Properties prop, String key, int def) {
        try {
            return Integer.parseInt(prop.getProperty(key).trim());
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
        try {
            return Boolean.parseBoolean(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String[] getStrArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new String[0];
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .toArray(String[]::new);
    }

    private static int[] getIntArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new int[0];
        try {
            return Arrays.stream(s.split(","))
                    .map(String::trim)
                    .mapToInt(Integer::parseInt)
                    .toArray();
        } catch (Exception e) {
            return new int[0];
        }
    }

    private static String clean(String url) {
        return url.replace("\"", "").replace("'", "").replace(";", "").trim();
    }
}