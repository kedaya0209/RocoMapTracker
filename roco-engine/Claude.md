#roco-engine 模块

核心引擎层 – 截图采集、上下文/状态管理、Hook 事件系统、匹配调度、进程管理

AI 协作专用 – 依赖 roco-common + roco-map + roco-model + roco-macher，是连接各层的枢纽。


## 模块职责


- 截图采集：CaptureService 管理 C++ capture.exe 子进程，接收 BGRA 帧，分发 ROI 数据。
- 上下文管理：MapContext 存储地图/视口/玩家坐标；CameraContext 控制跟随；ResourcePointContext 管理资源点。
- Hook 事件：HookRegistry 单例提供发布/订阅，HookMulticast 异步分发事件（虚拟线程）。
- 匹配调度：SiftMatchHandler 协调 sift_match.exe 子进程，封装请求/响应协议。
- 进程管理：NativeProcess 抽象进程生命周期，JobObjectManager 确保子进程随父进程退出。
- 状态追踪：PlayerStateTracker 计算 EMA 平滑、瞬移检测、Lost 判定。
- 异步 OCR：OcrAsyncManager 管理 OCR 虚拟线程池和任务队列。


## 包结构及类清单 (52 个)


capture 包 – 截图采集 (12 个)：
CaptureService           截图会话管理：连接子进程、黑帧检测、ROI 下发
CaptureHandler           Socket 帧数据接收、反序列化、并行分发、背压控制
CaptureProcessManager    capture.exe 子进程生命周期管理
CaptureSessionManager    capture.exe Socket 会话状态管理
CaptureFrameBuffer       全帧 + ROI 帧环形缓冲区
FrameDeserializer        帧数据字节流 → FrameSlot 反序列化 + 池化
ROIData                  ROI 坐标数据类（万分数）
RoiProcessor             处理器接口：targetRoiIndex/onProcess/getRoi
WindowFinder             User32.EnumWindows 按标题查找目标窗口
MapMatcherProcessor      ROI-0 管线：小地图检测→圆遮罩→箭头检测→SIFT 匹配
OcrProcessor             ROI-1 管线：OCR→稳定性判定→物资计数
SaveImageProcessor       调试用灰度帧保存

context 包 – 上下文/状态 (9 个)：
MapContext               核心枢纽：地图/视口/玩家坐标/角度 + Hook 发布
MapCoordinateManager     地图逻辑坐标 ↔ Canvas 像素坐标转换
CameraContext            摄像机跟随：followMode + followScale
PathContext              路线管理：保存/编辑/视图模式 + 持久化
ResourcePointContext     资源点位容器：加载/新增/删除 + 网格索引
ResourcePointGridIndex   空间网格索引：120px 格子 O(1) 近邻查询
MaterialCollectionContext 物资采集统计：累计计数 + 历史流水
OcrAsyncManager          OCR 虚拟线程池 + 任务队列
StatsContext             性能统计：检测/匹配/方向耗时 + FPS

hook 包 – 事件系统 (12 个)：
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

macher 包 – 匹配调度 (8 个)：
SiftMatchHandler         SIFT 门面：请求/响应协调 + 消息路由
SiftMatchProtocol        Socket 匹配协议编解码
SiftProcessManager       sift_match.exe 进程生命周期管理
SiftSessionManager       SIFT Socket 会话管理 + 热切换
SiftVariant              SIFT 变体配置枚举
MapMatcher               匹配器接口：init/match/destroy
SwitchMapMatcher         4 种 SIFT 变体热切换策略器
ArrowDetector            CNN 箭头方向检测单例（封装 ArrowPredictService）
PlayerStateTracker       玩家状态：EMA 平滑/瞬移检测/Lost 判定/角度传递

process 包 – 进程管理 (3 个)：
NativeProcess            原生进程抽象：创建/读取 stdout/stderr/销毁
NativeProcessFactory     NativeProcess 平台工厂
JobObjectManager         Windows Job Object 包装

socket 包 (3 个)：
SocketServer             TCP Socket 服务端
SocketHandler            Socket 事件处理器接口
SocketSession            Socket 会话生命周期管理

utils 包 (1 个)：
OcrResultValidator       OCR 结果解析与校验



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
| OcrAsyncManager           | DCL          | executorService, servicePool              |
| SwitchMapMatcher          | DCL          | volatile mapMatcher                       |
| ArrowDetector             | DCL          | ArrowPredictService                        |
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
| ArrowDetector           | CNN 箭头角度检测（单例）                    |
| PlayerStateTracker      | 玩家位置平滑、瞬移检测、Lost 判定            |
| OcrAsyncManager         | 异步 OCR 任务提交                           |
| StatsContext            | 记录/获取各阶段耗时                         |
| OcrResultValidator      | OCR 结果格式校验                            |
| WindowFinder            | 查找游戏窗口句柄                            |

**使用示例**：
- 更新玩家位置：`MapContext.getInstance().updatePlayerState(x, y, angle);`
- 发布事件：`HookRegistry.getInstance().publish(HookEventType.UI_NOTIFICATION, data);`
- 提交 OCR：`OcrAsyncManager.getInstance().submitTask(roiMat, callback);`


## 特殊约束


UI 线程安全
- 所有 UI 操作必须通过 `Platform.runLater()` 执行。
- HookMulticast 在虚拟线程中发布事件，回调中不得直接操作 JavaFX Node。

黑帧检测
- CaptureService 连续 30 帧全黑 → 强停截图并尝试重连。

OCR 稳定性
- OcrProcessor 要求连续 2 次相同结果才更新物资计数。

匹配容差
- PlayerStateTracker 连续 5 次匹配失败才标记 Lost。

子进程管理
- CaptureProcessManager 和 SiftProcessManager 使用 NativeProcess + JobObjectManager。
- 子进程崩溃后自动重启，Socket 自动重连。

ROI 下发
- ROI 坐标使用万分数 (0~10000)，由 MapMatcherProcessor 和 OcrProcessor 转换为实际像素。

虚拟线程
- OcrAsyncManager 使用虚拟线程池（`Executors.newVirtualThreadPerTaskExecutor()`）。
- HookMulticast 使用虚拟线程异步分发事件。


## 与其他模块的交互


- roco-common：读取各 Config 配置，使用 ResourceUtils、FileUtil。
- roco-map：调用 ImageLoader 获取图标，通过 ResourcePointContext 管理资源点。
- roco-model：调用 ArrowPredictService 和 OcrService。
- roco-macher：调用 SiftMapMatcher 和 MiniMapDetector。
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

// 异步 OCR
OcrAsyncManager ocrManager = OcrAsyncManager.getInstance();
ocrManager.submitTask(roiMat, items -> {
Platform.runLater(() -> updateCounter(items));
});

// 玩家状态追踪
PlayerStateTracker tracker = new PlayerStateTracker();
tracker.updatePosition(x, y);
boolean lost = tracker.isLost();
double smoothedX = tracker.getSmoothedX();

// 获取性能统计
StatsContext stats = StatsContext.getInstance();
stats.recordDetectionTime(detectionMs);
stats.recordMatchTime(matchMs);
stats.recordArrowTime(arrowMs);