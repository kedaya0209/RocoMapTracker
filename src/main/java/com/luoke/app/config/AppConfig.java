package com.luoke.app.config;

import com.luoke.app.utils.FileUtil;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

/**
 * 洛克导航 - 全局配置类
 * 功能：
 * 1. 程序启动时自动检查运行目录是否存在 app-config.properties
 * 2. 无配置文件 → 自动生成带注释的默认配置
 * 3. 有配置文件 → 直接读取并覆盖默认值
 * 4. 兼容 IDE / JAR / GraalVM 原生镜像
 */
@Slf4j
public final class AppConfig {

    public static final String FOLLOW_PLAYER = "视奸玩家";

    // ====================== 窗口捕获模式 ======================
    // 可选值：push = Rust推送模式（高性能），poll = Java轮询模式
    public static String CAPTURE_MODE = "poll";

    // ====================== 配置文件名称 ======================
    private static final String CONFIG_FILE_NAME = "app-config.properties";

    // ====================== 【内置默认配置】 ======================
    // 资源文件路径（本地资源，不动）
    public static String MAP_RESOURCE_PATH = "/source/map/map_0.png";
    public static String PLAYER_ICON_PATH = "/source/icon/player.png";
    public static String RESOURCE_ICON_PATH = "/source/icon/categories.json";
    public static String RESOURCE_POINT_PATH = "/source/point/points.json";

    // ====================== 【新加：网络爬虫地图 URL 数组】 ======================
    public static String[] MAP_REMOTE_URLS;
    public static String[] MAP_REMOTE_URL_NAME;
    public static int[] MAP_REMOTE_URL_SORT;
    public static int MAP_ZOOM = 7;
    public static int MAP_MIN_ZOOM;
    public static int MAP_MAX_ZOOM;
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
    /**
     * SIFT 提取的最大特征点数量
     * 0 = 自动提取全部
     * 调大：特征更多、更准 → 更慢
     * 调小：特征更少、更快 → 容易匹配失败
     */
    public static int SIFT_N_FEATURES = 0;

    /**
     * 高斯金字塔层数（通常 3~4）
     * 调大：对缩放更鲁棒、更远更小也能识别 → 更慢
     * 调小：更快 → 对缩放变化敏感
     */
    public static int SIFT_N_OCTAVE_LAYERS = 3;

    /**
     * 对比度阈值，过滤弱对比度特征
     * 调大：过滤更多噪声点，更干净 → 特征变少
     * 调小：保留更多特征 → 干扰变多
     */
    public static double SIFT_CONTRAST_THRESHOLD = 0.001;

    /**
     * 边缘阈值，过滤边缘伪特征
     * 调大：更容易把边缘当特征 → 易误匹配
     * 调小：过滤边缘更干净 → 特征变少
     */
    public static double SIFT_EDGE_THRESHOLD = 50.0;

    /**
     * 高斯模糊系数 sigma
     * 调大：更模糊，适合模糊画面、抗锯齿
     * 调小：更锐利，适合清晰画面
     */
    public static double SIFT_SIGMA = 1.6;

    /**
     * 是否使用 128 维描述子
     * true：128维，精度更高 → 更慢更占内存
     * false：64维，速度更快 → 精度足够日常使用
     */
    public static boolean SIFT_ENABLE_128 = false;

    // ====================== 匹配过滤阈值 ======================
    /**
     * 匹配过滤比例阈值（Lowe 比率）
     * 调大（0.6→0.8）：更宽松，更容易匹配成功 → 易飘、误匹配多
     * 调小（0.6→0.4）：更严格，误匹配少 → 可能丢正确匹配
     */
    public static float MATCH_RATIO_THRESHOLD = 0.6f;

    /**
     * 最小有效匹配点数，低于则判定匹配失败
     * 调大：更稳，不易误触 → 更难匹配成功
     * 调小：更容易匹配成功 → 易误定位
     */
    public static int MATCH_MIN_COUNT = 10;

    // ====================== RANSAC 单应性矩阵参数 ======================
    /**
     * RANSAC 重投影误差阈值
     * 调大：容错更高，抖动画面也能稳住 → 定位精度下降
     * 调小：定位更精准 → 容错低，易丢失目标
     */
    public static double RANSAC_REPROJ_THRESHOLD = 10.0;

