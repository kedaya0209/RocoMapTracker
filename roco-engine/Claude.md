#roco-engine 模块

核心引擎层 – 截图采集、上下文/状态管理、Hook 事件系统、匹配调度、进程管理

AI 协作专用 – 依赖 roco-common + roco-map，是连接各层的枢纽。


## 模块职责


- 截图采集：CaptureService 管理 C++ capture.exe 子进程，接收 BGRA 帧，分发 ROI 数据。
- 上下文管理：MapContext 存储地图/视口/玩家坐标；CameraContext 控制跟随；ResourcePointContext 管理资源点。
- Hook 事件：HookRegistry 单例提供发布/订阅，HookMulticast 异步分发事件（虚拟线程）。
- 匹配调度：SiftMatchHandler 协调 sift_match.exe 子进程，封装请求/响应协议。
- 进程管理：NativeProcess 抽象进程生命周期，JobObjectManager 确保子进程随父进程退出。
- 状态追踪：PlayerStateTracker 计算 EMA 平滑、瞬移检测、Lost 判定。
- 外部桥接：ExternalBridgeHandler 接收 pcap 子进程游戏事件（物资拾取、区域变更）。
- 进程通信：SocketServer 统一路由 Java ↔ C++/Python 子进程消息。


## 包结构及类清单 (73 个)


capture 包 – 截图采集生命周期 (7 个)：
CaptureService           截图会话管理：连接子进程、黑帧检测、ROI 下发
CaptureHandler           Socket 帧数据接收、反序列化、并行分发、背压控制
CaptureProcessManager    capture.exe 子进程生命周期管理
CaptureSessionManager    capture.exe Socket 会话状态管理
CaptureProtocol          capture.exe 协议常量与序列化
CaptureLaunchParams      截图启动参数
FullFrameControl         全帧截图控制接口（DIP 抽象，供设置面板调用）

capture.frame 包 – 帧数据结构 (3 个)：
CaptureFrameBuffer       全帧 + ROI 帧环形缓冲区
FrameDeserializer        帧数据字节流 → FrameSlot 反序列化 + 池化
ROIData                  ROI 坐标数据类（万分数）

capture.pipeline 包 – ROI 处理管线 (6 个)：
RoiProcessor             处理器接口：targetRoiIndex/onProcess/getRoi
ThroughputStats          吞吐量统计
AngleConverter           角度转换
MapMatcherProcessor      ROI-0 管线：小地图检测→圆遮罩→箭头检测→SIFT 匹配
MatchingWatchdog         匹配超时看门狗
SaveImageProcessor       调试用灰度帧保存

context 包 – 上下文/状态 (9 个)：
MapContext               核心枢纽：地图/视口/玩家坐标/角度 + Hook 发布
MapCoordinateManager     地图逻辑坐标 ↔ Canvas 像素坐标转换
CameraContext            摄像机跟随：followMode + followScale
PathContext              路线管理：保存/编辑/视图模式 + 持久化
ResourcePointContext     资源点位容器：加载/新增/删除 + 网格索引
ResourcePointGridIndex   空间网格索引：120px 格子 O(1) 近邻查询
MaterialCollectionContext 物资采集统计：累计计数 + 历史流水
RoutePersistenceService  路线持久化服务
StatsContext             性能统计：检测/匹配/方向耗时 + FPS

hook 包 – 事件系统 (15 个)：
IHook                    钩子接口
AbstractGenericHook<T>   泛型抽象基类
HookEventType            事件类型枚举
HookContainer            钩子容器：事件类型 → CopyOnWriteArrayList
HookMulticast            事件分发器：LinkedBlockingQueue + 虚拟线程
HookRegistry             枚举单例：注册/发布/销毁入口
HookEventTask            record(eventType, data)
CaptureStateEvent        截图开关状态事件
MaterialCollectionEvent  物资采集事件
NotificationType         通知类型枚举：SUCCESS/ERROR/INFO
ProgressEvent            进度更新事件
StatusCarouselEvent      状态轮播事件
StatusEvent              状态更新事件
EventBus                 泛型类型安全事件总线（发布/订阅/取消订阅）
AppEvents                应用级事件常量定义

match 包 – 匹配调度 (11 个)：
SiftMatchHandler         SIFT 门面：请求/响应协调 + 消息路由
SiftMatchProtocol        Socket 匹配协议编解码
SiftProcessManager       sift_match.exe 进程生命周期管理
SiftSessionManager       SIFT Socket 会话管理 + 热切换
SiftVariant              SIFT 变体配置枚举
FrameMatchSynchronizer   帧匹配同步协调
LaunchParams             启动参数值对象
MapImageLoader           地图图像加载
MapMatcher               匹配器接口：init/match/destroy
SwitchMapMatcher         4 种 SIFT 变体热切换策略器
PlayerStateTracker       玩家状态：EMA 平滑/瞬移检测/Lost 判定/角度传递

pcap 包 (1 个)：
PcapProcessManager       pcap.exe 子进程生命周期管理

platform 包 – 平台相关 (2 个)：
WindowFinder             User32.EnumWindows 按标题查找目标窗口
JobObjectManager         Windows Job Object 包装

process 包 – 进程管理 (3 个)：
NativeProcess            原生进程抽象：创建/读取 stdout/stderr/销毁
NativeProcessFactory     NativeProcess 平台工厂
ProcessRestartHelper     进程崩溃重启辅助

