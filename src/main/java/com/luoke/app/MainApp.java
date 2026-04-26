package com.luoke.app;

import com.luoke.app.capture.WindowsMonitor;
import com.luoke.app.capture.common.CaptureFrameRecord;
import com.luoke.app.capture.jna.Frame;
import com.luoke.app.component.InteractiveCanvas;
import com.luoke.app.config.AppConfig;
import com.luoke.app.context.*;
import com.luoke.app.hook.HookEventType;
import com.luoke.app.hook.HookRegistry;
import com.luoke.app.hook.impl.RealOcrHook;
import com.luoke.app.hook.impl.ResourceGrayHook;
import com.luoke.app.hook.multicast.HookMulticaster;
import com.luoke.app.macher.map.MapMatcher;
import com.luoke.app.macher.map.SiftMapMatcher;
import com.luoke.app.macher.minimap.MapTracker;
import com.luoke.app.macher.player.ArrowDetector;
import com.luoke.app.macher.player.Player;
import com.luoke.app.map.MapResourceUpdater;
import com.luoke.app.render.CutterPlayerRenderer;
import com.luoke.app.render.PlayerRenderer;
import com.luoke.app.render.RenderLoop;
import com.luoke.app.utils.CoordinateTransformer;
import com.luoke.app.utils.ImageUtil;
import com.luoke.app.utils.MapMathUtil;
import com.luoke.app.utils.ResourceUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;
import org.bytedeco.opencv.opencv_core.Mat;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 洛克导航主应用程序类
 * <p>
 * 继承自JavaFX的Application类，负责整个应用程序的生命周期管理。
 * 主要功能包括：
 * <ul>
 *   <li>初始化并下载必要的资源文件</li>
 *   <li>启动窗口监控，捕获游戏窗口画面</li>
 *   <li>实现SIFT特征匹配算法，识别小地图位置</li>
 *   <li>检测玩家箭头方向和位置</li>
 *   <li>实时渲染游戏地图和玩家位置</li>
 *   <li>管理Native资源（OpenCV、PaddleOCR等）的创建和释放</li>
 * </ul>
 * </p>
 * <p>
 * Native资源管理说明：
 * <ul>
 *   <li>OpenCV Mat对象使用try-with-resources确保及时释放</li>
 *   <li>MapMatcher、WindowsMonitor等持有Native资源的对象在stop()方法中显式销毁</li>
 *   <li>使用虚拟线程异步加载重型Native资源，避免阻塞UI线程</li>
 *   <li>实现看门狗机制防止资源释放时死锁，超时后强制halt</li>
 * </ul>
 * </p>
 * <p>
 * 性能优化策略：
 * <ul>
 *   <li>使用CountDownLatch实现init()和start()之间的同步，确保资源就绪后才显示UI</li>
 *   <li>帧处理在独立线程中异步执行，不阻塞JavaFX UI线程</li>
 *   <li>使用AtomicBoolean标志位控制匹配器就绪状态，避免竞态条件</li>
 *   <li>UI更新通过Platform.runLater()确保线程安全</li>
 * </ul>
 * </p>
 *
 * @author 可达鸭
 * @version 1.0
 */
@Slf4j
public class MainApp extends Application {

    /**
     * 小地图追踪器实例（单例）
     * <p>
     * 负责从游戏窗口中提取小地图图像区域
     * 使用单例模式避免重复创建，减少内存开销
     * </p>
     */
    private static final MapTracker mapTracker = MapTracker.getInstance();

    /**
     * 性能统计上下文实例（单例）
     * <p>
     * 记录帧处理时间、匹配耗时等性能指标
     * 用于性能监控和优化分析
     * </p>
     */
    private static final StatsContext stats = StatsContext.getInstance();

    /**
     * 匹配器就绪标志（原子布尔类型）
     * <p>
     * 使用AtomicBoolean保证多线程环境下的可见性和原子性
     * 在匹配器初始化完成前，帧会被跳过处理
     * 这避免了在资源未就绪时尝试进行匹配操作
     * </p>
     */
    private static final AtomicBoolean isMatcherReady = new AtomicBoolean(false);

