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
    public static String MAP_RESOURCE_PATH = "/source/map/map_0.png";
    public static String PLAYER_ICON_PATH = "/source/icon/player.png";
    public static String RESOURCE_ICON_PATH = "/source/icon/categories.json";
    public static String RESOURCE_POINT_PATH = "/source/point/points.json";

    // ====================== 【新加：网络爬虫地图 URL 数组】 ======================
    public static String[] MAP_REMOTE_URLS = new String[0];
    public static String[] MAP_REMOTE_URL_NAME = new String[0];
    public static int[] MAP_REMOTE_URL_SORT = new int[0];
    public static int MAP_ZOOM = 7;
    public static int MAP_MIN_ZOOM = 4;
    public static int MAP_MAX_ZOOM = 8;
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
    public static String STATUS_FIND_WINDOW = "查找" + TARGET_WINDOW_NAME + "中";
    public static String STATUS_MINIMAP_NOT_FOUND = "❌ 小地图未找到";
    public static String STATUS_MATCH_FAILED = "❌ 匹配失败";
    public static String STATUS_PLAYER_NOT_FOUND = "⚠️ 未找到玩家";
    public static String STATUS_RUNNING = "视奸" + TARGET_WINDOW_NAME + "中";

    // ====================== 统计面板显示配置 ======================
    public static boolean SHOW_STATS_MAP_TIME = true;
    public static boolean SHOW_STATS_MATCH_TIME = true;
    public static boolean SHOW_STATS_DIR_TIME = true;
    public static boolean SHOW_STATS_FPS = true;

    // ====================== SIFT 特征匹配配置 ======================
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
        // ====================== 完全保留你原来的注释 ======================
        String configContent = """
                # ==============================================
                # 洛克导航 - 配置文件
                # 自动生成，修改后重启程序生效
                # ==============================================
                #
                #是否在被监视的窗口显示边框
                show.monitor.border=false
                #
                # ---------------- 资源路径 ----------------
                # 本地大地图图片路径
                map.resource.path=/source/map/map_0.png
                
                # ---------------- 【新加：远程地图 URL 数组，逗号分隔】 ----------------
                # 目前有大陆、地下第一层、地下第二层，
                # 没有配置的话自动从 map.resource.info.url 解析
                # https://wiki-dev-patch-oss.oss-cn-hangzhou.aliyuncs.com/res/lkwg/map-3.0/7/tile-{x}_{y}.png
                # https://wiki-dev-patch-oss.oss-cn-hangzhou.aliyuncs.com/res/lkwg/map-1.0/tiles-B1/7/tile-{x}_{y}.png
                # https://wiki-dev-patch-oss.oss-cn-hangzhou.aliyuncs.com/res/lkwg/map-1.0/tiles-B2/7/tile-{x}_{y}.png
                # map.remote.urls=https://wiki-dev-patch-oss.oss-cn-hangzhou.aliyuncs.com/res/lkwg/map-3.0/7/tile-{x}_{y}.png,https://wiki-dev-patch-oss.oss-cn-hangzhou.aliyuncs.com/res/lkwg/map-1.0/tiles-B1/7/tile-{x}_{y}.png,https://wiki-dev-patch-oss.oss-cn-hangzhou.aliyuncs.com/res/lkwg/map-1.0/tiles-B2/7/tile-{x}_{y}.png
                
                # url图层顺序，默认 大陆表层，-1，-2
                map.remote.url.sort=0,-1,-2
                
                # 图层名称，默认 大陆表层，B1，B2
                map.remote.url.name=G,B1,B2
                
                # wiki配置页URL
                map.resource.info.url=https://wiki.biligame.com/rocom/大地图
                
                # 资源点位json
                map.resource.point.url=https://wiki.biligame.com/rocom/Data:Mapnew/point.json
                
                # 玩家箭头图标路径
                player.icon.path=/source/icon/player.png
                
                # ---------------- 窗口配置 ----------------
                # 捕获的游戏窗口名称
                target.window.name=洛克王国：世界
                
                # 程序主窗口标题
                app.title=洛克导航
                
                # 主窗口默认宽高
                main.window.width=1000
                main.window.height=700
                
                # ---------------- UI 样式 ----------------
                # 界面字体大小
                ui.font.size=14
                
                # 顶部工具栏间距
                ui.top.bar.spacing=12
                ui.top.bar.padding.vertical=10
                ui.top.bar.padding.horizontal=15
                
                # ---------------- 相机视角 ----------------
                # 默认是否开启跟随玩家
                camera.follow.mode.default=false
                
                # 跟随模式下的缩放比例
                camera.follow.scale.default=1.5
                
                # 地图缩放限制
                map.scale.min=0.1
                map.scale.max=15.0
                
                # ---------------- 玩家渲染 ----------------
                # 玩家图标大小
                player.icon.draw.size=34.0
                
                # 玩家旋转平滑系数（越小越丝滑）
                player.rotate.lerp.factor=0.15
                
                # ---------------- 坐标平滑 ----------------
                coordinate.smooth.factor=0.8
                
                # ---------------- 捕获帧率 ----------------
                # 目标捕获帧率（越高越流畅，越占CPU）
                target.capture.fps=30
                
                # ---------------- 窗口模糊匹配关键词 ----------------
                # 作用：自动查找标题包含该关键词的窗口，实现模糊匹配
                # 示例：洛克王国 | 微信 | Chrome
                monitor.pattern=洛克王国
                
                # ---------------- 统计面板显示开关 ----------------
                show.stats.map.time=true
                show.stats.match.time=true
                show.stats.dir.time=true
                show.stats.fps=true
                
                # ---------------- 状态提示文本 ----------------
                status.starting=启动中...
                status.minimap.not.found=❌ 小地图未找到
                status.match.failed=❌ 匹配失败
                status.player.not.found=⚠️ 未找到玩家
                status.running=视奸洛克王国：世界中
                
                # ==============================================
                # SIFT 特征匹配参数（图像识别核心）
                # ==============================================
                # SIFT 特征点数量：0=自动提取全部
                # 调大=更准更慢 | 调小=更快易失败
                sift.n.features=0
                
                # 金字塔层数：3~4，越高对缩放越鲁棒但更慢
                sift.n.octave.layers=3
                
                # 对比度阈值：越高过滤噪声越多，特征越少
                sift.contrast.threshold=0.001
                
                # 边缘阈值：越高越容易误匹配边缘
                sift.edge.threshold=50.0
                
                # 高斯模糊系数：越大越适合模糊画面
                sift.sigma=1.6
                
                # 是否使用128维描述子：true=更准更慢 | false=更快
                sift.enable.128=false
                
                # ---------------- 匹配过滤阈值 ----------------
                # 匹配率阈值：0.6~0.8，越小越严格
                match.ratio.threshold=0.6
                
                # 最小有效匹配点：低于该值判定匹配失败
                match.min.count=10
                
                # ---------------- RANSAC 定位参数 ----------------
                # 重投影误差阈值：越大容错越高，定位精度越低
                ransac.reproj.threshold=10.0
                
                # 最大迭代次数：越高越稳越慢
                ransac.max.iters=200
                
                # 置信度：0.95~0.99，越高越鲁棒
                ransac.confidence=0.95
                """;

        try (FileOutputStream fos = new FileOutputStream(configFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {

            // UTF-8 BOM 保证 Windows 记事本不乱码
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);
            writer.write(configContent);
        }
    }

    private static void overrideFromProperties(Properties prop) {
        MAP_RESOURCE_PATH = getStr(prop, "map.resource.path", MAP_RESOURCE_PATH);
        PLAYER_ICON_PATH = getStr(prop, "player.icon.path", PLAYER_ICON_PATH);

        SHOW_MONITOR_BORDER = getBool(prop, "show.monitor.border", SHOW_MONITOR_BORDER);

        MAP_REMOTE_URLS = getStrArray(prop, "map.remote.urls");
        MAP_REMOTE_URL_SORT = getIntArray(prop, "map.remote.url.sort");
        MAP_REMOTE_URL_NAME = getStrArray(prop, "map.remote.url.name");

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
        STATUS_MINIMAP_NOT_FOUND = getStr(prop, "status.minimap.not.found", STATUS_MINIMAP_NOT_FOUND);
        STATUS_MATCH_FAILED = getStr(prop, "status.match.failed", STATUS_MATCH_FAILED);
        STATUS_PLAYER_NOT_FOUND = getStr(prop, "status.player.not.found", STATUS_PLAYER_NOT_FOUND);
        STATUS_RUNNING = getStr(prop, "status.running", STATUS_RUNNING);

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
    }

    // ====================== 安全获取工具 ======================
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