    /**
     * RANSAC 最大迭代次数
     * 调大：计算更稳健 → 更慢
     * 调小：更快 → 可能计算偏差变大
     */
    public static int RANSAC_MAX_ITERS = 200;

    /**
     * RANSAC 置信度
     * 调大（0.99）：抗干扰更强 → 略慢
     * 调小（0.90）：更快 → 稳定性下降
     */
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
        // 【强制 UTF-8 读取】
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            prop.load(reader);
            log.info("✅ 配置文件加载成功：{}", configFile.getAbsolutePath());
        }
        overrideFromProperties(prop);
    }

    /**
     * 生成带完整中文注释的配置文件（UTF-8 BOM 彻底解决乱码）
     */
    private static void generateDefaultConfigWithComments(File configFile) throws Exception {
        String configContent = """
                # ==============================================
                # 洛克导航 - 配置文件
                # 自动生成，修改后重启程序生效
                # ==============================================
                
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
                
                # url图层顺序，默认 大陆表层，-1，-2
                map.remote.url.name=G,B1,B2
                
                # wikiUrl
                map.resource.info.url = "https://wiki.biligame.com/rocom/大地图";
                
                # 资源点位
                map.resource.point.url = "https://wiki.biligame.com/rocom/Data:Mapnew/point.json";
                
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
                
                # ---------------- 窗口捕获模式 ----------------
                # 可选值：
                # push  = Rust 内部推送模式（高性能、低延迟、等Java处理完再推下一帧）【推荐】
                # poll  = Java 主动轮询模式（定时拉取，兼容性更好）
                capture.mode=push
                
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

            // 写入 UTF-8 BOM，Windows 记事本必认
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            writer.write(configContent);
        }
    }

    /**
     * 从配置文件覆盖默认值
     */
    private static void overrideFromProperties(Properties prop) {
        MAP_RESOURCE_PATH = getProp(prop, "map.resource.path", MAP_RESOURCE_PATH);
        PLAYER_ICON_PATH = getProp(prop, "player.icon.path", PLAYER_ICON_PATH);

        // ====================== 【加载远程地图数组】 ======================
        MAP_REMOTE_URLS = Arrays.stream(
                        getProp(prop, "map.remote.urls", "")
                                .split(","))
                .map(AppConfig::format)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        MAP_REMOTE_URL_SORT = Arrays.stream(
                        getProp(prop, "map.remote.url.sort", "")
                                .split(","))
                .mapToInt(Integer::parseInt)
                .toArray();

        MAP_REMOTE_URL_NAME = Arrays.stream(
                        getProp(prop, "map.remote.urls", "")
                                .split(","))
                .map(AppConfig::format)
                .filter(s -> !s.isBlank())
                .toArray(String[]::new);

        MAP_RESOURCE_INFO_URL = format(getProp(prop, "map.resource.info.url", MAP_RESOURCE_INFO_URL));
        MAP_RESOURCE_POINT_URL = format(getProp(prop, "map.resource.point.url", MAP_RESOURCE_POINT_URL));

        TARGET_WINDOW_NAME = getProp(prop, "target.window.name", TARGET_WINDOW_NAME);
        APP_MAIN_TITLE = getProp(prop, "app.title", APP_MAIN_TITLE);
        MAIN_WINDOW_DEFAULT_WIDTH = getPropInt(prop, "main.window.width", MAIN_WINDOW_DEFAULT_WIDTH);
        MAIN_WINDOW_DEFAULT_HEIGHT = getPropInt(prop, "main.window.height", MAIN_WINDOW_DEFAULT_HEIGHT);

        UI_FONT_SIZE = getPropInt(prop, "ui.font.size", UI_FONT_SIZE);
        TOP_BAR_SPACING = getPropInt(prop, "ui.top.bar.spacing", TOP_BAR_SPACING);
        TOP_BAR_PADDING_VERTICAL = getPropInt(prop, "ui.top.bar.padding.vertical", TOP_BAR_PADDING_VERTICAL);
        TOP_BAR_PADDING_HORIZONTAL = getPropInt(prop, "ui.top.bar.padding.horizontal", TOP_BAR_PADDING_HORIZONTAL);

        DEFAULT_FOLLOW_MODE = getPropBool(prop, "camera.follow.mode.default", DEFAULT_FOLLOW_MODE);
        DEFAULT_FOLLOW_SCALE = getPropDouble(prop, "camera.follow.scale.default", DEFAULT_FOLLOW_SCALE);
        MIN_SCALE_LIMIT = getPropDouble(prop, "map.scale.min", MIN_SCALE_LIMIT);
        MAX_SCALE_LIMIT = getPropDouble(prop, "map.scale.max", MAX_SCALE_LIMIT);

        PLAYER_ICON_DRAW_SIZE = getPropDouble(prop, "player.icon.draw.size", PLAYER_ICON_DRAW_SIZE);
        PLAYER_ROTATE_LERP_FACTOR = getPropDouble(prop, "player.rotate.lerp.factor", PLAYER_ROTATE_LERP_FACTOR);

        COORDINATE_SMOOTH_FACTOR = getPropDouble(prop, "coordinate.smooth.factor", COORDINATE_SMOOTH_FACTOR);
        TARGET_CAPTURE_FPS = getPropInt(prop, "target.capture.fps", TARGET_CAPTURE_FPS);

        CAPTURE_MODE = getProp(prop, "capture.mode", CAPTURE_MODE);

        SHOW_STATS_MAP_TIME = getPropBool(prop, "show.stats.map.time", SHOW_STATS_MAP_TIME);
        SHOW_STATS_MATCH_TIME = getPropBool(prop, "show.stats.match.time", SHOW_STATS_MATCH_TIME);
        SHOW_STATS_DIR_TIME = getPropBool(prop, "show.stats.dir.time", SHOW_STATS_DIR_TIME);
        SHOW_STATS_FPS = getPropBool(prop, "show.stats.fps", SHOW_STATS_FPS);

        STATUS_STARTING = getProp(prop, "status.starting", STATUS_STARTING);
        STATUS_MINIMAP_NOT_FOUND = getProp(prop, "status.minimap.not.found", STATUS_MINIMAP_NOT_FOUND);
        STATUS_MATCH_FAILED = getProp(prop, "status.match.failed", STATUS_MATCH_FAILED);
        STATUS_PLAYER_NOT_FOUND = getProp(prop, "status.player.not.found", STATUS_PLAYER_NOT_FOUND);
        STATUS_RUNNING = getProp(prop, "status.running", STATUS_RUNNING);

        // SIFT & 匹配配置
        SIFT_N_FEATURES = getPropInt(prop, "sift.n.features", SIFT_N_FEATURES);
        SIFT_N_OCTAVE_LAYERS = getPropInt(prop, "sift.n.octave.layers", SIFT_N_OCTAVE_LAYERS);
        SIFT_CONTRAST_THRESHOLD = getPropDouble(prop, "sift.contrast.threshold", SIFT_CONTRAST_THRESHOLD);
        SIFT_EDGE_THRESHOLD = getPropDouble(prop, "sift.edge.threshold", SIFT_EDGE_THRESHOLD);
        SIFT_SIGMA = getPropDouble(prop, "sift.sigma", SIFT_SIGMA);
        SIFT_ENABLE_128 = getPropBool(prop, "sift.enable.128", SIFT_ENABLE_128);

        MATCH_RATIO_THRESHOLD = (float) getPropDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        MATCH_MIN_COUNT = getPropInt(prop, "match.min.count", MATCH_MIN_COUNT);

        RANSAC_REPROJ_THRESHOLD = getPropDouble(prop, "ransac.reproj.threshold", RANSAC_REPROJ_THRESHOLD);
        RANSAC_MAX_ITERS = getPropInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        RANSAC_CONFIDENCE = getPropDouble(prop, "ransac.confidence", RANSAC_CONFIDENCE);
    }

    // ====================== 工具方法 ======================
    private static String getProp(Properties prop, String key, String def) {
        return prop.getProperty(key, def);
    }

    private static int getPropInt(Properties prop, String key, int def) {
        try {
            return Integer.parseInt(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static double getPropDouble(Properties prop, String key, double def) {
        try {
            return Double.parseDouble(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean getPropBool(Properties prop, String key, boolean def) {
        try {
            return Boolean.parseBoolean(prop.getProperty(key).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String format(String url) {
        if (url == null) return null;
        return url.trim()
                .replace("\"", "")
                .replace("'", "")
                .replace(";", "")
                .strip();
    }
}