    /**
     * 地图匹配器实例
     * <p>
     * 负责使用SIFT算法将小地图与大地图进行特征匹配
     * 这是一个重型的Native资源对象，持有OpenCV的内存
     * 必须在程序退出时显式调用destroy()释放资源
     * </p>
     */
    private static MapMatcher mapMatcher;

    /**
     * Windows窗口监控器
     * <p>
     * 负责监控目标游戏窗口（"洛克王国：世界"）的屏幕变化
     * 使用JNA调用Windows API实现高效的屏幕捕获
     * 持有Native句柄资源，必须在stop()时释放
     * </p>
     */
    private static WindowsMonitor windowsMonitor;

    /**
     * 渲染循环实例
     * <p>
     * 负责JavaFX画布的实时渲染工作
     * 在独立线程中运行，定期刷新UI显示玩家位置和地图
     * 必须在程序退出时调用stop()停止渲染线程
     * </p>
     */
    private RenderLoop renderLoop;

    /**
     * 状态标签控件
     * <p>
     * 显示当前运行状态文本（如"启动中"、"运行中"、"匹配失败"等）
     * 静态变量以方便从静态方法processFrame中更新UI
     * </p>
     */
    private static Label statusLabel;

    /**
     * 跟随玩家复选框控件
     * <p>
     * 允许用户选择是否自动跟随玩家视角移动地图
     * 初始时不可见，直到成功检测到玩家箭头后才显示
     * </p>
     */
    private static CheckBox followPlayerCb;

    /**
     * 初始化同步门闩
     * <p>
     * 用于阻塞start()方法直到init()方法完成所有准备工作
     * 这确保了UI窗口只在所有资源就绪后才显示，避免启动时的竞态条件
     * </p>
     * <p>
     * 线程安全保证：
     * CountDownLatch在多线程环境下是线程安全的
     * init()在JavaFX初始化线程中运行，start()在JavaFX应用线程中运行
     * 通过此门闩确保正确的执行顺序
     * </p>
     */
    private final CountDownLatch initLatch = new CountDownLatch(1);

    /**
     * 初始化成功标志
     * <p>
     * volatile关键字保证多线程之间的可见性
     * 如果初始化失败（如资源下载失败），start()方法将检测此标志并退出程序
     * </p>
     */
    private volatile boolean initSuccess = false;

    /**
     * 应用程序主入口方法
     * <p>
     * 启动JavaFX应用程序，由Application类管理生命周期
     * 执行顺序：init() -> start() -> stop()
     * </p>
     *
     * @param args 命令行参数数组
     */
    public static void main(String[] args) {
        launch(args);
    }