socket 包 (12 个)：
SocketServer             TCP Socket 注册中心 + 消息路由器（纯路由，无业务逻辑）
SocketSession            Socket 会话生命周期管理（传输层）
SocketHandler            Socket 消息处理基类
MessageSubscriber        统一消息订阅者接口（内部/外部平级）
SocketSubscriber         TCP 外部客户端的订阅者实现
HandlerSubscriber        Java 内部处理器的订阅者实现（桥接回调）
ClientInfo               record — 客户端注册信息（clientId, provides, subscribes）
ServiceRegistry          服务注册中心 — provider/subscriber 映射管理
ProducerLifecycleManager 进程生命周期管理（内部生产者重启）
ExternalBridgeHandler    外部桥接处理器：游戏事件分发
ExternalBridgeProtocol   外部桥接协议常量
SystemEvents             系统事件 serviceId 常量（连接/断开/注册/取消）



## 单例模式

| 类                         | 单例方式       | 持有全局状态                                 |
|---------------------------|--------------|-------------------------------------------|
| MapContext                | Holder 内部类 | mapImage, scale, offsetX/Y, playerX/Y/Angle |
| CameraContext             | Holder 内部类 | followMode, followScale                   |
| MapCoordinateManager      | 饿汉式        | mapConfigMap                              |
| PathContext               | 饿汉式        | savedRoutes, currentMode, activeRoute     |
| ResourcePointContext      | 饿汉式        | rawResourceList, pointList, gridIndex, collectSet |
| MaterialCollectionContext | 饿汉式        | summaryMap, historyLog, filters           |
| StatsContext              | 饿汉式        | 检测/匹配/方向耗时 + FPS                    |
| SwitchMapMatcher          | DCL          | volatile mapMatcher                       |
| HookRegistry              | 枚举单例       | HookContainer + HookMulticast              |

注：CaptureService、SiftMatchHandler 等为普通类，由调用方实例化。


本模块工具类清单（优先使用）


以下工具类位于 roco-engine，编辑本模块代码时应优先使用：

| 类名                     | 用途                                       |
|-------------------------|-------------------------------------------|
| MapContext              | 获取/更新玩家坐标、地图缩放、视口偏移           |
| CameraContext           | 跟随模式开关、跟随比例                      |
| ResourcePointContext    | 资源点 CRUD、邻近查询、网格索引              |
| MaterialCollectionContext | 物资统计累积、查询、过滤                   |
| HookRegistry            | 发布事件、注册/移除钩子                     |
| PlayerStateTracker      | 玩家位置平滑、瞬移检测、Lost 判定            |
| StatsContext            | 记录/获取各阶段耗时                         |
| WindowFinder            | 查找游戏窗口句柄                            |

**使用示例**：
- 更新玩家位置：`MapContext.getInstance().updatePlayerState(x, y, angle);`
- 发布事件：`HookRegistry.getInstance().publish(HookEventType.UI_NOTIFICATION, data);`


## 特殊约束


UI 线程安全
- 所有 UI 操作必须通过 `Platform.runLater()` 执行。
- HookMulticast 在虚拟线程中发布事件，回调中不得直接操作 JavaFX Node。

黑帧检测
- CaptureService 连续 30 帧全黑 → 强停截图并尝试重连。

匹配容差
- PlayerStateTracker 连续 5 次匹配失败才标记 Lost。

子进程管理
- CaptureProcessManager、SiftProcessManager 和 PcapProcessManager 使用 NativeProcess + JobObjectManager。
- 子进程崩溃后自动重启，Socket 自动重连。

ROI 下发
- ROI 坐标使用万分数 (0~10000)，由 MapMatcherProcessor 等处理器转换为实际像素。

虚拟线程
- HookMulticast 使用虚拟线程异步分发事件。


## 与其他模块的交互


- roco-common：读取各 Config 配置，使用 ResourceUtils、FileUtil。
- roco-map：调用 ImageLoader 获取图标，通过 ResourcePointContext 管理资源点。
- roco-ui：接收 Hook 事件更新界面，通过 CaptureServiceManager 控制截图开关。


## 典型使用示例


// 更新玩家坐标并发布事件
MapContext ctx = MapContext.getInstance();
ctx.updatePlayerState(x, y, angle);
HookRegistry.getInstance().publish(HookEventType.PLAYER_UPDATE, null);

// 注册钩子监听
HookRegistry.getInstance().registerHook(HookEventType.UI_NOTIFICATION, new IHook() {
@Override
public void onEvent(Object data) {
Platform.runLater(() -> notificationToast.show((String) data));
}
});

// 启动截图服务
CaptureService captureService = new CaptureService();
captureService.start(targetWindowTitle, roiConfigs);

// 使用 SIFT 匹配器
SwitchMapMatcher switcher = SwitchMapMatcher.getInstance();
switcher.switchVariant(SiftVariant.PCA_ULTRA);
switcher.match(frameGray, hintX, hintY);

// 玩家状态追踪
PlayerStateTracker tracker = new PlayerStateTracker();
tracker.updatePosition(x, y);
boolean lost = tracker.isLost();
double smoothedX = tracker.getSmoothedX();

// 获取性能统计
StatsContext stats = StatsContext.getInstance();
stats.recordMapDetect(detectionMs);
stats.recordMatchTiming(matchMs);
stats.recordDirectionTime(directionMs);
stats.recordSiftTimings(minimapMs, extractMs, flannMs);