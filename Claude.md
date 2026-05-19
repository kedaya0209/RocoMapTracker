# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# RocoMapTracker 架构索引与逻辑约束文档

> **AI 协作专用** — 供 Claude 在后续开发中快速定位类、理解坐标转换与跨语言边界。
> 生成日期: 2026-05-19 | 项目版本: 1.1.0 | Java 25 + GraalVM Native Image | OpenCV: JavaCPP 4.13.0-1.5.13 | 截图: C++
> 子进程 + Socket IO

---

## 快速参考 — 构建/运行/测试命令

多模块 Maven 项目，主类 `com.luoke.app.Main`（位于 `roco-ui` 模块）。

```bash
# === 日常开发 (JVM 模式) ===
mvn clean compile                              # 编译所有模块
mvn compile -pl roco-ui -am                    # 仅编译 roco-ui 及其依赖
mvn javafx:run -pl roco-ui                     # JVM 模式运行
mvn test                                       # 运行全部测试
mvn test -Dtest=ClassName                      # 运行单个测试类

# === 打包 ===
mvn clean package                              # 打包所有模块
# fat jar 产物: roco-ui/target/roco-ui-1.1.0-jar-with-dependencies.jar

# === C++ 子进程编译 ===
# capture.exe:   WGC 截图引擎 (Visual Studio MSVC)
# sift_match.exe: SIFT 匹配引擎 (Visual Studio MSVC)
cd cpp && build_capture.bat                    # 编译 capture.exe
cd cpp && build_sift.bat                       # 编译 sift_match.exe
# 产物自动复制到 roco-ui/src/main/resources/{capture,sift}/

# === C JNI 库编译 (jniframe.c) ===
# Windows: cl /LD /Fe:jniframe.dll c/jniframe.c /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32"

# === Native Image 构建 ===
mvn -Pnative clean package -pl roco-ui -am     # 构建 native exe
mvn -Pnative-instrument clean package -pl roco-ui -am  # PGO Step 1: 插桩
# 运行插桩 exe 采集数据 → 生成 default.iprof
mvn -Pnative-pgo clean package -pl roco-ui -am         # PGO Step 2: 优化构建
```

---

# Language & Communication Rules (MANDATORY)

- IMPORTANT: You MUST ALWAYS communicate in Simplified Chinese (简体中文).
- All thinking, analysis, code comments, and responses MUST be in Chinese.
- Technical terms may remain in English only when writing code identifiers (variable names, class names, etc.).
- Violation of this rule is unacceptable.

---

## 多模块结构 (Multi-Module Structure)

项目采用 6 模块 Maven 多模块架构，依赖关系如下：

```
roco-ui ───────────── 最终应用 (JavaFX 界面 + 打包 + Native Image)
  └─ roco-engine ──── 核心引擎 (截图/上下文/Hook事件/匹配调度)
       ├─ roco-macher ─ SIFT 匹配算法
       │    ├─ roco-model ─ 模型推理 (ONNX/DJL)
       │    │    └─ roco-common ─ 基础工具 (配置/资源/JSON)
       │    └─ roco-common
       ├─ roco-map ──── 地图管理 (下载/拼接/资源点)
       │    └─ roco-common
       └─ roco-common
```