    /**
     * 处理捕获的帧图像
     * <p>
     * 该方法由WindowsMonitor回调，每次捕获到新帧时被调用
     * 执行完整的图像处理流水线：
     * <ol>
     *   <li>从小地图区域提取图像</li>
     *   <li>使用SIFT算法与大地图匹配，计算玩家位置</li>
     *   <li>检测玩家箭头的方向</li>
     *   <li>更新地图上下文中的玩家状态</li>
     *   <li>平滑坐标更新以减少抖动</li>
     * </ol>
     * </p>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用try-with-resources管理OpenCV Mat对象，确保及时释放Native内存</li>
     *   <li>Frame对象由JNA管理，调用方负责释放</li>
     * </ul>
     * </p>
     * <p>
     * 性能优化：
     * <ul>
     *   <li>早期返回策略：在匹配器未就绪或关键步骤失败时立即返回，避免无效计算</li>
     *   <li>性能统计：记录各个阶段的耗时，帮助识别性能瓶颈</li>
     *   <li>UI更新异步化：通过Platform.runLater()避免阻塞帧处理线程</li>
     * </ul>
     * </p>
     *
     * @param frame 捕获的帧对象，包含图像数据和尺寸信息
     */
    // ==================== 下面的代码你不用动 ====================
    private static void processFrame(Frame frame) {
        // 检查帧有效性，并验证匹配器是否已就绪
        // 这避免了在资源初始化期间处理帧，防止空指针异常
        if (frame == null || !isMatcherReady.get()) return;

        // 记录帧处理开始时间，用于FPS统计
        stats.onFrameProcessed();

        try {
            // 触发帧捕获事件，通知所有注册的钩子（如资源灰度计算、OCR识别等）
            // 使用事件解耦架构，方便扩展新功能而不修改核心逻辑
            HookMulticaster.getInstance().enqueue(HookEventType.FRAME_CAPTURED, frame);

            // ====================== 小地图提取阶段 ======================
            long t0 = System.currentTimeMillis();
            // 从游戏窗口帧中提取小地图区域
            // MapTracker通过固定的坐标和尺寸裁剪图像
            CaptureFrameRecord miniMap = mapTracker.getMiniMapImage(frame);
            stats.recordMapDetect(System.currentTimeMillis() - t0);

            // 小地图提取失败，无法继续后续处理
            // 更新状态提示用户
            if (miniMap == null) {
                updateStatus(AppConfig.STATUS_MINIMAP_NOT_FOUND, Color.RED);
                return;
            }

            // 更新玩家箭头裁剪器，用于后续的箭头方向检测
            // CutterPlayerRenderer从小地图中提取玩家箭头图像用于模拟显示
            CutterPlayerRenderer.getInstance().updateArrow(miniMap);

            // ====================== 地图匹配阶段 ======================
            long t1 = System.currentTimeMillis();
            // 使用SIFT算法将小地图与大地图进行特征匹配
            // 返回小地图四个角点在大地图上的坐标对应关系
            double[][] corners = mapMatcher.match(miniMap.bytes(), miniMap.width(), miniMap.height());
            stats.recordMatch(System.currentTimeMillis() - t1);

            // 匹配失败或结果不可靠（少于3个角点）
            // 这可能是因为小地图在大地图边缘或发生了旋转
            if (corners == null || corners.length < 3) {
                updateStatus(AppConfig.STATUS_MATCH_FAILED, Color.RED);
                return;
            }

            // 计算小地图在大地图上的中心点坐标
            // 使用几何中心计算算法，通过四个角点的平均值得到
            double[] center = MapMathUtil.getCentroid(corners);

            // ====================== 玩家箭头检测阶段 ======================
            long t2 = System.currentTimeMillis();
            Player player;

            // 将捕获记录转换为OpenCV Mat对象
            // 使用try-with-resources确保Mat的Native内存在作用域结束时自动释放
            try (Mat mat = ImageUtil.convertToMat(miniMap)) {
                // 使用箭头检测算法识别玩家方向
                // ArrowDetector通过模板匹配或特征识别找到箭头的角度
                player = ArrowDetector.detectPlayer(mat);
            }
            stats.recordDirection(System.currentTimeMillis() - t2);

            // 首次成功检测到玩家箭头时，显示"跟随玩家"复选框
            // 使用Platform.runLater确保在JavaFX应用线程中更新UI
            if (player.isFound()) {
                Platform.runLater(() -> followPlayerCb.setVisible(true));
            }

            // 玩家箭头检测失败，无法确定朝向
            // 不返回继续使用上次的位置，仅更新状态提示
            if (!player.isFound()) {
                updateStatus(AppConfig.STATUS_PLAYER_NOT_FOUND, Color.ORANGE);
                return;
            }

            // ====================== 状态更新阶段 ======================
            // 更新地图上下文中的玩家位置和角度
            // MapContext是全局状态管理器，存储当前玩家状态
            MapContext.getInstance().updatePlayerState(center[0], center[1], player.getAngle());

            // 平滑更新玩家坐标，减少因匹配误差引起的抖动
            // 使用线性插值算法，平滑因子来自配置文件
            CoordinateTransformer.updatePositionSmoothly(center[0], center[1], AppConfig.COORDINATE_SMOOTH_FACTOR);

            // 更新状态为"运行中"，表示一切正常
            updateStatus(AppConfig.STATUS_RUNNING, Color.LIGHTGREEN);

        } catch (Exception e) {
            // 捕获并记录帧处理过程中的异常
            // 单帧异常不会中断整个程序，确保系统鲁棒性
            log.error("帧异常", e);
        }
    }

