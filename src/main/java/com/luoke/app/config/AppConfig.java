package com.luoke.app.config;

import com.luoke.app.utils.ResourceUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

/**
 * 应用程序配置管理类
 * <p>
 * 负责加载、管理和提供应用程序的所有配置参数。
 * 配置来源优先级：
 * <ol>
 *   <li>外部配置文件（app_config.properties）- 最高优先级</li>
 *   <li>内置默认值 - 作为备选方案</li>
 * </ol>
 * </p>
 * <p>
 * 设计特点：
 * <ul>
 *   <li>工具类设计（私有构造函数 + 静态方法）</li>
 *   <li>配置自动加载（静态代码块）</li>
 *   <li>首次运行自动生成带注释的配置文件</li>
 *   <li>支持多种数据类型的配置项（字符串、整数、浮点数、布尔值、数组）</li>
 * </ul>
 * </p>
 * <p>
 * 配置类别：
 * <ul>
 *   <li>全局开关：如显示捕获边框</li>
 *   <li>资源路径：本地资源和远程资源URL</li>
 *   <li>窗口配置：游戏窗口和程序窗口的设置</li>
 *   <li>UI样式：字体大小、间距、内边距等</li>
 *   <li>相机控制：缩放、跟随模式等</li>
 *   <li>SIFT算法参数：图像特征匹配的核心参数</li>
 *   <li>RANSAC参数：单应性矩阵计算参数</li>
 *   <li>性能优化：帧率、平滑系数等</li>
 * </ul>
 * </p>
 * <p>
 * 内存生命周期：
 * <ul>
 *   <li>所有配置项都是静态变量，在类加载时初始化</li>
   *   <li>配置加载在静态代码块中完成，保证只执行一次</li>
   *   <li>字符串数组使用new String[0]作为空值，避免NPE</li>
 * </ul>
 * </p>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public final class AppConfig {

    /**
     * 跟随玩家模式文本标签
     * <p>
     * 用于UI中"跟随玩家"复选框的显示文本
     * </p>
     */
    public static final String FOLLOW_PLAYER = "跟随玩家";

    /**
     * 模型文件目录路径
     * <p>
     * 存放AI模型文件（如OCR识别模型）的相对路径
     * </p>
     */
    public static final String MODEL_DIR = "/model/";

    /**
     * OCR识别模型文件名
     * <p>
     * 使用PaddleOCR的移动端轻量级识别模型
     * .onnx格式是Open Neural Network Exchange的跨平台模型格式
     * </p>
     */
    public static final String OCR_REC_MODEL = "ch_PP-OCRv4_rec_mobile.onnx";

    /**
     * OCR检测模型文件名
     * <p>
     * 负责检测文本区域的模型
     * 与识别模型配合使用完成OCR任务
     * </p>
     */
    public static final String OCR_DET_MODEL = "ch_PP-OCRv4_det_mobile.onnx";

    /**
     * PaddleOCR字符集文件名
     * <p>
     * 包含OCR支持的字符列表，用于识别结果解码
     * </p>
     */
    public static final String PPOCR_KEYS = "ppocr_keys_v1.txt";

    /**
     * OCR核心线程池大小
     * <p>
     * Native资源管理说明：
     * 每个PaddleOCR实例都需要加载Native模型到内存，占用较大内存
     * 使用对象池复用OCR实例，避免频繁创建销毁带来的性能开销
     * 默认值2是平衡并发处理能力和内存占用的建议值
     * </p>
     */
    //ORC对象池
    public static int OCR_CORE_SIZE = 2;

    /**
     * 配置文件名称
     * <p>
     * 外部配置文件的基本文件名
     * 会被ResourceUtils.getExternalFile()解析为绝对路径
     * </p>
     */
    // ====================== 配置文件名称 ======================
    private static final String CONFIG_FILE_NAME = "app_config.properties";

    /**
     * 是否在被监视窗口显示捕获边框
     * <p>
     * 开发调试选项，用于在游戏窗口上绘制捕获区域的边框
     * 关闭时对性能有微小提升（每帧少一次绘制操作）
     * </p>
     */
    public static boolean SHOW_MONITOR_BORDER = false;

    /**
     * 是否启用资源采集
     */
    public static boolean MATERIAL_COLLECTION = false;

    /**
     * 玩家图标模式
     * <p>
     * 可选值：
     * <ul>
     *   <li>"cutter" - 从小地图裁剪真实箭头图像，准确性高但可能受旋转影响</li>
     *   <li>"simulation" - 使用算法模拟箭头方向，大部分时间稳定</li>
     * </ul>
     * </p>
     */
    public static String PLAYER_ICON_MODEL = "cutter";

    // ====================== 【内置默认配置】 ======================

    /**
     * 资源文件根目录
     * <p>
     * 本地资源文件的根目录，相对于应用程序工作目录
     * 不动的基础资源路径，不建议修改
     * </p>
     */
    // 资源文件路径（本地资源，不动）
    public static String SOURCE_ROOT_DIR = "/source/";

    /**
     * 初始化标记文件路径
     * <p>
     * 用于标记资源文件已完成首次下载和初始化
     * 文件存在则跳过首次资源下载步骤
     * </p>
     */
    public static String SOURCE_INIT = "/source/init";

    /**
     * 大地图图片路径
     * <p>
     * 用于SIFT特征匹配的完整大地图图像
     * 大地图是整个游戏世界的完整地图
     * </p>
     */
    public static String MAP_RESOURCE_PATH = "/source/map/map_G.png";

    /**
     * 地图资源目录
     * <p>
     * 存放地图相关资源（如不同缩放级别的地图瓦片）的目录
     * </p>
     */
    public static String MAP_RESOURCE_DIR = "/source/map/";

    /**
     * 图标资源目录
     * <p>
     * 存放UI图标（如玩家图标、各种状态图标）的目录
     * </p>
     */
    public static String ICON_DIR = "/source/icon/";

    /**
     * 玩家图标路径
     * <p>
     * 在大地图上显示的玩家位置图标
     * PNG格式支持透明背景，适合图标显示
     * </p>
     */
    public static String PLAYER_ICON_PATH = "/source/icon/player.png";

    /**
     * 资源点图标目录
     * <p>
     * 存放地图上各种资源点（如宝箱、NPC、怪物等）的图标
     * </p>
     */
    public static String RESOURCE_ICON_DIR = "/source/point/";

    /**
     * 资源点配置文件路径
     * <p>
     * JSON格式的配置文件，包含地图上所有资源点的位置和属性信息
     * 程序启动时解析此文件并初始化资源点上下文
     * </p>
     */
    public static String RESOURCE_POINT_CONFIG_PATH = "/source/point/resource_config.json";

    // ====================== 【新加：网络爬虫地图 URL 数组】 ======================

    /**
     * 地图瓦片远程URL数组
     * <p>
     * 支持从网络加载分块的地图瓦片，减少大地图的内存占用
     * 多个URL可配置不同缩放级别或不同图层
     * </p>
     */
    public static String[] MAP_REMOTE_URLS = new String[0];

    /**
     * 地图瓦URL对应的名称数组
     * <p>
     * 用于UI中显示不同地图图层的名称
     * 与MAP_REMOTE_URLS一一对应
     * </p>
     */
    public static String[] MAP_REMOTE_URL_NAME = new String[0];

    /**
     * 地图URL对应的排序数组
     * <p>
     * 用于指定不同地图图层的显示优先级和层级关系
     * </p>
     */
    public static int[] MAP_REMOTE_URL_SORT = new int[0];

    /**
     * 地图默认缩放级别
     * <p>
     * 程序启动时的地图缩放等级
     * 值越大显示的地图范围越小，细节越清晰
     * </p>
     */
    public static int MAP_ZOOM = 7;

    /**
     * 地图最小缩放级别
     * <p>
     * 用户可以缩放到的最小层级
     * 最小层级显示的地图范围最大，细节最模糊
     * </p>
     */
    public static int MAP_MIN_ZOOM = 4;

    /**
     * 地图最大缩放级别
     * <p>
     * 用户可以缩放到的最大层级
     * 最大层级显示的地图范围最小，细节最清晰
     * </p>
     */
    public static int MAP_MAX_ZOOM = 8;

    /**
     * JSON坐标使用的缩放级别
     * <p>
     * 资源点配置文件中的坐标是基于哪个缩放级别的
     * 需要根据当前缩放级别进行坐标转换
     * </p>
     */
    public static int JSON_ZOOM = 7;

    /**
     * 大地图信息页面URL
     * <p>
     * Wiki页面的URL，包含大地图的相关信息
     * 可用于获取地图元数据或验证地图版本
     * </p>
     */
    public static String MAP_RESOURCE_INFO_URL = "https://wiki.biligame.com/rocom/大地图";

    /**
     * 资源点位JSON文件URL
     * <p>
     * 远程获取最新资源点配置的URL
     * 支持在线更新资源点数据
     * </p>
     */
    public static String MAP_RESOURCE_POINT_URL = "https://wiki.biligame.com/rocom/Data:Mapnew/point.json";

    // 目标游戏窗口 & 主程序窗口

    /**
     * 目标游戏窗口名称
     * <p>
     * 需要捕获的Windows窗口的标题
     * WindowsMonitor通过此名称查找目标窗口
     * </p>
     */
    public static String TARGET_WINDOW_NAME = "洛克王国：世界";

    /**
     * 应用程序主窗口标题
     * <p>
     * 本程序窗口显示在标题栏的文本
     * </p>
     */
    public static String APP_MAIN_TITLE = "洛克导航";

    /**
     * 主窗口默认宽度
     * <p>
     * 单位：像素
     * 首次启动时窗口的宽度
     * </p>
     */
    public static int MAIN_WINDOW_DEFAULT_WIDTH = 1000;

    /**
     * 主窗口默认高度
     * <p>
     * 单位：像素
     * 首次启动时窗口的高度
     * </p>
     */
    public static int MAIN_WINDOW_DEFAULT_HEIGHT = 700;

    // UI 样式配置

    /**
     * UI字体大小
     * <p>
     * 单位：像素
     * 控制所有文本控件的字体大小
     * </p>
     */
    public static int UI_FONT_SIZE = 14;

    /**
     * 顶部工具栏控件间距
     * <p>
     * 单位：像素
     * 控制工具栏中各个控件之间的水平间距
     * </p>
     */
    public static int TOP_BAR_SPACING = 12;

    /**
     * 顶部工具栏垂直内边距
     * <p>
     * 单位：像素
     * 工具栏上下边缘的内边距
     * </p>
     */
    public static int TOP_BAR_PADDING_VERTICAL = 10;

    /**
     * 顶部工具栏水平内边距
     * <p>
     * 单位：像素
     * 工具栏左右边缘的内边距
     * </p>
     */
    public static int TOP_BAR_PADDING_HORIZONTAL = 15;

    // 相机 & 视角控制

    /**
     * 默认跟随模式
     * <p>
     * 程序启动时是否自动跟随玩家视角
     * true表示地图会自动移动以保持玩家在视图中心
     * </p>
     */
    public static boolean DEFAULT_FOLLOW_MODE = false;

    /**
     * 跟随模式默认缩放比例
     * <p>
     * 启用跟随模式时的地图缩放级别
     * 可以设置为较大的值以聚焦玩家周围区域
     * </p>
     */
    public static double DEFAULT_FOLLOW_SCALE = 1.5;

    /**
     * 地图最小缩放限制
     * <p>
     * 用户通过滚轮缩放时的最小缩放倍数
     * 防止地图缩小到不可识别的程度
     * </p>
     */
    public static double MIN_SCALE_LIMIT = 0.1;

    /**
     * 地图最大缩放限制
     * <p>
     * 用户通过滚轮缩放时的最大缩放倍数
     * 防止地图放大过大导致性能问题
     * </p>
     */
    public static double MAX_SCALE_LIMIT = 15.0;

    // 玩家图标渲染

    /**
     * 玩家图标绘制大小
     * <p>
     * 单位：像素
     * 控制玩家图标在大地图上的显示尺寸
     * </p>
     */
    public static double PLAYER_ICON_DRAW_SIZE = 34.0;

    /**
     * 玩家旋转平滑系数
     * <p>
     * 范围：0.0~1.0
     * 用于平滑玩家图标旋转动画的插值因子
     * 值越小旋转越平滑但响应越慢，值越大响应越快但可能抖动
     * </p>
     */
    public static double PLAYER_ROTATE_LERP_FACTOR = 0.15;

    // 坐标平滑

    /**
     * 坐标平滑系数
     * <p>
     * 范围：0.0~1.0
     * 用于平滑玩家位置更新的插值因子
     * 值越接近1.0响应越快但可能抖动，值越小越平滑但有延迟
     * 平滑处理可以减少因SIFT匹配误差引起的抖动
     * </p>
     */
    public static double COORDINATE_SMOOTH_FACTOR = 0.8;

    // 目标捕获帧率 FPS

    /**
     * 目标捕获帧率
     * <p>
     * 单位：FPS（帧/秒）
     * 控制游戏窗口画面捕获的频率
     * 较高帧率提供更流畅的体验但增加CPU占用
     * 30FPS是流畅度和性能的平衡点
     * </p>
     */
    public static int TARGET_CAPTURE_FPS = 30;

    // 界面状态提示文本

    /**
     * 启动中状态文本
     * <p>
     * 程序启动时显示的状态提示
     * </p>
     */
    public static String STATUS_STARTING = "启动中...";

    /**
     * 查找窗口状态文本
     * <p>
     * 正在查找游戏窗口时的状态提示
     * </p>
     */
    public static String STATUS_FIND_WINDOW = "查找洛克王国：世界中";

    /**
     * 小地图未找到状态文本
     * <p>
     * 无法从游戏窗口中提取小地图区域时的状态提示
     * </p>
     */
    public static String STATUS_MINIMAP_NOT_FOUND = "❌ 小地图未找到";

    /**
     * 匹配失败状态文本
     * <p>
     * SIFT特征匹配失败时的状态提示
     * 可能是因为小地图在大地图边缘或发生了旋转
     * </p>
     */
    public static String STATUS_MATCH_FAILED = "❌ 匹配失败";

    /**
     * 玩家未找到状态文本
     * <p>
     * 无法检测到玩家箭头时的状态提示
     * </p>
     */
    public static String STATUS_PLAYER_NOT_FOUND = "⚠️ 未找到玩家";

    /**
     * 运行中状态文本
     * <p>
     * 正常运行中的状态提示
     * 表示所有功能工作正常
     * </p>
     */
    public static String STATUS_RUNNING = "视奸洛克王国：世界中";

    // ====================== 统计面板显示配置 ======================

    /**
     * 是否显示地图检测耗时
     * <p>
     * 控制统计面板中是否显示小地图提取耗时
     * 用于性能监控和调试
     * </p>
     */
    public static boolean SHOW_STATS_MAP_TIME = true;

    /**
     * 是否显示匹配耗时
     * <p>
     * 控制统计面板中是否显示SIFT匹配耗时
     * 用于性能监控和调试
     * </p>
     */
    public static boolean SHOW_STATS_MATCH_TIME = true;

    /**
     * 是否显示方向检测耗时
     * <p>
     * 控制统计面板中是否显示箭头方向检测耗时
     * 用于性能监控和调试
     * </p>
     */
    public static boolean SHOW_STATS_DIR_TIME = true;

    /**
     * 是否显示FPS
     * <p>
     * 控制统计面板中是否显示实时帧率
     * 用于性能监控和调试
     * </p>
     */
    public static boolean SHOW_STATS_FPS = true;

    // ====================== SIFT 特征匹配配置 ======================

    /**
     * 图像缩放系数
     * <p>
     * 对输入图像进行预缩放的倍数
     * 1.0表示不缩放，较大的值可以加快匹配但降低精度
     * 该参数影响SIFT特征点的密度和匹配速度
     * </p>
     */
    public static double SCALE_FACTOR = 1.0;

    /**
     * SIFT特征点数量限制
     * <p>
     * 限制每幅图像提取的特征点数量
     * 0表示提取所有可能的特征点（OpenCV默认行为）
     * 较大的值提高匹配精度但增加计算量
     * </p>
     */
    public static int SIFT_N_FEATURES = 0;

    /**
     * SIFT金字塔层数
     * <p>
     * 图像金字塔中每一层的层数
     * 标准值为3，影响尺度不变性的范围
     * 增加此值可以检测更大尺度的特征但增加计算量
     * </p>
     */
    public static int SIFT_N_OCTAVE_LAYERS = 3;

    /**
     * SIFT对比度阈值
     * <p>
     * 用于过滤低对比度特征点的阈值
     * 较小的值保留更多特征点但可能包含噪声
     * 标准值为0.001，通常不需要修改
     * </p>
     */
    public static double SIFT_CONTRAST_THRESHOLD = 0.001;

    /**
     * SIFT边缘阈值
     * <p>
     * 用于过滤边缘响应强的特征点的阈值
     * 较小的值保留更多特征点但可能不稳定
     * 标准值为10.0，通常不需要修改
     * </p>
     */
    public static double SIFT_EDGE_THRESHOLD = 50.0;

    /**
     * SIFT高斯模糊系数
     * <p>
     * 用于图像预模糊的高斯核的标准差
     * 影响特征点的尺度和定位
     * 标准值为1.6，通常不需要修改
     * </p>
     */
    public static double SIFT_SIGMA = 1.6;

    /**
     * 是否启用128维描述符
     * <p>
     * true表示使用128维SIFT描述符，false表示使用64维
     * 128维描述符精度更高但计算量更大
     * 默认使用64维描述符以提升性能
     * </p>
     */
    public static boolean SIFT_ENABLE_128 = false;

    // ====================== 匹配过滤阈值 ======================

    /**
     * 匹配比率阈值
     * <p>
     * 范围：0.0~1.0
     * 用于筛选良好匹配点的比率阈值（Lowe's ratio test）
     * 较小的值只保留高质量的匹配点，但可能匹配点过少
     * 0.6是经验值，在此项目中表现良好
     * </p>
     */
    public static float MATCH_RATIO_THRESHOLD = 0.6f;

    /**
     * 最小匹配点数量
     * <p>
     * 认为匹配成功所需的最少匹配点数量
对旋转和缩放的鲁棒性较差，容易被RANSAC过滤掉
     * 增加此值可以提高匹配的可靠性，但可能漏掉边缘的匹配
     * </p>
     */
    public static int MATCH_MIN_COUNT = 10;

    // ====================== RANSAC 单应性矩阵参数 ======================

    /**
     * RANSAC重投影误差阈值
     * <p>
     * 单位：像素
     * 认为内点的最大重投影误差
     * 较大的值允许更多的点被视为内点，但精度降低
     * 10.0是一个合理的平衡值
     * </p>
     */
    public static double RANSAC_REPROJ_THRESHOLD = 10.0;

    /**
     * RANSAC最大迭代次数
     * <p>
     * 计算单应性矩阵的最大迭代次数
     * 较大的值提高找到正确矩阵的概率但增加计算时间
     * 200次通常足够收敛
     * </p>
     */
    public static int RANSAC_MAX_ITERS = 200;

    /**
     * RANSAC置信度
     * <p>
     * 范围：0.0~1.0
     * 期望的算法成功的概率
     * 较高的置信度需要更多迭代
     * 0.95是标准值
     * </p>
     */
    public static double RANSAC_CONFIDENCE = 0.95;

    // 采集配置

    /**
     * 灰度距离阈值
     * <p>
     * 单位：像素
     * 玩家靠近资源点多少像素时，该资源点自动变灰（表示已采集）
     * 用于辅助玩家识别已采集的资源
     * </p>
     */
    // 采集配置
    public static double GRAY_DISTANCE = 12;

    // ====================== 自动加载配置 ======================

    /**
     * 静态代码块
     * <p>
     * 在类加载时自动执行，加载配置文件
     * 保证配置在第一次访问前已经加载完成
     * </p>
     */
    static {
        loadConfig();
    }

    /**
     * 私有构造函数
     * <p>
     * 禁止实例化配置类，确保所有配置通过静态成员访问
     * </p>
     *
     * @throws AssertionError 任何时候尝试实例化都会抛出此异常
     */
    private AppConfig() {
        throw new AssertionError("禁止实例化配置类");
    }

    /**
     * 加载配置文件
     * <p>
     * 执行流程：
     * <ol>
     *   <li>获取配置文件路径</li>
     *   <li>检查配置文件是否存在</li>
     *   <li>如果不存在，生成默认配置文件</li>
     *   <li>读取并解析配置文件</li>
     *   <li>覆盖内置默认值</li>
     * </ol>
     * </p>
     * <p>
     * 异常处理：
     * 配置加载失败时不会抛出异常，而是记录错误日志并使用内置默认值
     * 这保证了程序在任何情况下都能启动
     * </p>
     */
    private static void loadConfig() {
        try {
            // 获取配置文件的绝对路径
            // ResourceUtils.getExternalFile()会根据环境解析文件位置
            File configFile = ResourceUtils.getExternalFile(CONFIG_FILE_NAME);

            // 创建Properties对象用于存储配置键值对
            Properties prop = new Properties();

            if (configFile.exists()) {
                // 配置文件已存在，直接读取
                readConfig(configFile, prop);
            } else {
                // 配置文件不存在，生成默认配置文件
                generateDefaultConfigWithComments(configFile);
                log.info("✅ 已自动生成默认配置文件：{}", configFile.getAbsolutePath());

                // 立即读取刚生成的配置文件
                readConfig(configFile, prop);
            }

        } catch (Exception e) {
            // 配置加载失败，使用内置默认值
            log.error("❌ 配置加载失败，使用内置默认值", e);
        }
    }

    /**
     * 读取配置文件并覆盖配置值
     * <p>
     * 从指定的配置文件中读取配置项，并用这些值覆盖内置默认值
     * </p>
     * <p>
     * IO资源管理：
     * 使用try-with-resources确保InputStreamReader正确关闭
     * 使用UTF-8编码读取文件，支持中文配置值
     * </p>
     *
     * @param configFile 配置文件对象
     * @param prop 用于存储配置的Properties对象
     * @throws IOException 文件读取失败时抛出
     */
    private static void readConfig(File configFile, Properties prop) throws IOException {
        // 使用try-with-resources管理文件输入流
        // // 确保文件句柄在方法结束时自动关闭，防止资源泄漏
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            // 加载配置文件内容到Properties对象
            prop.load(reader);
            log.info("✅ 配置文件加载成功：{}", configFile.getAbsolutePath());
        }
        // 使用配置值覆盖内置默认值
        overrideFromProperties(prop);
    }

    /**
     * 生成带注释的默认配置文件
     * <p>
     * 创建一个包含详细中文注释的配置文件
     * 每个配置项都有对应的注释说明其作用和取值范围
     * </p>
     * <p>
     * 文件格式：
     * <ul>
     *   <li>使用UTF-8编码（支持中文）</li>
     *   <li>使用BOM标记确保Windows记事本正常识别编码</li>
     *   <li>使用#符号表示注释</li>
     *   <li>配置项格式为 key=value</li>
     * </ul>
与其他工具兼容
     * </p>
     * <p>
     * IO资源管理：
     * 使用try-with-resources确保所有流对象正确关闭
     * 显式写入UTF-8 BOM标记（EF BB BF）
     * </p>
     *
     * @param configFile 要生成的配置文件对象
     * @throws Exception 文件写入失败时抛出
     */
    private static void generateDefaultConfigWithComments(File configFile) throws Exception {
        // 配置文件内容模板，使用Java 15+的文本块语法
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
                # 玩家图标模式 cutter(从小地图抠图，稳定), simulation(算法模拟，大部分时间稳定)
                player.icon.model=cutter
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
                ransac.re.reproj.threshold=10.0
                ransac.max.iters=200
                ransac.confidence=0.95
                """;

        // 使用try-with-resources管理文件输出流
        // // 确保所有流对象在方法结束时正确关闭
        try (FileOutputStream fos = new FileOutputStream(configFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
             BufferedWriter writer = new BufferedWriter(osw)) {

            // 显式写入UTF-8 BOM标记（EF BB BF）
            // // 这确保Windows记事本能正确识别文件编码
            fos.write(0xEF);
            fos.write(0xBB);
            fos.write(0xBF);

            // 写入配置文件内容
            writer.write(configContent);
        }
    }

    /**
     * 使用Properties对象覆盖配置值
     * <p>
     * 遍历所有配置项，从Properties对象中读取对应的值
     * 如果配置文件中有定义则使用配置文件的值，否则保持默认值
     * </p>
     * <p>
     * 类型转换：
     * <ul>
     *   <li>字符串：直接获取</li>
     *   <li>整数：parseInt转换，失败时使用默认值</li>
     *   <li>浮点数：parseDouble转换，失败时使用默认值</li>
     *   <li>布尔值：parseBoolean转换，失败时使用默认值</li>
     *   <li>数组：逗号分隔的字符串转换</li>
     * </ul>
     * </p>
     *
     * @param prop 包含配置项的Properties对象
     */
    private static void overrideFromProperties(Properties prop) {
        // 本地资源路径配置
        SOURCE_ROOT_DIR = getStr(prop, "source.root.dir", SOURCE_ROOT_DIR);
        SOURCE_INIT = getStr(prop, "source.init", SOURCE_INIT);
        MAP_RESOURCE_PATH = getStr(prop, "map.resource.path", MAP_RESOURCE_PATH);
        MAP_RESOURCE_DIR = getStr(prop, "map.resource.dir", MAP_RESOURCE_DIR);
        ICON_DIR = getStr(prop, "icon.dir", ICON_DIR);
        PLAYER_ICON_PATH = getStr(prop, "player.icon.path", PLAYER_ICON_PATH);
        RESOURCE_ICON_DIR = getStr(prop, "resource.icon.dir", RESOURCE_ICON_DIR);
        RESOURCE_POINT_CONFIG_PATH = getStr(prop, "resource.point.config.path", RESOURCE_POINT_CONFIG_PATH);

        // 全局开关配置
        SHOW_MONITOR_BORDER = getBool(prop, "show.monitor.border", SHOW_MONITOR_BORDER);
        PLAYER_ICON_MODEL = getStr(prop, "player.icon.model", PLAYER_ICON_MODEL);

        // 远程地图配置
        MAP_REMOTE_URLS = getStrArray(prop, "map.remote.urls");
        MAP_REMOTE_URL_SORT = getIntArray(prop, "map.remote.url.sort");
        MAP_REMOTE_URL_NAME = getStrArray(prop, "map.remote.url.name");

        // 地图缩放配置
        MAP_ZOOM = getInt(prop, "map.zoom", MAP_ZOOM);
        MAP_MIN_ZOOM = getInt(prop, "map.min.zoom", MAP_MIN_ZOOM);
        MAP_MAX_ZOOM = getInt(prop, "map.max.zoom", MAP_MAX_ZOOM);
        JSON_ZOOM = getInt(prop, "json.zoom", JSON_ZOOM);

        // 远程资源URL配置
        MAP_RESOURCE_INFO_URL = clean(getStr(prop, "map.resource.info.url", MAP_RESOURCE_INFO_URL));
        MAP_RESOURCE_POINT_URL = clean(getStr(prop, "map.resource.point.url", MAP_RESOURCE_POINT_URL));

        // 窗口配置
        TARGET_WINDOW_NAME = getStr(prop, "target.window.name", TARGET_WINDOW_NAME);
        APP_MAIN_TITLE = getStr(prop, "app.title", APP_MAIN_TITLE);
        MAIN_WINDOW_DEFAULT_WIDTH = getInt(prop, "main.window.width", MAIN_WINDOW_DEFAULT_WIDTH);
        MAIN_WINDOW_DEFAULT_HEIGHT = getInt(prop, "main.window.height", MAIN_WINDOW_DEFAULT_HEIGHT);

        // UI样式配置
        UI_FONT_SIZE = getInt(prop, "ui.font.size", UI_FONT_SIZE);
        TOP_BAR_SPACING = getInt(prop, "ui.top.bar.spacing", TOP_BAR_SPACING);
        TOP_BAR_PADDING_VERTICAL = getInt(prop, "ui.top.bar.padding.vertical", TOP_BAR_PADDING_VERTICAL);
        TOP_BAR_PADDING_HORIZONTAL = getInt(prop, "ui.top.bar.padding.horizontal", TOP_BAR_PADDING_HORIZONTAL);

        // 相机和视角控制配置
        DEFAULT_FOLLOW_MODE = getBool(prop, "camera.follow.mode.default", DEFAULT_FOLLOW_MODE);
        DEFAULT_FOLLOW_SCALE = getDouble(prop, "camera.follow.scale.default", DEFAULT_FOLLOW_SCALE);
        MIN_SCALE_LIMIT = getDouble(prop, "map.scale.min", MIN_SCALE_LIMIT);
        MAX_SCALE_LIMIT = getDouble(prop, "map.scale.max", MAX_SCALE_LIMIT);

        // 玩家图标和坐标配置
        PLAYER_ICON_DRAW_SIZE = getDouble(prop, "player.icon.draw.size", PLAYER_ICON_DRAW_SIZE);
        PLAYER_ROTATE_LERP_FACTOR = getDouble(prop, "player.rotate.lerp.factor", PLAYER_ROTATE_LERP_FACTOR);
        COORDINATE_SMOOTH_FACTOR = getDouble(prop, "coordinate.smooth.factor", COORDINATE_SMOOTH_FACTOR);
        TARGET_CAPTURE_FPS = getInt(prop, "target.capture.fps", TARGET_CAPTURE_FPS);

        // 统计面板显示配置
        SHOW_STATS_MAP_TIME = getBool(prop, "show.stats.map.time", SHOW_STATS_MAP_TIME);
        SHOW_STATS_MATCH_TIME = getBool(prop, "show.stats.match.time", SHOW_STATS_MATCH_TIME);
        SHOW_STATS_DIR_TIME = getBool(prop, "show.stats.dir.time", SHOW_STATS_DIR_TIME);
        SHOW_STATS_FPS = getBool(prop, "show.stats.fps", SHOW_STATS_FPS);

        // 状态文本配置
        STATUS_STARTING = getStr(prop, "status.starting", STATUS_STARTING);
        STATUS_FIND_WINDOW = getStr(prop, "status.find.window", STATUS_FIND_WINDOW);
        STATUS_MINIMAP_NOT_FOUND = getStr(prop, "status.minimap.not.found", STATUS_MINIMAP_NOT_FOUND);
        STATUS_MATCH_FAILED = getStr(prop, "status.match.failed", STATUS_MATCH_FAILED);
        STATUS_PLAYER_NOT_FOUND = getStr(prop, "status.player.not.found", STATUS_PLAYER_NOT_FOUND);
        STATUS_RUNNING = getStr(prop, "status.running", STATUS_RUNNING);

        // SIFT参数配置
        SCALE_FACTOR = getDouble(prop, "scale.factor", SCALE_FACTOR);
        SIFT_N_FEATURES = getInt(prop, "sift.n.features", SIFT_N_FEATURES);
        SIFT_N_OCTAVE_LAYERS = getInt(prop, "sift.n.octave.layers", SIFT_N_OCTAVE_LAYERS);
        SIFT_CONTRAST_THRESHOLD = getDouble(prop, "sift.contrast.threshold", SIFT_CONTRAST_THRESHOLD);
        SIFT_EDGE_THRESHOLD = getDouble(prop, "sift.edge.threshold", SIFT_EDGE_THRESHOLD);
        SIFT_SIGMA = getDouble(prop, "sift.sigma", SIFT_SIGMA);
        SIFT_ENABLE_128 = getBool(prop, "sift.enable.128", SIFT_ENABLE_128);

        // 匹配过滤配置
        MATCH_RATIO_THRESHOLD = (float) getDouble(prop, "match.ratio.threshold", MATCH_RATIO_THRESHOLD);
        MATCH_MIN_COUNT = getInt(prop, "match.min.count", MATCH_MIN_COUNT);

        // RANSAC参数配置
        RANSAC_REPROJ_THRESHOLD = getDouble(prop, "ransac.reproj.threshold", RANSAC_REPROJ_THRESHOLD);
        RANSAC_MAX_ITERS = getInt(prop, "ransac.max.iters", RANSAC_MAX_ITERS);
        RANSAC_CONFIDENCE = getDouble(prop, "ransac.confidence", RANSAC_CONFIDENCE);

        // 采集配置
        GRAY_DISTANCE = getDouble(prop, "gray.distance", GRAY_DISTANCE);
    }

    /**
     * 获取字符串配置项
     * <p>
     * 从Properties对象中获取指定键的值
     * 如果键不存在或值为空，返回默认值
     * 自动去除字符串两端的空白字符
     * </p>
     *
     * @param prop Properties对象
     * @param key 配置键
     * @param def 默认值
     * @return 配置值或默认值
     */
    private static String getStr(Properties prop, String key, String def) {
        String val = prop.getProperty(key);
        return val == null ? def : val.trim();
    }

    /**
     * 获取整数配置项
     * <p>
     * 从Properties对象中获取指定键的整数值
     * 如果键不存在、值为空或格式错误，返回默认值
     * </p>
     * <p>
     * 异常处理：
     * 捕获所有异常（包括NumberFormatException、NullPointerException等）
     * 保证方法不会抛出异常，总是返回有效值
     * </p>
     *
     * @param prop Properties对象
     * @param key 配置键
     * @param def 默认值
     * @return 配置值或默认值
     */
    private static int getInt(Properties prop, String key, int def) {
        try {
            return Integer.parseInt(prop.getProperty(key).trim());
        } catch (Exception e) {
            // 解析失败，返回默认值
            return def;
        }
    }

    /**
     * 获取浮点数配置项
     * <p>
     * 从Properties对象中获取指定键的浮点数值
     * 如果键不存在、值为空或格式错误，返回默认值
     * </p>
     * <p>
     * 异常处理：
     * 捕获所有异常（包括NumberFormatException、NullPointerException等）
     * 保证方法不会抛出异常，总是返回有效值
     * </p>
     *
     * @param prop Properties对象
     * @param key 配置键
     * @param def 默认值
     * @return 配置值或默认值
     */
    private static double getDouble(Properties prop, String key, double def) {
        try {
            return Double.parseDouble(prop.getProperty(key).trim());
        } catch (Exception e) {
            // 解析失败，返回默认值
            return def;
        }
    }

    /**
     * 获取布尔配置项
     * <p>
     * 从Properties对象中获取指定键的布尔值
     * 如果键不存在、值为空或格式错误，返回默认值
     * </p>
     * <p>
     * 异常处理：
     * 捕获所有异常（包括NullPointerException等）
     * 保证方法不会抛出异常，总是返回有效值
     * </p>
     *
     * @param prop Properties对象
     * @param key 配置键
     * @param def 默认值
     * @return 配置值或默认值
     */
    private static boolean getBool(Properties prop, String key, boolean def) {
        try {
            return Boolean.parseBoolean(prop.getProperty(key).trim());
        } catch (Exception e) {
            // 解析失败，返回默认值
            return def;
        }
    }

    /**
     * 获取字符串数组配置项
     * <p>
     * 从Properties对象中获取指定键的值，按逗号分割为数组
     * 自动去除每个元素两端的空白字符
     * 过滤掉空字符串
     * </p>
     * <p>
     * 性能优化：
     * 使用Java Stream API进行链式操作，代码简洁高效
     * </p>
     *
     * @param prop Properties对象
     * @param key 配置键
     * @return 字符串数组，可能为空数组但不会为null
     */
    private static String[] getStrArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new String[0];

        // 使用Stream API分割、过滤、处理字符串数组
        return Arrays.stream(s.split(","))
                .map(String::trim)          // 去除每个元素两端的空白
                .filter(v -> !v.isBlank()) // 过滤空字符串
                .toArray(String[]::new);   // 转换为数组
    }

    /**
     * 获取整数数组配置项
     * <p>
     * 从Properties对象中获取指定键的值，按逗号分割并转为整数数组
     * 自动去除每个元素两端的空白字符
     * </p>
     * <p>
     * 异常处理：
     * 如果任何元素无法解析为整数，返回空数组
     * 这是容错处理，避免配置文件中的格式错误导致程序崩溃
     * </p>
     *
     * @param prop Properties对象
     * @param key 配置键
     * @return 整数数组，可能为空数组但不会为null
     */
    private static int[] getIntArray(Properties prop, String key) {
        String s = prop.getProperty(key);
        if (s == null || s.isBlank()) return new int[0];

        try {
            // 使用Stream API分割、转换、处理整数数组
            return Arrays.stream(s.split(","))
                    .map(String::trim)                  // 去除每个元素两端的空白
                    .mapToInt(Integer::parseInt)        // 转换为整数
                    .toArray();                         // 转换为数组
        } catch (Exception e) {
            // 解析失败，返回空数组
            return new int[0];
        }
    }

    /**
     * 清理URL字符串
     * <p>
     * 移除URL中可能存在的多余字符，如引号、分号等
     * 这些字符可能是从网页或文档中复制时带上的
     * </p>
     *
     * @param url 原始URL字符串
     * @return 清理后的URL字符串
     */
    private static String clean(String url) {
        return url.replace("\"", "").replace("'", "").replace(";", "").trim();
    }
}