| 模块              | 文件数 | 总行数   | 资源路径                                    | 职责                                  |
|-----------------|------|--------|------------------------------------------|-------------------------------------|
| **roco-common** | 18   | ~1764  | extract-list.txt                         | 配置中心 (14 个 Config 类)、资源/JSON 工具     |
| **roco-model**  | 7    | ~667   | /model/*.onnx, ppocr_keys_v1.txt         | ONNX 推理基类、CNN 箭头检测、OCR 全流程         |
| **roco-map**    | 29   | ~2381  | /source/map/, /source/icon/, /source/point/ | 地图下载/拼接/瓦片、资源点模型、远程 Wiki 数据抓取     |
| **roco-macher** | 8    | ~1416  | —                                        | SIFT 匹配器 (4 变体)、小地图检测、数据集工具         |
| **roco-engine** | 52   | ~5411  | —                                        | 截图采集、上下文/Hook 事件、子进程管理、匹配器调度       |
| **roco-ui**     | 49   | ~9312  | /dll/, /icon/, /capture/, /sift/, /META-INF/ | JavaFX 界面、渲染引擎、SvgManager、设置面板、Native Image |
| **cpp/**        | 5    | ~3534  | —                                        | C++ WGC 截图 + SIFT 匹配子进程 (Socket IO) |
| **c/**          | 1    | 38     | —                                        | JNI 局部引用帧管理 (jniframe.c)            |

### 资源归属规则

- **roco-model** — ONNX 模型文件 (`/model/`)
- **roco-map** — 地图瓦片、图标、点位配置 (`/source/`)
- **roco-ui** — 运行时 DLL (`/dll/`, `/sift/`)、子进程 exe (`/capture/`, `/sift/`)、SVG 图标 (`/icon/`)、日志配置、Native Image 元数据
- **roco-common** — 资源提取清单 (`/extract-list.txt`)
- **roco-pcap/** — SQLite 静态数据库 (`data/roco_static.db`，非 Maven 模块)
- 所有资源运行时通过 classpath 统一访问

---

## 物理清点 (Physical Inventory)

### roco-common (基础工具层) — 18 文件

| 类名                      | 行数  | 职责简述                          |
|--------------------------|------|----------------------------------|
| `CaptureConfig`          | 76   | 截图配置：窗口名、FPS、黑帧检测阈值        |
| `ConfigHelper`           | 65   | Properties 类型安全读取工具（含默认值）   |
| `ConfigPersistence`      | 101  | 配置持久化：UTF-8 BOM 的 app_config.properties |
| `DownloadConfig`         | 111  | 下载设置：URL、超时、重试、并发数          |
| `MiniMapConfig`          | 73   | 小地图检测参数：resize、HoughCircles    |
| `OcrConfig`              | 122  | OCR 管线设置：并发、扫描间隔、ROI 坐标     |
| `PathConfig`             | 64   | 不可变路径常量：exe、地图、模型等          |
| `PlayerConfig`           | 52   | 玩家追踪：EMA 平滑、瞬移检测、Lost 容差    |
| `RenderConfig`           | 112  | 渲染/动画：图标尺寸、缩放、玩家标记、Toast   |
| `SiftConfig`             | 171  | SIFT 匹配参数：特征检测、FLANN、RANSAC  |
| `SocketConfig`           | 59   | Socket/子进程：超时、重启间隔            |
| `StatsConfig`            | 53   | 统计覆盖层：FPS、耗时显示开关             |
| `UiConfig`               | 90   | UI/交互：主题、字体、缩放、hover 半径     |
| `ViewConfig`             | 96   | 地图视图：缩放、跟随模式、置灰距离           |
| `ResourceConfigContext`  | 93   | 资源套件枚举：INTERNAL / EXTERNAL 切换  |
| `FileUtil`               | 252  | 文件工具：Native 检测、MD5、资源释放       |
| `JsonUtils`              | 36   | Jackson ObjectMapper 单例           |
| `ResourceUtils`          | 88   | 资源加载：外部文件优先 → classpath 回退  |

### roco-model (模型推理层) — 7 文件

| 类名                    | 行数  | 职责简述                              |
|-----------------------|------|-------------------------------------|
| `BaseOnnxManager`     | 79   | 🔴 ONNX 基类：loadModel/rebuild/close    |
| `ItemResult`          | 28   | OCR 结果 record(name, count)         |
| `ArrowOnnxManager`    | 23   | 箭头 CNN 模型管理器 (单线程 ONNX Runtime)  |
| `ArrowPredictService` | 104  | 箭头方向推理：64×64 灰度 → sin/cos → 角度    |
| `OcrService`          | 297  | OCR 全流程：letterbox → det → rec (纯 Java) |
| `OnnxDetManager`      | 47   | 文本检测 ONNX 模型管理器                  |
| `OnnxRecManager`      | 89   | 文本识别 ONNX 模型管理器 + CTC 解码         |

### roco-map (地图管理层) — 29 文件

| 类名                        | 行数  | 职责简述                         |
|---------------------------|------|-------------------------------|
| `MapAssembler`            | 229  | 16 图块并行加载 → 洪水填充 → 两张 8192 输出 |
| `MapResourceUpdater`      | 169  | 顶层更新协调器：下载→构建→移动            |
| `MapTileProcessor`        | 93   | 图块元数据 JSON 解析                |
| `ResourceExporter`        | 207  | 从游戏解包数据导出资源点和图标             |
| `MapDownloader`           | 283  | BFS 图块下载器 + 断点续传             |
| `MapStitcher`             | 80   | 图块拼接为完整地图 PNG                |
| `IconDownloader`          | 129  | 并发图标下载（虚拟线程 + 信号量）          |
| `ResourceConfigBuilder`   | 77   | 分类+点位 → resource_config.json  |
| `DownloadProgressContext` | 56   | 下载进度 AtomicInteger 跟踪         |
| `LoadInfo`                | 107  | 远程配置/点位/分类加载协调              |
| `MapConfigLoader`         | 64   | Jsoup 抓取 mapData script        |
| `MapCategoryLoader`       | 90   | Jsoup 抓取 categoryData pre      |
| `MapPointLoader`          | 101  | Jsoup 抓取 mapPointData pre      |
| `ImageLoader`             | 40   | 图标字节缓存 ConcurrentHashMap     |
| `JsMapConfigParser`       | 126  | JS 对象正则解析 → MapConfig DTO    |
| `MapFileMover`            | 161  | 暂存→最终目录移动 + init 清单          |
| DTO (6 files)             | 12-74 | LayerOption, MapCategoryItem, MapConfig, MapLayer, MapPointItem, Point |
| Entity (2 files)          | 14,43 | DownloadResult, Tile             |
| Model (4 files)           | 16-37 | Point, ResourceConfig, ResourcePoint, RoutePath |

### roco-macher (匹配算法层) — 8 文件

| 类名                       | 行数  | 职责简述                      |
|--------------------------|------|----------------------------|
| `SiftMapMatcher`         | 568  | 🔴 核心 SIFT 匹配器：4 变体 + 重叠分块 |
| `DescriptorTransform`    | 221  | 描述符变换管道：PCA + 量化 + Zstd 缓存 |
| `CircleMaskApplier`      | 50   | 纯 Java 圆遮罩：零化圆外像素          |
| `MiniMapDetector`        | 143  | HoughCircles 小地图圆检测 + 校验    |
| `PlayerAngle`            | 7    | record(found, angle)        |
| `DatasetGeneratorServer` | 158  | HTTP 数据集生成服务 (arrow 训练数据)  |
| `MoveValidationDataSet`  | 54   | 训练/验证目录同步工具               |
| `PCARecalibrator`        | 215  | PCA 角度重校准 + 调试可视化          |

### roco-engine (核心引擎层) — 52 文件

#### capture 包 — 截图采集 (12 文件)

| 类名                      | 行数  | 职责简述                              |
|--------------------------|------|-------------------------------------|
| `CaptureService`         | 197  | 截图会话管理：连接 C++ 子进程 + 黑帧检测 + ROI 下发 |
| `CaptureHandler`         | 312  | Socket 帧数据接收、反序列化、并行分发、背压控制      |
| `CaptureProcessManager`  | 101  | capture.exe 子进程生命周期管理              |
| `CaptureSessionManager`  | 62   | capture.exe Socket 会话状态管理           |
| `CaptureFrameBuffer`     | 77   | 全帧 + ROI 帧环形缓冲区                   |
| `FrameDeserializer`      | 67   | 帧数据字节流 → FrameSlot 反序列化 + 池化     |
| `ROIData`                | 31   | ROI 坐标数据类 (万分数)                   |
| `RoiProcessor`           | 25   | 处理器接口：targetRoiIndex/onProcess/getRoi |
| `WindowFinder`           | 174  | User32.EnumWindows 按标题查找目标窗口       |
| `MapMatcherProcessor`    | 153  | ROI-0 管线：小地图→圆遮罩→箭头检测→SIFT 匹配  |
| `OcrProcessor`           | 124  | ROI-1 管线：OCR→稳定性判定→物资计数          |
| `SaveImageProcessor`     | 107  | 调试用灰度帧保存                          |

#### context 包 — 上下文/状态 (9 文件)

| 类名                         | 行数  | 职责简述                              |
|-----------------------------|------|-------------------------------------|
| `MapContext`                | 115  | 🔴 核心枢纽：地图/视口/玩家坐标/角度 + Hook 发布    |
| `MapCoordinateManager`      | 68   | 地图逻辑坐标 ↔ Canvas 像素坐标转换            |
| `CameraContext`             | 91   | 摄像机跟随：followMode + followScale      |
| `PathContext`               | 109  | 路线管理：保存/编辑/视图模式 + 持久化              |
| `ResourcePointContext`      | 149  | 资源点位容器：加载/新增/删除 + 网格索引             |
| `ResourcePointGridIndex`    | 66   | 空间网格索引：120px 格子 O(1) 近邻查询         |
| `MaterialCollectionContext` | 79   | 物资采集统计：累计计数 + 历史流水                 |
| `OcrAsyncManager`           | 133  | OCR 虚拟线程池 + 任务队列                    |
| `StatsContext`              | 105  | 性能统计：检测/匹配/方向耗时 + FPS              |

#### hook 包 — 事件系统 (12 文件)

| 类名                          | 行数  | 职责简述                         |
|-----------------------------|------|-------------------------------|
| `IHook`                     | 11   | 钩子接口                         |
| `AbstractGenericHook<T>`    | 38   | 泛型抽象基类                       |
| `HookEventType`             | 20   | 事件类型枚举                       |
| `HookContainer`             | 58   | 钩子容器：事件类型 → CopyOnWriteArrayList |
| `HookMulticast`             | 122  | 事件分发器：LinkedBlockingQueue + 虚拟线程 |
| `HookRegistry`              | 72   | 🔴 枚举单例：注册/发布/销毁入口            |
| `HookEventTask`             | 6    | record(eventType, data)       |
| `CaptureStateEvent`         | 3    | 截图开关状态事件                     |
| `MaterialCollectionEvent`   | 5    | 物资采集事件                       |
| `NotificationType`          | 4    | 通知类型枚举：SUCCESS/ERROR/INFO    |
| `ProgressEvent`             | 3    | 进度更新事件                       |
| `StatusCarouselEvent`       | 86   | 状态轮播事件                       |
| `StatusEvent`               | 3    | 状态更新事件                       |

#### macher 包 — 匹配调度 (8 文件)

| 类名                      | 行数  | 职责简述                          |
|--------------------------|------|---------------------------------|
| `SiftMatchHandler`       | 375  | SIFT 门面：请求/响应协调 + 消息路由       |
| `SiftMatchProtocol`      | 183  | Socket 匹配协议编解码                |
| `SiftProcessManager`     | 183  | sift_match.exe 进程生命周期管理        |
| `SiftSessionManager`     | 175  | SIFT Socket 会话管理 + 热切换         |
| `SiftVariant`            | 76   | SIFT 变体配置枚举                    |
| `MapMatcher`             | 40   | 匹配器接口：init/match/destroy       |
| `SwitchMapMatcher`       | 80   | 4 种 SIFT 变体热切换策略器             |
| `ArrowDetector`          | 70   | CNN 箭头方向检测单例                  |
| `PlayerStateTracker`     | 102  | 玩家状态：EMA 平滑/瞬移检测/Lost 判定/角度传递 |

#### process 包 — 进程管理 (3 文件)

| 类名                      | 行数  | 职责简述                         |
|--------------------------|------|-------------------------------|
| `NativeProcess`          | 447  | 原生进程抽象：创建/读取 stdout/stderr/销毁 |
| `NativeProcessFactory`   | 21   | NativeProcess 平台工厂           |
| `JobObjectManager`       | 307  | Windows Job Object 包装         |

#### socket 包 (3 文件)

| 类名              | 行数  | 职责简述               |
|-----------------|------|----------------------|
| `SocketServer`  | 305  | TCP Socket 服务端      |
| `SocketHandler` | 41   | Socket 事件处理器接口      |
| `SocketSession` | 99   | Socket 会话生命周期管理    |

#### utils (1 文件)

| 类名                    | 行数  | 职责简述          |
|-----------------------|------|-----------------|
| `OcrResultValidator`  | 51   | OCR 结果解析与校验 |

### roco-ui (用户界面层) — 49 文件

#### 入口

| 类名              | 行数  | 职责简述                      |
|-----------------|------|---------------------------|
| `Main`          | 13   | 入口点：初始化 OpenCV → launch JavaFX |
| `ModernCanvasApp` | 168  | 主 Application：初始化 + UI 构建 + 截图守护 |

#### hook 实现

| 类名              | 行数  | 职责简述                           |
|-----------------|------|----------------------------------|
| `UiResponseHook` | 63   | 监听 UI_NOTIFICATION / INIT_PROGRESS / CAPTURE_STATE |

#### component — UI 组件 (15 文件)

| 类名                      | 行数  | 职责简述                              |
|--------------------------|------|-------------------------------------|
| `InteractiveCanvas`      | 209  | 🔴 交互画布：鼠标/键盘事件 → 委托 PathEditor/HoverManager |
| `Sidebar`                | 479  | 左侧分类侧边栏：算法/资源/主题/路线/WIKI 更新        |
| `TitleBar`               | 331  | 标题栏 + 状态轮播 + 幽灵模式 (透明度+置顶)         |
| `RouteManagerStage`      | 452  | 路线管理窗口：保存/编辑/导入/导出                 |
| `FloatToolbox`           | 114  | 浮动工具栏：自动跟随/物资计数开关                  |
| `HoverManager`           | 110  | 鼠标 hover 检测 + 提示框                  |
| `PathEditor`             | 197  | 路线绘制/编辑：节点增删拖拽 + Ctrl+Z 撤销         |
| `LoadingOverlay`         | 86   | 全屏加载遮罩 + 进度条                       |
| `NotificationToast`      | 92   | 滑入/滑出 Toast 通知                     |
| `ResourceCounterPanel`   | 126  | 物资采集统计面板（订阅 Hook 事件）               |
| `StatsOverlay`           | 77   | 实时性能覆盖层：匹配耗时/FPS                   |
| `ContextMenuManager`     | 132  | 右键菜单：添加标记/重置视口/资源点操作              |
| `UiAnimator`             | 66   | 侧边栏滑入/滑出动画                        |
| `WikiUpdateManager`      | 154  | WIKI 资源更新：下载按钮 + 进度条               |
| `AddPointDialog`         | 146  | 添加地图标记对话框 + 自动补全                  |

#### component/setting — 设置面板 (10 文件)

| 类名                      | 行数  | 职责简述                              |
|--------------------------|------|-------------------------------------|
| `SettingsStage`          | 482  | 单例 IntelliJ 风格设置窗口：分类列表 + 右侧配置面板 |
| `SettingDefinitions`     | 599  | 11 分类全部可配置项定义注册中心                  |
| `SettingConfigManager`   | 305  | 设置数据层：控件注册/配置读写/变更追踪/快照管理         |
| `SettingFieldBuilder`    | 248  | 配置控件工厂：CheckBox/Spinner/ComboBox 等 |
| `RoiPreview`             | 820  | ROI 截帧预览 + 全帧模式 + 弹出调整窗口           |
| `PlayerPreview`          | 192  | 玩家实时动画预览：图标 + 拾取光晕 + 波纹            |
| `SettingCategory`        | 13   | record(name, icon, fields)         |
| `SettingCategoryCell`    | 64   | 分类列表单元格：hover 高亮 + SVG 图标动画       |
| `SettingDef`             | 26   | record(key, label, type, getter/setter) |
| `SettingType`            | 8    | 枚举：BOOLEAN/INTEGER/LONG/DOUBLE/STRING/COMBO |

#### render — 渲染引擎 (6 文件)

| 类名                    | 行数  | 职责简述                               |
|-----------------------|------|--------------------------------------|
| `MapRenderer`         | 247  | 渲染循环协调器：Timeline 驱动 + viewportDirty 快照 |
| `IconLayerManager`    | 138  | 资源点图标 ImageView 节点管理 + 灰度切换          |
| `PlayerRenderer`      | 126  | 玩家图标渲染：角度旋转 + 潮汐波纹 + 拾取光晕          |
| `RouteRenderer`       | 169  | Canvas 路线渲染：脏检测 + 平移补偿               |
| `HoverRenderer`       | 105  | Canvas hover 高亮：辉光 + 资源图标             |
| `RenderLayer`         | 22   | 渲染层接口：getNode() / onFrame()           |

#### service — 服务 (12 文件)

| 类名                        | 行数  | 职责简述                              |
|---------------------------|------|-------------------------------------|
| `SvgManager`              | 901  | SVG 加载/缓存/构建/画线动画 (XML DOM 解析)    |
| `ResourceInitService`     | 306  | 资源初始化验证 + 首运行对话框 + 地图元数据加载       |
| `TileManager`             | 233  | 瓦片 ImageView 生命周期：级别选择/视口约束/回收   |
| `IconCache`               | 214  | 图标纹理图集：彩色+灰度双图集, GPU 槽位访问        |
| `TileGeneratorService`    | 176  | 多分辨率瓦片金字塔生成 (5 级)                |
| `MainUiComposer`          | 157  | 静态 UI 组装器：场景图构建                   |
| `CaptureServiceManager`   | 86   | CaptureService 生命周期 + 看门狗重连        |
| `WindowManager`           | 126  | 窗口拖拽/缩放：8 方向边缘矩形                  |
| `SiftClientManager`       | 60   | SIFT 客户端生命周期：启动/重启/停止             |
| `InfrastructureManager`   | 36   | JobObject + SocketServer 生命周期       |
| `ThemeManager`            | 38   | 7 种 AtlanFX 主题切换                    |
| `ResourceInitUiDelegate`  | 28   | 资源初始化 UI 回调接口                     |

#### util (3 文件)

| 类名              | 行数  | 职责简述              |
|-----------------|------|---------------------|
| `DialogUtils`   | 228  | 模态覆盖对话框工具         |
| `FxRippleUtil`  | 107  | Material Design 涟漪效果 |
| `RestartUtils`  | 37   | Native Image 兼容的重启工具 |

### C++ 源文件

| 文件                   | 行数   | 职责                                     |
|----------------------|-------|----------------------------------------|
| `cpp/capture_main.cpp` | 1210  | WGC 截图子进程：D3D11 → Socket 推送 BGRA 帧数据   |
| `cpp/sift_match_main.cpp` | 1458 | SIFT 匹配子进程：Socket 接收请求 → FLANN + RANSAC 返回坐标 |
| `cpp/wgc_capture.cpp`   | 633   | JNA 兼容 WGC 截图 DLL (已由 capture.exe 替代)    |
| `cpp/test_wgc.cpp`      | 232   | wgc_capture.dll 内存稳定性测试                 |

### C 源文件

| 文件               | 行数  | 职责                                       |
|------------------|------|------------------------------------------|
| `c/jniframe.c`   | 38   | JNI PushLocalFrame/PopLocalFrame 管理       |

---

## 单例模式一览

| 类                           | 所在模块        | 单例方式       | 持有全局状态                                                                       |
|-----------------------------|-------------|------------|------------------------------------------------------------------------------|
| `MapContext`                | roco-engine | Holder 内部类 | mapImage, scale, offsetX/Y, playerX/Y/Angle                                 |
| `CameraContext`             | roco-engine | Holder 内部类 | followMode (BooleanProperty), followScale                                   |
| `MapCoordinateManager`      | roco-engine | 饿汉式        | mapConfigMap                                                                 |
| `PathContext`               | roco-engine | 饿汉式        | savedRoutes, currentMode, activeRoute                                        |
| `ResourcePointContext`      | roco-engine | 饿汉式        | rawResourceList, pointList, gridIndex, collectSet                            |
| `ResourceConfigContext`     | roco-common | 静态枚举切换     | currentProfile (INTERNAL / EXTERNAL)                                         |
| `StatsContext`              | roco-engine | 饿汉式        | 检测/匹配/方向耗时 + FPS                                                       |
| `MaterialCollectionContext` | roco-engine | 饿汉式        | summaryMap, historyLog, filters                                              |
| `OcrAsyncManager`           | roco-engine | DCL        | executorService, servicePool                                                 |
| `SwitchMapMatcher`          | roco-engine | DCL        | volatile mapMatcher                                                          |
| `ArrowDetector`             | roco-engine | DCL        | ArrowPredictService                                                          |
| `ImageLoader`               | roco-map    | 饿汉式        | imageCache: ConcurrentHashMap                                                |
| `IconCache`                 | roco-ui     | 懒汉式        | iconCache: ConcurrentHashMap                                                 |
| `HookRegistry`              | roco-engine | 枚举单例       | HookContainer + HookMulticast                                                |
| `MapRenderer`               | roco-ui     | DCL        | AnimationTimer, viewportDirty                                                |
| `PlayerRenderer`            | roco-ui     | Holder 内部类 | playerImage                                                                  |
| `SettingsStage`             | roco-ui     | DCL        | 设置面板单例窗口                                                                |

---

## 数据流枢纽图

```
C++ capture.exe → Socket → CaptureHandler → CaptureFrameBuffer
  │
  ├─ ROI-0 (小地图) → MapMatcherProcessor
  │    ├─ trackOrDetectMiniMap → MiniMapDetector (HoughCircles)
  │    ├─ applyFastCircleMask → CircleMaskApplier
  │    ├─ executeArrowDect → ArrowDetector → ArrowPredictService → CNN (角度)
  │    ├─ executeMatching → SiftMatchHandler → C++ sift_match.exe (坐标)
  │    └─ MapContext.updatePlayerState(x, y, angle)
  │         └─ HookRegistry.publish(PLAYER_UPDATE)
  │              ├─ ResourceGrayHook → 邻近点置灰
  │              └─ CameraContext.updateViewport()
  │
  └─ ROI-1 (物品栏) → OcrProcessor
       ├─ OcrAsyncManager.submitTask (虚拟线程池)
       ├─ OcrService.recognizeAll (det + rec)
       ├─ OcrResultValidator.parse → ItemResult
       └─ MaterialCollectionContext.addMaterial

MapContext (scale/offset) ← CameraContext ← MapRenderer (AnimationTimer)
  ├─ viewportDirty 检测 → 重建 mapSnapshot (地图+图标+路线)
  ├─ IconLayerManager: 资源点图标 (纹理图集 GPU)
  ├─ PlayerRenderer: 玩家图标 (角度旋转 + 光晕)
  ├─ RouteRenderer: 路线 Canvas (脏检测 + 平移补偿)
  └─ HoverRenderer: hover 高亮 Canvas
```

---

## 事件流全景

```
HookEventType.PLAYER_UPDATE
  ← MapContext.updatePlayerState()
  → ResourceGrayHook (置灰邻近资源)
  → CameraContext (自动跟随偏移)

HookEventType.UI_NOTIFICATION
  ← OcrProcessor / ModernCanvasApp / ResourcePointContext
  → UiResponseHook → NotificationToast.show()

HookEventType.INIT_PROGRESS
  ← ModernCanvasApp.publishInitStep()
  → UiResponseHook → LoadingOverlay.updateProgress()

HookEventType.CAPTURE_STATE
  ← CaptureService (tryConnect/stop/callback)
  → UiResponseHook → NotificationToast

HookEventType.RESOURCE_POINT_CHANGED
  ← ResourcePointContext (loadAndInit/savePoint/deletePoint)
  → MapRenderer.markDirty() → mapSnapshot = null

HookEventType.MATERIAL_COLLECTION_UPDATED
  ← MaterialCollectionContext.addMaterial/removeMaterial
  → ResourceCounterPanel.refreshData()

HookEventType.STATUS_CAROUSEL
  ← CaptureService / 各模块状态变更
  → TitleBar 状态轮播显示

HookEventType.MAP_COORD_UPDATED / MAP_NAME_UPDATED / RESOURCE_FOUND
  (预留事件，当前无订阅者)
```

---

## 跨语言边界逻辑

### C++ 子进程 ↔ Java Socket 通信

| C++ 侧                      | Java 侧                             | 说明                               |
|----------------------------|-------------------------------------|-----------------------------------|
| `capture.exe` (WGC 截图)     | `CaptureHandler` + `CaptureProcessManager` | Socket IO 多路复用，BGRA 帧数据传输         |
| `sift_match.exe` (SIFT 匹配) | `SiftMatchHandler` + `SiftProcessManager` | 请求-响应模式，特征匹配卸载到独立进程               |
| ROI 坐标使用 **万分数** (0~10000) | `MapMatcherProcessor` 配置 ROI 范围        | 百分比定位，自适应分辨率                      |
| `SocketSession` 协议          | 帧头 + 数据体，code=0 正常 / code=-1 断开       | `CaptureService` 判断 code 标记断开 |

### 子进程生命周期管理

- `NativeProcess` — 启动/监控/重启 C++ 子进程
- `NativeProcessFactory` — NativeProcess 平台工厂
- `CaptureProcessManager` — capture.exe 进程管理
- `SiftProcessManager` — sift_match.exe 进程管理
- `JobObjectManager` — Windows Job Object 包装，父进程退出时子进程自动销毁
- `SocketServer` — 管理多个 C++ 子进程的 Socket 连接和会话

---

## 坐标系数学

### 三层坐标系定义

| 层级     | 名称                  | 原点         | 单位       | 范例           |
|--------|---------------------|------------|----------|--------------|
| **L1** | 屏幕像素 (Screen Pixel) | Canvas 左上角 | 物理像素     | (550, 400)   |
| **L2** | Canvas 逻辑像素         | 地图左上角      | 1:1 地图像素 | (3200, 2400) |
| **L3** | 地图逻辑坐标              | **地图中心**   | 缩放后逻辑单位  | (-120, 85)   |

### 转换公式

#### L1 ↔ L2: 屏幕像素 ↔ Canvas 逻辑像素

CanvasX = (ScreenX - offsetX) / scale

ScreenX = offsetX + CanvasX × scale

> 代码来源: `InteractiveCanvas.toLogicX/Y()` (L1→L2), `MapContext.getPlayerCanvasX/Y()` (L2→L1)

#### L2 ↔ L3: Canvas 逻辑像素 ↔ 地图逻辑坐标 (中心原点)

CanvasX = mapWidth/2 + x · 2^(imageZoom - jsonZoom)

x = (CanvasX - mapWidth/2) / 2^(imageZoom - jsonZoom)

> 代码来源: `MapCoordinateManager.toScreen()` / `fromScreen()`
> 缩放因子 scale_coord = 2^(imageZoom - jsonZoom)，默认 1:1

### 缩放交互 (Zoom)

newOffsetX = mouseX - (mouseX - oldOffsetX) × newScale/oldScale

> 代码来源: `MapContext.zoom(factor, mx, my)`
> 约束: minScale = max(viewW/mapW, viewH/mapH), maxScale = 15

### 跟随模式 (Follow Mode)

offsetX = viewWidth/2 - playerX × followScale

---

## 架构约束

### C++ 子进程硬约束

| 约束                    | 原因                                   |
|-----------------------|--------------------------------------|
| ROI 坐标使用万分数 (0~10000) | 自适应不同分辨率/DPI                         |
| Socket IO 多路复用        | 单 Socket 连接处理多 ROI 帧数据               |
| 子进程随父进程自动销毁           | JobObjectManager 绑定，防止孤儿进程           |
| 子进程崩溃自动重连             | NativeProcess 监控 + CaptureService 重连 |

### Java 侧硬约束

| 约束                                          | 原因                            |
|---------------------------------------------|-------------------------------|
| SIFT 匹配每帧包裹 `try (PointerScope scope)`      | nopointergc 下 Scope 是唯一批量回收机制 |
| `ArrowPredictService` 每 200 帧重置 NDManager   | DJL + ORT 内部 Arena 分配器累积内存    |
| `OcrService` 成员 Mat 在 `close()` 前禁止 release | 复用容器，后续访问已释放指针 → JVM 崩溃       |
| `ImageLoader.imageCache` 强引用，不可清除           | 渲染循环每帧读取，GC 回收导致图标闪烁          |
| `IconCache.iconCache` 强引用，不可清除              | 渲染循环每帧读取，GC 回收导致图标闪烁          |
| `MapRenderer.mapSnapshot` 只含地图+图标+路线        | viewportDirty 快照复用，动态元素每帧独立绘制 |

### 架构级约束

| 约束                               | 说明                                |
|----------------------------------|-----------------------------------|
| 所有 UI 操作通过 `Platform.runLater()` | HookMulticast 虚拟线程中直接操作 Node 会抛异常 |
| Hook 事件单向流：数据层 → UI 层            | 禁止在 Hook 回调中修改核心状态                |
| CaptureService 黑帧检测阈值: 30 帧      | 连续 30 帧全黑 → 强停 + 自动重连             |
| OCR 稳定性判定: 2 次连续相同               | 防止 OCR 误识别导致计数跳变                  |
| 地图匹配连续失败 5 次才标记 Lost             | 防止偶发失败导致玩家图标闪烁                    |
| SIFT 匹配通过 C++ 子进程异步执行，不阻塞主线程     | Socket 通信模式，匹配请求与箭头检测并行执行         |

### 模块间依赖约束

| 约束                                      | 说明                       |
|-----------------------------------------|--------------------------|
| roco-common 不依赖任何内部模块                   | 无 JavaFX、无模型类依赖          |
| roco-model 仅依赖 roco-common              | 推理逻辑不涉及地图/匹配             |
| roco-map 依赖 roco-common + JavaFX        | 资源点/图标渲染需要 JavaFX 类型     |
| roco-macher 依赖 roco-common + roco-model | 不依赖 roco-engine (避免循环依赖) |
| roco-engine 依赖所有核心模块                    | 截图/上下文/事件汇集各层            |
| roco-ui 仅直接依赖 roco-engine               | 其余模块通过传递依赖引入             |

### 资源路径系统

```
内嵌资源 (classpath):
  roco-model:     /model/*.onnx, /model/ppocr_keys_v1.txt
  roco-map:       /source/map/, /source/icon/, /source/point/
  roco-ui:        /dll/*.dll, /capture/capture.exe, /sift/*.dll, /sift/sift_match.exe
  roco-ui:        /icon/*.svg, /logback.xml, /META-INF/**
  roco-common:    /extract-list.txt

外部资源 (磁盘): ResourceUtils.getExternalFile()
  ├─ 首次运行: 从 classpath 释放到外部目录
  └─ SIFT 缓存: .feat / .pca64.ultra.feat (Zstd 压缩)
```

### GraalVM Native Image 特殊约束

| 约束                                     | 说明                                          |
|----------------------------------------|---------------------------------------------|
| DLL 必须在运行时释放到临时目录                      | Native Image 不支持从 JAR 内直接加载 DLL             |
| `reachability-metadata.json` 必须完整      | 反射/JNI 访问类必须在元数据中声明                         |
| JNI 局部引用泄漏风险更高                         | Serial GC 不像 G1 那样频繁触发                      |
| `System.gc()` 在 Serial GC 下有效          | 同步全量回收                                      |
| roco-common 需依赖 `graal-sdk` (provided) | `--release 25` 限制 classpath，ImageInfo 需显式依赖 |

### JavaCPP OpenCV 约束 (nopointergc=true)

项目使用 JavaCPP (`org.bytedeco.opencv.*`) 4.13.0-1.5.13。
`Main.java` 启动时设置 `System.setProperty("org.bytedeco.javacpp.nopointergc", "true")`，完全禁用 GC 自动回收 Native 指针。

#### 内存管理核心规则

| 规则                                                 | 说明                                                     |
|----------------------------------------------------|--------------------------------------------------------|
| **所有临时 Native 对象在 `try (PointerScope scope)` 内创建** | KeyPointVector, Mat, DMatchVectorVector 等，scope 自动批量回收 |
| **FlannBasedMatcher 在 scope 外创建**                  | 长期存活的匹配器，训练临时对象在嵌套 scope 中                             |
| **严禁散乱的 `.close()` 调用**                            | 仅在 `destroy()` 中关闭字段级长期 Mat                            |
| **asyncRebuildMatcher lambda 内打开自己的 scope**        | 调度线程有独立 PointerScope 上下文                               |
| **长期存活的 Mat 在 scope 外创建**                          | 字段级 Mat 在构造/init 中创建于所有 scope 之外                       |
| **字段级 Mat 重建必须在 PointerScope 外**                   | 生命周期跨越多个请求，scope 内重建会析构底层内存                            |

#### FLANN 强制配置

```java
// ✅ 正确
new FlannBasedMatcher(
    new KDTreeIndexParams(1),       // 单树模式
    new SearchParams(24, 0, true)   // checks=24, 不排序, 启用 KD-tree
);
// ❌ 禁止: new FlannBasedMatcher();  // 默认多树 → 内存超限
```

#### Mat 数据存取

```java
// 浮点数据 → FloatPointer 包装
new FloatPointer(mat.data()).put(floatArray);
new FloatPointer(mat.data()).get(floatArray);
// 字节数据 → BytePointer (data() 返回 BytePointer)
mat.data().put(byteArray);
// 指定行
new BytePointer(mat.ptr(row, 0)).get(byteArray, 0, width);
```

#### SIFT.create() 签名 (6 参数)

```java
SIFT.create(nfeatures, nOctaveLayers, contrastThreshold, edgeThreshold, sigma, false);
```

#### 常量和类型对应

| OpenPNP                             | JavaCPP                               |
|-------------------------------------|---------------------------------------|
| `Core.CV_8UC1`                      | `opencv_core.CV_8UC1`                 |
| `Core.CV_32F`                       | `opencv_core.CV_32F`                  |
| `Calib3d.RANSAC`                    | `opencv_calib3d.RANSAC`               |
| `Imgproc.COLOR_BGR2GRAY`            | `opencv_imgproc.COLOR_BGR2GRAY`       |
| `MatOfKeyPoint`                     | `KeyPointVector`                      |
| `MatOfDMatch` / `List<MatOfDMatch>` | `DMatchVector` / `DMatchVectorVector` |
| `Vec3f` (HoughCircles)              | `Point3f`                             |

---

## ROI 布局

| ROI Index | 用途              | 万分数坐标                    | 实际覆盖 (以 1920×1080 为例) |
|-----------|-----------------|--------------------------|-----------------------|
| 0         | 小地图 (SIFT + 箭头) | (8900, 700, 1000, 1800)  | 右上角 192×194 像素区域      |
| 1         | 物品栏 (OCR)       | (8750, 2870, 1100, 1700) | 右侧中部 211×486 像素区域     |

> **万分数计算**: 实际像素 = 万分数 × 窗口尺寸 / 10000
> 例: ROI-0 x = 8900 × 1920 / 10000 = 1708px