    /**
     * 应用程序初始化方法
     * <p>
     * 在JavaFX应用线程中执行，负责首次启动时的资源准备工作
     * 执行流程：
     * <ol>
     *   <li>检查并提取内置资源文件</li>
     *   <li>检测是否已初始化（检查init标记文件）</li>
     *   <li>首次启动时显示提示对话框</li>
     *   <li>下载必要的远程资源（地图、图标等）</li>
     *   <li>创建初始化标记文件</li>
     *   <li>释放CountDownLatch，允许start()继续执行</li>
     * </ol>
     * </p>
     * <p>
     * 线程同步机制：
     * <ul>
     *   <li>与start()方法通过CountDownLatchDownLatch同步</li>
     *   <li>在finally块中调用countDown()，确保即使异常也能解除阻塞</li>
     * </ul>
     * </p>
     * <p>
     * Native打包考虑：
     * <ul>
     *   <li>资源文件可能打包在native image内部，需要提取到外部目录</li>
     *   <li>首次运行时需要下载资源，确保网络连接正常</li>
     * </ul>
     * </p>
     *
     * @throws Exception 初始化过程中的异常（如网络错误、IO错误等）
     */
    // ====================== 【阻塞式初始化】 ======================
    @Override
    public void init() throws Exception {
        super.init();
        log.info("init() 开始初始化（子线程）");

        try {
            // ====================== 资源提取阶段 ======================
            // 将打包在JAR中的资源文件提取到外部目录
            // // 这对于native image尤为重要，因为资源可能嵌入在可执行文件中
            ResourceUtils.extractAll();

            // 获取资源根目录，确保目录存在
            File rootDir = ResourceUtils.getExternalFile(AppConfig.SOURCE_ROOT_DIR);
            if (!rootDir.exists()) {
                rootDir.mkdirs();
            }

            // 检查初始化标记文件，判断是否为首次运行
            File initFile = ResourceUtils.getExternalFile(AppConfig.SOURCE_INIT);
            if (initFile.exists()) {
                log.info("资源已初始化，直接启动");
                initSuccess = true;
                return;
            }

            // ====================== 首次运行提示 ======================
            // 在JavaFX应用线程中显示首次启动提示对话框
            // 使用showAndWait()会阻塞当前线程，等待用户点击确认
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("首次启动");
                alert.setHeaderText("首次运行需要下载资源文件");
                alert.setContentText("点击确定后开始下载，请不要关闭程序...");
                alert.showAndWait(); // 这里会阻塞 JavaFX 线程
            });

            // ====================== 资源下载阶段 ======================
            // 开始下载所需的远程资源文件
            // MapResourceUpdater负责从配置的URL下载地图瓦片和资源点配置
            log.info("开始下载资源...");
            MapResourceUpdater.updateAllResources();

            // 创建初始化标记文件，表示资源已下载完成
            // 下次启动时将跳过下载步骤
            initFile.createNewFile();
            initSuccess = true;
            log.info("初始化完成！");

        } catch (Exception e) {
            // 记录初始化失败异常
            log.error("初始化失败", e);
            initSuccess = false;

            // 在JavaFX应用线程中显示错误对话框
            // 提示用户初始化失败，程序将退出
            Platform.runLater(() -> {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("初始化失败");
                alert.setHeaderText("资源下载失败，程序无法启动");
                alert.showAndWait();
                Platform.exit();
            });
        } finally {
            // ====================== 解除阻塞 ======================
            // 无论初始化成功还是失败，都释放start()的阻塞
            // 这样start()可以检查initSuccess标志并决定是否继续
            initLatch.countDown();
        }
    }

    /**
     * 异步预加载地图匹配器
     * <p>
     * 在虚拟线程中执行，避免阻塞UI线程
     * 加载过程：
     * <ol>
     *   <li>创建SIFT地图匹配器实例（涉及OpenCV Native库加载）</li>
     *   <li>初始化匹配器并加载大地图特征</li>
     *   <li>初始化OCR异步管理器</li>
     *   <li>设置匹配器就绪标志</li>
     *   <li>启动窗口监控</li>
     * </ol>
     * </p>
     * <p>
     * 性能优化策略：
     * <ul>
     *   <li>使用虚拟线程执行，降低线程开销</li>
   *   <li>在UI显示后再加载重型资源，提升启动速度</li>
     *   <li>通过isMatcherReady标志避免在资源未就绪时处理帧</li>
     * </ul>
     * </p>
     */
    private static void preloadMatcherAsync() {
        // 在虚拟线程中启动预加载任务
        // 虚拟线程是轻量级线程，适合IO密集型和长耗时任务
        Thread.ofVirtual().start(() -> {
            try {
                // 创建SIFT地图匹配器实例
                // // 这会触发OpenCV Native库的加载，有一定耗时
                mapMatcher = new SiftMapMatcher();

                // 初始化匹配器，加载大地图并提取SIFT特征点
                // 特征提取是CPU密集型操作，在独立线程中执行
                mapMatcher.init(AppConfig.MAP_RESOURCE_PATH);

                // 初始化OCR异步管理器，创建PaddleOCR实例池
                // OCR库也涉及Native资源，需要提前加载
                OcrAsyncManager.initialize(AppConfig.OCR_CORE_SIZE);

                // 设置匹配器就绪标志，允许帧处理线程开始工作
                // 使用AtomicBoolean保证多线程可见性
                isMatcherReady.set(true);

                // 启动窗口监控，开始捕获游戏画面
                // // 成功后才启动，避免资源未就绪时就开始捕获
                try {
                    startCapture();
                } catch (Exception ignore) {
                    // 窗口启动失败忽略，可能是因为目标窗口不存在
                }
            } catch (Exception e) {
                // 记录匹配器加载失败异常
                log.error("匹配器加载失败", e);
            }
        });
    }

    /**
     * 更新状态标签显示
     * <p>
     * 在JavaFX应用线程中更新状态标签的文本和颜色
     * 使用Platform.runLater()确保线程安全，因为该方法可能从非UI线程调用
     * </p>
     *
     * @param msg 要显示的状态文本
     * @param color 文本颜色
     */
    private static void updateStatus(String msg, Color color) {
        // 使用Platform.runLater()确保在JavaFX应用线程中更新UI
        // // 这避免了非UI线程直接操作JavaFX控件引发的异常
        Platform.runLater(() -> {
            statusLabel.setText(msg);
            statusLabel.setTextFill(color);
        });
    }

    /**
     * 启动JavaFX应用程序主窗口
     * <p>
     * 在JavaFX应用线程中执行，等待init()完成后创建并显示UI
     * 执行流程：
     * <ol>
     *   <li>等待init()方法完成（通过CountDownLatch阻塞）</li>
     *   <li>检查初始化是否成功</li>
     *   <li>初始化大地图资源</li>
     *   <li>加载资源点配置</li>
     *   <li>创建并配置JavaFX UI控件</li>
     *   <li>启动渲染循环</li>
     *   <li>异步预加载匹配器</li>
     * </ol>
     * </p>
     * <p>
     * Native资源管理：
     * <ul>
     *   <li>使用try-with-resources管理Image InputStream，防止文件句柄泄漏</li>
   *   <li>拦截窗口关闭事件，调用自定义stop()方法确保资源正确释放</li>
     * </ul>
     * </p>
     * <p>
     * 线程安全：
     * <ul>
     *   <li>通过CountDownLatch等待init()完成，避免竞态条件</li>
     *   <li>所有UI操作在JavaFX应用线程中执行</li>
     *   <li>异步任务使用虚拟线程，不阻塞UI</li>
     * </ul>
     * </p>
     *
     * @param primaryStage JavaFX主舞台对象，代表应用程序窗口
     */
    // ====================== 【被阻塞，直到 init() 完成】 ======================
    @Override
    public void start(Stage primaryStage) {
        // 注册事件钩子（资源灰度计算、OCR识别等）
        // 必须在start阶段注册，确保在帧处理前准备好
        registerHook();

        try {
            // ====================== 等待初始化完成 ======================
            // 阻塞当前线程直到init()方法调用countDown()
            // 这确保了UI只在所有资源准备就绪后才显示
            log.info("等待初始化完成...");
            initLatch.await();

            // 检查初始化是否成功，失败则退出程序
            if (!initSuccess) {
                Platform.exit();
                return;
            }

            log.info("初始化完成，启动主窗口");

            // ====================== 资源初始化 ======================

            // 加载大地图图片资源并初始化地图上下文
            initBigMapResource();

            // 加载资源点配置文件（包含地图上各种资源的位置信息）
            ResourcePointContext.getInstance().loadAndInit();

            // ====================== UI控件创建 ======================

            // 创建根容器，使用StackPane方便层叠布局
            StackPane root = new StackPane();

            // 创建交互式画布，支持缩放、平移等交互操作
            InteractiveCanvas canvas = new InteractiveCanvas();
            // 将画布尺寸绑定到根容器，实现自适应布局
            canvas.widthProperty().bind(root.widthProperty());
            canvas.heightProperty().bind(root.heightProperty());

            // 创建顶部工具栏，使用HBox水平布局
            HBox topBar = new HBox(AppConfig.TOP_BAR_SPACING);
            topBar.setPadding(new Insets(
                    AppConfig.TOP_BAR_PADDING_VERTICAL,
                    AppConfig.TOP_BAR_PADDING_HORIZONTAL,
                    AppConfig.TOP_BAR_PADDING_VERTICAL,
                    AppConfig.TOP_BAR_PADDING_HORIZONTAL
            ));
            // 允许鼠标事件穿透到下方的画布
            topBar.setMouseTransparent(false);
            topBar.setPickOnBounds(false);
            StackPane.setAlignment(topBar, javafx.geometry.Pos.TOP_LEFT);

            // 创建"跟随玩家"复选框，绑定到相机上下文
            followPlayerCb = new CheckBox(AppConfig.FOLLOW_PLAYER);
            followPlayerCb.setStyle("-fx-text-fill: black; -fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");
            // 监听选中状态变化，更新相机跟随模式
            followPlayerCb.selectedProperty().addListener((o, ov, nv) ->
                    CameraContext.getInstance().setFollowMode(nv)
            );
            // 初始时不可见，直到检测到玩家箭头后才显示
            followPlayerCb.setVisible(false);

            // ====================== 事件处理 ======================

            // 拦截默认关闭行为，调用自定义stop()方法
            // 这确保在关闭窗口时能正确释放所有Native资源
            primaryStage.setOnCloseRequest(event -> {
                event.consume(); // 吃掉默认关闭事件，阻止JavaFX默认处理
                stop();          // 主动调用完整的资源释放逻辑
            });

            // 创建"更新资源文件"按钮
            Button updateBtn = new Button("更新资源文件");
            updateBtn.setStyle("-fx-text-fill: black; -fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");
            // 点击按钮时触发资源更新（在虚拟线程中执行）
            updateBtn.setOnAction(e -> updateResource(updateBtn));

            // 创建状态标签，显示当前运行状态
            statusLabel = new Label(AppConfig.STATUS_STARTING);
            statusLabel.setTextFill(Color.BLACK);
            statusLabel.setStyle("-fx-font-size: " + AppConfig.UI_FONT_SIZE + "px;");

            // 将控件添加到工具栏
            topBar.getChildren().addAll(updateBtn, followPlayerCb, statusLabel);
            // 将画布和工具栏添加到根容器
            root.getChildren().addAll(canvas, topBar);

            // ====================== 渲染循环启动 ======================

            // 创建渲染循环实例，绑定到画布的GraphicsContext2D
            renderLoop = new RenderLoop(canvas.getGraphicsContext2D());
            // 启动渲染循环，在独立线程中刷新UI
            renderLoop.start();

            // ====================== 窗口显示 ======================

            // 设置窗口标题
            primaryStage.setTitle(AppConfig.APP_MAIN_TITLE);

            // 创建场景并绑定到舞台
            Scene scene = new Scene(root, AppConfig.MAIN_WINDOW_DEFAULT_WIDTH, AppConfig.MAIN_WINDOW_DEFAULT_HEIGHT);
            primaryStage.setScene(scene);

            //再设置一次关闭事件，确保资源正确释放（双重保险）
            primaryStage.setOnCloseRequest(e -> stop());

            // 显示窗口
            primaryStage.show();

            // ====================== 后台任务启动 ======================

            // 异步预加载地图匹配器
            // 在UI显示后执行，提升启动速度
            preloadMatcherAsync();

        } catch (Exception e) {
            // 记录启动失败异常
            log.error("启动失败", e);
            Platform.exit();
        }
    }

    /**
     * 更新资源文件
     * <p>
     * 在虚拟线程中执行资源更新操作，避免阻塞UI线程
     * 执行流程：
     * <ol>
     *   <li>禁用更新按钮并更新按钮文本为"正在更新..."</li>
     *   <li>下载并更新所有资源文件</li>
     *   <li>重新加载资源点配置</li>
     *   <li>恢复按钮状态</li>
     * </ol>
     * </p>
     * <p>
     * 线程安全：
     * <ul>
     *   <li>UI更新通过Platform.runLater()确保在JavaFX应用线程中执行</li>
   *   <li>按钮禁用状态防止重复点击</li>
     * </ul>
     * </p>
     *
     * @param updateBtn 更新按钮控件引用
     */
    private static void updateResource(Button updateBtn) {
        // 在虚拟线程中执行资源更新任务
        // 虚拟线程适合IO密集型任务，减少线程切换开销
        Thread.ofVirtual().start(() -> {
            try {
                // 在JavaFX应用线程中更新按钮状态
                Platform.runLater(() -> {
                    updateBtn.setText("正在更新...");
                    updateBtn.setDisable(true); // 禁用按钮，防止重复点击
                });

                // 下载并更新所有资源文件（地图、图标等）
                MapResourceUpdater.updateAllResources();

                // 重新加载资源点配置文件
                ResourcePointContext.getInstance().loadAndInit();

                // 更新按钮状态为"更新完成"
                Platform.runLater(() -> updateBtn.setText("更新完成"));

            } catch (Exception ex) {
                // 记录更新失败异常
                log.error("更新失败", ex);
                // 更新按钮状态为"更新失败，重试"
                Platform.runLater(() -> updateBtn.setText("更新失败，重试"));
            } finally {
                // 无论成功失败，都重新启用按钮
                Platform.runLater(() -> updateBtn.setDisable(false));
            }
        });
    }

    /**
     * 启动窗口监控
     * <p>
     * 创建WindowsMonitor实例并开始监控目标游戏窗口
     * 监控器会定期捕获窗口画面并回调processFrame方法
     * </p>
     * <p>
     * Native资源管理：
     * WindowsMonitor持有Windows API句柄，必须在stop()时释放
     * </p>
     */
    private static void startCapture() {
        // 创建窗口监控器，指定要监控的游戏窗口名称
        windowsMonitor = new WindowsMonitor(AppConfig.TARGET_WINDOW_NAME);

        // 启动监控，指定回调函数处理捕获到的帧
        // processFrame会在独立线程中被调用
        windowsMonitor.startMonitor(MainApp::processFrame);
    }

    /**
     * 初始化大地图资源
     * <p>
     * 加载大地图图片和玩家图标，并初始化相关上下文
     * </p>
     * <p>
     * Native资源管理：
     * 使用try-with-resources确保InputStream正确关闭，防止文件句柄泄漏
     * JavaFX Image对象由JavaFX自动管理，无需手动释放
     * </p>
     *
     * @throws Exception 资源加载异常（如文件不存在、IO错误等）
     */
    private void initBigMapResource() throws Exception {
        // 加载大地图图片资源
        // 使用try-with-resources确保InputStream正确关闭
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.MAP_RESOURCE_PATH)) {
            Image rawImage = new Image(is);
            // 初始化地图上下文，保存大地图的引用和尺寸信息
            MapContext.getInstance().initWithKey(rawImage, rawImage.getWidth(), rawImage.getHeight(), "G");
        }

        // 加载玩家图标资源
        try (InputStream is = ResourceUtils.getResourceStream(AppConfig.PLAYER_ICON_PATH)) {
            // 初始化玩家渲染器，保存玩家图标引用
            PlayerRenderer.getInstance().initIcon(is);
        }
    }

    /**
     * 注册事件钩子
     * <p>
     * 注册各种功能钩子，如资源灰度计算、OCR识别等
     * 钩子通过事件总线接收帧捕获事件，实现功能解耦
     * </p>
     */
    private void registerHook() {
        // 创建资源灰度计算钩子
        // // 根据玩家位置计算哪些资源应该变灰（表示已采集）
        ResourceGrayHook resourceGrayHook = new ResourceGrayHook();

        // 创建实时OCR识别钩子
        // // 识别小地图上的文字信息（如地名、怪物名等）
        RealOcrHook realTimeOCRHook = new RealOcrHook();

        // 注册钩子到全局钩子注册表
        HookRegistry.INSTANCE.registers(resourceGrayHook, realTimeOCRHook);
    }

    /**
     * 应用程序停止方法
     * <p>
     * 在JavaFX窗口关闭时被调用，负责清理所有资源
     * 执行流程：
     * <ol>
     *   <li>启动看门狗线程，防止资源释放时死锁</li>
   *   <li>关闭事件总线，停止新事件处理</li>
   *   <li>停止窗口监控和渲染循环</li>
     *   <li>销毁Native资源（MapMatcher、OCR等）</li>
   *   <li>调用Platform.exit()和Runtime.exit()确保JVM退出</li>
     * </ol>
     * </p>
     * <p>
     * Native资源管理（关键）：
     * <ul>
     *   <li>显式调用destroy()方法释放OpenCV、PaddleOCR等Native内存</li>
   *   <li>关闭线程池，中断所有运行中的虚拟线程</li>
   *   <li>使用看门狗机制防止halt前的代码卡死</li>
     *   <li>最终调用Runtime.getRuntime().halt()强制退出，绕过ShutdownHook</li>
     * </ul>
     * </p>
     * <p>
     * 看门狗机制说明：
     * <ul>
     *   <li>创建一个后台线程，等待300毫秒后执行halt(0)</li>
   *   <li>如果正常资源释放在300毫秒内完成，看门狗线程被JVM终止，不会执行halt</li>
   *   <li>如果资源释放卡死，看门狗线程超时后强制halt，避免程序僵死</li>
     * </ul>
     * </p>
     * <p>
     * Native打包注意事项：
     * <ul>
     *   <li>Native Image环境下的资源释放可能更慢，需要给足够时间</li>
   *   <li>halt()不会执行ShutdownHook，但这是可接受的权衡</li>
   *   <li>显式释放Native资源比依赖GC更可靠，避免内存泄漏</li>
     * </ul>
     * </p>
     */
    @Override
    public void stop() {
        System.out.println(">>> 正在启动紧急退出程序...");

        // ====================== 看门狗线程启动 ======================
        // 立即开启一个"自杀计数器"线程（防止 stop 方法本身卡死）
        // 这是为了防止Native资源释放时出现死锁，导致程序无法退出
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(300); // 给 300 毫秒时间优雅退出
                System.err.println(">>> 优雅退出超时，执行强制毁灭 (halt)...");
                // halt 不会等待 ShutdownHook，直接杀掉 JVM
                // // 这是最保险的退出方式，确保程序一定能退出
                Runtime.getRuntime().halt(0);
            } catch (InterruptedException ignored) {}
        });
        watchdog.setDaemon(true); // 设置为守护线程，不阻止JVM退出
        watchdog.start();

        try {
            // ====================== 事件总线关闭 ======================
            // 获取事件多播器实例
            HookMulticaster multicaster = HookMulticaster.getInstance();

            // 先切断信号源：让所有新产生的事件直接丢弃
            // // 这避免了在关闭过程中继续处理新事件，防止竞态条件
            if (multicaster != null) {
                multicaster.shutdown();
            }

            // ====================== 资源释放（带中断） ======================
            // 停止窗口监控器，释放Windows API句柄
            if (windowsMonitor != null) windowsMonitor.stopMonitor();

            // 停止渲染循环，中断渲染线程
            if (renderLoop != null) renderLoop.stop();

            // ====================== Native资源销毁 ======================
            // 销毁地图匹配器，释放OpenCV内存
            // // 如果这些 destroy 耗时极长，考虑放进子线程异步关，或者直接跳过
            if (mapMatcher != null) mapMatcher.destroy();

            // 获取OCR异步管理器实例
            OcrAsyncManager ocrAsyncManager = OcrAsyncManager.getInstance();
            if (ocrAsyncManager != null) {
                // 在 close 内部，务必调用 executorService.shutdownNow() 强制中断虚拟线程
                // 这确保所有正在执行的OCR任务被取消，Native资源能及时释放
                ocrAsyncManager.close();
            }

            // ====================== JavaFX和JVM退出 ======================
            // 退出JavaFX平台
            Platform.exit();

            // 最后的挣扎：尝试通过exit()正常退出
            // // 如果前面的代码都顺利执行，这里会成功
            Runtime.getRuntime().exit(0);

        } catch (Exception e) {
            // 捕获退出过程中的异常
            System.err.println("退出过程中发生异常: " + e.getMessage());
            // 发生异常时直接halt，确保程序退出
            Runtime.getRuntime().halt(1);
        }
    }
}
