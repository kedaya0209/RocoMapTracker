# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# RocoMapTracker 架构索引与逻辑约束文档

> **AI 协作专用** — 供 Claude 在后续开发中快速定位类、理解坐标转换与跨语言边界。
> 生成日期: 2026-05-15 | 项目版本: 1.1.0 | Java 25 + GraalVM Native Image | OpenCV: JavaCPP 4.13.0-1.5.13 | 截图: C++
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

# === Rust 原生库编译 (遗留) ===
cd rust && cargo build --release               # 编译 wgc_capture.dll
# 产物: rust/target/release/wgc_capture.dll → 复制到 dll/ 目录

# === C++ 子进程编译 ===
# capture.exe:   WGC 截图引擎 (Visual Studio MSVC)
# sift_match.exe: SIFT 匹配引擎 (Visual Studio MSVC)
# 产物: cpp/capture.exe, cpp/sift_match.exe → 复制到 dll/ 目录

# === C JNI 库编译 (jniframe.c) ===
# Windows: cl /LD /Fe:jniframe.dll c/jniframe.c /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32"

# === Native Image 构建 ===
mvn -Pnative clean package -pl roco-ui -am     # 构建 native exe
mvn -Pnative-instrument clean package -pl roco-ui -am  # PGO Step 1: 插桩
# 运行插桩 exe 采集数据 → 生成 default.iprof
mvn -Pnative-pgo clean package -pl roco-ui -am         # PGO Step 2: 优化构建

# === 运行时 DLL 目录 ===
# 以下文件必须与 exe/jar 同目录:
#   opencv_java490.dll  — JavaCPP OpenCV native (由 Maven 依赖自动提供)
#   capture.exe         — C++ WGC 截图子进程
#   sift_match.exe      — C++ SIFT 匹配子进程
#   jniframe.dll        — JNI 局部引用帧管理
```

---

# Language & Communication Rules (MANDATORY)

- IMPORTANT: You MUST ALWAYS communicate in Simplified Chinese (简体中文).
- All thinking, analysis, code comments, and responses MUST be in Chinese.
- Technical terms may remain in English only when writing code identifiers (variable names, class names, etc.).
- If the user asks a question in Chinese, you MUST reply in Chinese. If they ask in English, still reply in Chinese.
- Violation of this rule is unacceptable.

---

## Step 0 — 多模块结构 (Multi-Module Structure)

项目采用 6 模块 Maven 多模块架构，依赖关系如下：

```
roco-ui ───────────── 最终应用 (JavaFX 界面 + 打包 + Native Image)
  └─ roco-engine ──── 核心引擎 (截图/上下文/Hook事件)
       ├─ roco-macher ─ 匹配算法 (SIFT/小地图/箭头方向)
       │    ├─ roco-model ─ 模型推理 (ONNX/DJL)
       │    │    └─ roco-common ─ 基础工具 (配置/资源/JSON)
       │    └─ roco-common
       ├─ roco-map ──── 地图管理 (下载/拼接/资源点)
       │    └─ roco-common
       └─ roco-common
```

| 模块              | 文件数            | 资源                                       | 职责                                         |
|-----------------|----------------|------------------------------------------|--------------------------------------------|
| **roco-common** | 5              | extract-list.txt                         | 配置中心、JSON/资源工具、ResourceConfigContext       |
| **roco-model**  | 7              | model/*.onnx, model/ppocr_keys_v1.txt    | ONNX 推理基类、CNN 箭头检测、OCR 服务                  |
| **roco-map**    | 26             | source/map/, source/icon/, source/point/ | 地图下载/拼接/瓦片、图标缓存、资源点模型                      |
| **roco-macher** | 15             | —                                        | SIFT 匹配器 (4 变体)、小地图检测、数据集工具                |
| **roco-engine** | 39             | —                                        | 截图采集 (C++子进程)、上下文管理、Hook 事件系统、匹配器调度        |
| **roco-ui**     | 21             | dll/, logback.xml, META-INF/             | JavaFX 界面、MapRenderer 渲染引擎、Native Image 配置 |
| **rust/**       | 1 (lib.rs)     | —                                        | WGC 截图引擎 (独立 Cargo 构建，已由 C++ 替代)           |
| **cpp/**        | 2 (.exe)       | —                                        | C++ WGC 截图 + SIFT 匹配子进程 (Socket IO)        |
| **c/**          | 1 (jniframe.c) | —                                        | JNI 局部引用帧管理 (独立 MSVC 构建)                   |
| **python/**     | 10+            | —                                        | CNN 模型训练/数据集/验证脚本                          |

### 资源归属规则

- **roco-model** — ONNX 模型文件 (`/model/`)
- **roco-map** — 地图/图标/点位配置 (`/source/`)
- **roco-ui** — 运行时 DLL、日志配置、Native Image 元数据
- **roco-common** — 资源提取清单 (`/extract-list.txt`)
- 所有资源运行时通过 classpath 统一访问，模块拆分不影响 `getResourceAsStream()`

---

## Step 1 — 物理清点 (Physical Inventory)

### 1.1 roco-common (基础工具层)

| 类名                      | 职责简述                                     |
|-------------------------|------------------------------------------|
| `AppConfig`             | 静态配置中心：final 常量 + 可持久化变量 (Properties)    |
| `ResourceConfigContext` | 资源套件枚举：INTERNAL / EXTERNAL 路径切换          |
| `FileUtil`              | 文件工具：native 检测 + 资源释放 (extract-list.txt) |
| `JsonUtils`             | Jackson ObjectMapper 单例                  |
| `ResourceUtils`         | 资源路径工具：内嵌/外部/下载                          |

### 1.2 roco-model (模型推理层)

| 类名                    | 职责简述                                           |
|-----------------------|------------------------------------------------|
| `BaseOnnxManager`     | 🔴 ONNX 基类：loadModel / rebuild / newSubManager |
| `ItemResult`          | OCR 结果：record(name, count)                     |
| `ArrowPredictService` | CNN 推理服务：64×64 灰度 → sin/cos → 角度               |
| `ArrowOnnxManager`    | 箭头 ONNX 管理器                                    |
| `OcrService`          | OCR 全流程：letterbox → det → rec                  |
| `OnnxRecManager`      | 识别模型管理器                                        |
| `OnnxDetManager`      | 检测模型管理器                                        |

### 1.3 roco-map (地图管理层)

| 类名                        | 职责简述                                                  |
|---------------------------|-------------------------------------------------------|
| `ImageLoader`             | 🔴 图标缓存：ConcurrentHashMap + 32px 离屏渲染                 |
| `MapAssembler`            | 地图组装器                                                 |
| `MapResourceUpdater`      | 资源更新器：下载地图 + 图标                                       |
| `MapTileProcessor`        | 瓦片处理器                                                 |
| `ResourceExporter`        | 资源导出                                                  |
| `LoadInfo`                | 地图加载信息                                                |
| `DownloadProgressContext` | 下载进度上下文                                               |
| `IconDownloader`          | 图标下载器                                                 |
| `MapStitcher`             | 地图拼接                                                  |
| `MapDownloader`           | 地图下载                                                  |
| `ResourceConfigBuilder`   | 资源配置构建                                                |
| `MapCategoryLoader`       | 地图分类加载                                                |
| `MapConfigLoader`         | 地图配置加载                                                |
| `MapPointLoader`          | 地图点位加载                                                |
| `JsMapConfigParser`       | JS 配置解析器                                              |
| `MapFileMover`            | 文件迁移工具                                                |
| `ResourceConfig`          | 资源配置模型                                                |
| `ResourcePoint`           | 资源点模型：config + screenPosition + grayed + hovered + 渲染 |
| `RoutePath`               | 路线模型：节点列表 + 名称                                        |
| `Point` (map.model)       | 点位模型                                                  |
| `LayerOption`             | 图层选项 DTO                                              |
| `MapCategoryItem`         | 地图分类项 DTO                                             |
| `MapConfig`               | 地图配置 DTO                                              |
| `MapLayer`                | 地图图层 DTO                                              |
| `MapPointItem`            | 地图点位项 DTO                                             |
| `Point` (map.dto)         | 点位 DTO                                                |
| `DownloadResult`          | 下载结果                                                  |
| `Tile`                    | 瓦片实体                                                  |

### 1.4 roco-macher (匹配算法层)

| 类名                       | 职责简述                          |
|--------------------------|-------------------------------|
| `SiftMapMatcher`         | 🔴 统一 SIFT 匹配器：4 个静态工厂覆盖全部变体  |
| `SiftPCAMapMatcher`      | PCA 降维变体                      |
| `SiftUltraMapMatcher`    | 8-bit 量化变体                    |
| `SiftPCAUltraMapMatcher` | PCA + 量化组合变体                  |
| `DescriptorTransform`    | 描述符变换管道：Variant 枚举 + PCA + 量化 |
| `CircleMaskApplier`      | 圆遮罩应用                         |
| `MiniMapDetector`        | 霍夫圆检测                         |
| `PlayerAngle`            | 结果容器：found + angle            |
| `ImageProcessorTest`     | 图像处理测试 (独立 main)              |
| `ImageProcessorTest1`    | 图像处理测试 (独立 main)              |
| `MatchOnlyTest`          | 纯匹配测试 (独立 main)               |
| `CaptureOnlyTest`        | 纯截图测试 (独立 main)               |
| `DatasetGeneratorServer` | 数据集生成服务                       |
| `MoveValidationDataSet`  | 移动验证数据集                       |
| `PCARecalibrator`        | PCA 重校准工具                     |

### 1.5 roco-engine (核心引擎层)

| 类名                                 | 职责简述                                            |
|------------------------------------|-------------------------------------------------|
| `CaptureService`                   | 截图会话管理：Socket 连接 C++ 子进程 + 黑帧检测 + ROI 下发        |
| `CaptureHandler`                   | Socket 帧数据接收与分发                                 |
| `SocketServer`                     | Socket 服务端，管理 C++ 子进程连接                         |
| `SocketSession`                    | Socket 会话封装                                     |
| `SocketHandler`                    | Socket 事件处理器接口                                  |
| `NativeProcess`                    | C++ 子进程生命周期管理                                   |
| `JobObjectManager`                 | Windows Job Object 包装，子进程随父进程自动销毁               |
| `SiftMatchHandler`                 | 🔴 SIFT 匹配请求/响应处理器 (与 C++ sift_match.exe 通信)    |
| `WindowFinder`                     | User32.EnumWindows 按标题查找目标窗口                    |
| `RoiProcessor`                     | 处理器接口：targetRoiIndex() / onProcess() / getRoi() |
| `MapMatcherProcessor`              | ROI-0：小地图检测→圆遮罩→箭头检测→SIFT 匹配→玩家定位               |
| `OcrProcessor`                     | ROI-1：OCR 文字识别→稳定性判定→物资计数                       |
| `SaveImageProcessor`               | 调试用灰度图保存                                        |
| `MapContext`                       | 🔴 核心枢纽：地图图像/视口(scale,offset)/玩家坐标/角度           |
| `MapCoordinateManager`             | 坐标转换器：地图逻辑坐标 ↔ 屏幕坐标                             |
| `CameraContext`                    | 摄像机跟随：followMode + followScale                  |
| `PathContext`                      | 路线管理：绘制/编辑/视图模式 + 持久化                           |
| `ResourcePointContext`             | 资源点位容器：加载/新增/删除 + 网格索引                          |
| `ResourcePointGridIndex`           | 空间网格索引：120px 格子，O(1) 近邻查询                       |
| `MaterialCollectionContext`        | 物资采集统计：累计计数 + 历史流水                              |
| `OcrAsyncManager`                  | OCR 线程池管理：服务池 + 任务队列                            |
| `StatsContext`                     | 性能统计：检测/匹配/方向耗时 + FPS                           |
| `PlayerStateTracker`               | 玩家状态追踪：EMA 平滑 + 瞬移检测 + 地图丢失 + 角度传递              |
| `OcrResultValidator`               | OCR 结果解析与校验                                     |
| `MapMatcher`                       | 匹配器接口：init / match / destroy                    |
| `SwitchMapMatcher`                 | 策略切换器：4 种 SIFT 变体热切换                            |
| `ArrowDetector`                    | 单例：CNN 箭头方向检测入口                                 |
| `IHook` / `AbstractGenericHook<T>` | 钩子接口与泛型基类                                       |
| `HookEventType`                    | 事件枚举：8 种事件类型                                    |
| `HookContainer`                    | 钩子容器：事件类型 → CopyOnWriteArrayList                |
| `HookRegistry`                     | 🔴 枚举单例：注册/发布/销毁入口                              |
| `HookMulticast`                    | 事件分发器：LinkedBlockingQueue + 虚拟线程                |
| `HookEventTask`                    | record(eventType, data)                         |
| `PlayerPositionEvent`              | record(x, y)                                    |
| `StatusEvent`                      | record(message, type)                           |
| `ProgressEvent`                    | record(value, text)                             |
| `CaptureStateEvent`                | record(id, connected, windowTitle)              |
| `MaterialCollectionEvent`          | record(summary)                                 |
| `NotificationType`                 | 枚举：SUCCESS / ERROR / INFO                       |
| `ResourceGrayHook`                 | 监听 PLAYER_UPDATE → 邻近资源点置灰                      |

### 1.6 roco-ui (用户界面层)

| 类名                      | 职责简述                                               |
|-------------------------|----------------------------------------------------|
| `Main`                  | 入口点：初始化 OpenCV → launch JavaFX                     |
| `ModernCanvasApp`       | 🔴 主 Application：初始化流程 + UI 构建 + 截图守护              |
| `InteractiveCanvas`     | 🔴 交互画布：鼠标/键盘事件 + hover + 路线编辑                     |
| `FloatToolbox`          | 浮动工具箱                                              |
| `LoadingOverlay`        | 加载遮罩                                               |
| `NotificationToast`     | Toast 通知 (跟随主题)                                    |
| `ResourceCounterPanel`  | 物资计数面板                                             |
| `RouteManagerStage`     | 路线管理器                                              |
| `Sidebar`               | 侧边栏                                                |
| `StatsOverlay`          | 统计信息覆盖层 (每帧更新)                                     |
| `TitleBar`              | 标题栏                                                |
| `UiAnimator`            | UI 动画                                              |
| `MapRenderer`           | 🔴 渲染引擎：AnimationTimer 驱动，viewportDirty 快照复用       |
| `PlayerRenderer`        | 玩家图标渲染 (角度旋转)                                      |
| `PathRenderer`          | 路线渲染 (viewportDirty 触发)                            |
| `IconCache`             | 🔴 图标缓存：ConcurrentHashMap + 离屏渲染                   |
| `ResourcePointRenderer` | 资源点渲染器                                             |
| `DialogUtils`           | 对话框工具 (跟随主题)                                       |
| `WindowManager`         | 窗口拖拽/缩放                                            |
| `RestartUtils`          | 重启工具                                               |
| `UiResponseHook`        | 监听 UI_NOTIFICATION / INIT_PROGRESS / CAPTURE_STATE |

### 1.7 Rust 源文件 (遗留)

| 文件            | 核心导出                                                                | 职责                               |
|---------------|---------------------------------------------------------------------|----------------------------------|
| `rust/lib.rs` | `create(hwnd, max_fps, cb)` / `set_rois(id, ptr, len)` / `stop(id)` | WGC 截图引擎 (已由 C++ capture.exe 替代) |

### 1.8 C++ 源文件

| 文件                   | 职责                                  |
|----------------------|-------------------------------------|
| `cpp/capture.exe`    | WGC 截图子进程：D3D11 截图 → Socket 推送灰度帧数据 |
| `cpp/sift_match.exe` | SIFT 匹配子进程：Socket 接收匹配请求 → 返回坐标结果   |

### 1.9 C 源文件

| 文件             | 核心导出                                    | 职责                                         |
|----------------|-----------------------------------------|--------------------------------------------|
| `c/jniframe.c` | `JNI_OnLoad` → 注册 `push(I)I` / `pop()I` | JNI 局部引用帧管理：PushLocalFrame / PopLocalFrame |

### 1.10 Python 源文件

| 文件                                          | 职责            |
|---------------------------------------------|---------------|
| `python/gen_dataset.py`                     | 生成箭头方向训练数据集   |
| `python/train_model.py`                     | 训练箭头方向 CNN 模型 |
| `python/onnx_export.py`                     | 导出 ONNX 模型    |
| `python/match.py`                           | 特征匹配工具        |
| `python/clean.py` / `python/deduplicate.py` | 数据清洗/去重       |

---

## Step 2 — 核心枢纽分析 (Core Hub Analysis)

### 2.1 单例模式一览

| 类                           | 所在模块        | 单例方式       | 持有全局状态                                                                                 |
|-----------------------------|-------------|------------|----------------------------------------------------------------------------------------|
| `MapContext`                | roco-engine | Holder 内部类 | mapImage, mapWidth/Height, **scale**, **offsetX/Y**, viewWidth/Height, playerX/Y/Angle |
| `CameraContext`             | roco-engine | Holder 内部类 | followMode (BooleanProperty), followScale                                              |
| `MapCoordinateManager`      | roco-engine | 饿汉式        | mapConfigMap: `Map<String, MapConfig>`                                                 |
| `PathContext`               | roco-engine | 饿汉式        | savedRoutes, currentMode, activeRoute                                                  |
| `ResourcePointContext`      | roco-engine | 饿汉式        | rawResourceList, pointList, typeTemplates, gridIndex, collectSet                       |
| `ResourceConfigContext`     | roco-common | 静态枚举切换     | currentProfile (INTERNAL / EXTERNAL)                                                   |
| `StatsContext`              | roco-engine | 饿汉式        | lastMapDetectMs, lastMatchMs, lastDirectionMs, frequency                               |
| `MaterialCollectionContext` | roco-engine | 饿汉式        | summaryMap, historyLog, filters                                                        |
| `OcrAsyncManager`           | roco-engine | DCL        | executorService, servicePool                                                           |
| `SwitchMapMatcher`          | roco-engine | DCL        | volatile mapMatcher                                                                    |
| `ArrowDetector`             | roco-engine | DCL        | ArrowPredictService                                                                    |
| `ImageLoader`               | roco-map    | 饿汉式        | **imageCache**: ConcurrentHashMap                                                      |
| `IconCache`                 | roco-ui     | 懒汉式        | iconCache: ConcurrentHashMap                                                           |
| `HookRegistry`              | roco-engine | 枚举单例       | HookContainer + HookMulticast                                                          |
| `MapRenderer`               | roco-ui     | DCL        | AnimationTimer, viewportDirty, mapSnapshot                                             |
| `PlayerRenderer`            | roco-ui     | Holder 内部类 | playerImage                                                                            |

### 2.2 数据流枢纽图

```
C++ capture.exe → Socket → CaptureService
  ├─ ROI-0 (小地图) → MapMatcherProcessor
  │    ├─ trackOrDetectMiniMap (霍夫圆检测)
  │    ├─ applyFastCircleMask (圆遮罩)
  │    ├─ executeArrowDect → ArrowDetector → ArrowPredictService → CNN (角度)
  │    ├─ executeMatching → SiftMatchHandler → C++ sift_match.exe (坐标)
  │    └─ MapContext.updatePlayerState(x, y, angle)
  │         └─ HookRegistry.publish(PLAYER_UPDATE)
  │              ├─ ResourceGrayHook → 邻近点置灰
  │              └─ CameraContext.updateViewport()
  │
  └─ ROI-1 (物品栏) → OcrProcessor
       ├─ OcrAsyncManager.submitTask
       ├─ OcrService.recognizeAll (det + rec)
       ├─ OcrResultValidator.parse → ItemResult
       └─ MaterialCollectionContext.addMaterial

MapContext (scale/offset) ← CameraContext ← MapRenderer (AnimationTimer)
  ├─ StatsOverlay.update() (每帧)
  ├─ viewportDirty 检测 (offset/scale/路线状态变化)
  ├─ MapRenderer.drawMapLayer (地图+图标+路线快照, 仅在 viewportDirty 时)
  ├─ MapRenderer.drawPlayer → PlayerRenderer (每帧, 含角度旋转)
  └─ MapRenderer.drawHoverOverlay → InteractiveCanvas
```

---

## Step 3 — 跨语言边界逻辑 (Cross-Language Boundary)

### 3.1 C++ 子进程 ↔ Java Socket 通信

| C++ 侧                      | Java 侧                             | 说明                            |
|----------------------------|------------------------------------|-------------------------------|
| `capture.exe` (WGC 截图)     | `NativeProcess` + `CaptureHandler` | Socket IO 多路复用，灰度帧数据传输        |
| `sift_match.exe` (SIFT 匹配) | `SiftMatchHandler`                 | 请求-响应模式，特征匹配卸载到独立进程           |
| ROI 坐标使用 **万分数** (0~10000) | `MapMatcherProcessor` 配置 ROI 范围    | 百分比定位，自适应分辨率                  |
| `SocketSession` 协议         | 帧头 + 数据体，code=0 正常 / code=-1 断开    | `CaptureService` 判断 code 标记断开 |

### 3.2 子进程生命周期管理

- `NativeProcess` — 启动/监控/重启 C++ 子进程
- `JobObjectManager` — Windows Job Object 包装，父进程退出时子进程自动销毁
- `SocketServer` — 管理多个 C++ 子进程的 Socket 连接和会话

### 3.3 JNI 局部引用管理

```java
// SiftPCAUltraMapMatcher.match() 中:
int pushResult = JNIFrameNative.push(65535);  // PushLocalFrame
try {
    // ... OpenCV 操作产生大量 JNI 局部引用 ...
} finally {
    JNIFrameNative.pop();  // PopLocalFrame，一次性释放
}
```

- **目的**: GraalVM Serial GC 下避免 JNI 局部引用泄漏
- **C 侧**: `PushLocalFrame(65535)` / `PopLocalFrame(NULL)` — 标准 JNI API

---

## Step 4 — 坐标系数学真理 (Coordinate System Mathematics)

### 4.1 三层坐标系定义

| 层级     | 名称                  | 原点         | 单位       | 范例           |
|--------|---------------------|------------|----------|--------------|
| **L1** | 屏幕像素 (Screen Pixel) | Canvas 左上角 | 物理像素     | (550, 400)   |
| **L2** | Canvas 逻辑像素         | 地图左上角      | 1:1 地图像素 | (3200, 2400) |
| **L3** | 地图逻辑坐标              | **地图中心**   | 缩放后逻辑单位  | (-120, 85)   |

### 4.2 转换公式

#### L1 ↔ L2: 屏幕像素 ↔ Canvas 逻辑像素

$$\text{CanvasX} = \frac{\text{ScreenX} - \text{offsetX}}{\text{scale}}$$

$$\text{ScreenX} = \text{offsetX} + \text{CanvasX} \times \text{scale}$$

> 代码来源: `InteractiveCanvas.toLogicX/Y()` (L1→L2), `MapContext.getPlayerCanvasX/Y()` (L2→L1)

#### L2 ↔ L3: Canvas 逻辑像素 ↔ 地图逻辑坐标 (中心原点)

$$\text{CanvasX} = \frac{\text{mapWidth}}{2} + x \cdot 2^{\text{imageZoom} - \text{jsonZoom}}$$

$$x = \frac{\text{CanvasX} - \frac{\text{mapWidth}}{2}}{2^{\text{imageZoom} - \text{jsonZoom}}}$$

> 代码来源: `MapCoordinateManager.toScreen()` / `fromScreen()`
> 缩放因子 `scale_coord = 2^(imageZoom - jsonZoom)`，默认 1:1

### 4.3 缩放交互 (Zoom)

$$\text{newOffsetX} = \text{mouseX} - (\text{mouseX} - \text{oldOffsetX}) \times \frac{\text{newScale}}{\text{oldScale}}$$

> 代码来源: `MapContext.zoom(factor, mx, my)`
> 约束: `minScale = max(viewW/mapW, viewH/mapH)`, `maxScale = 15`

### 4.4 跟随模式 (Follow Mode)

$$\text{offsetX} = \frac{\text{viewWidth}}{2} - \text{playerX} \times \text{followScale}$$

---

## Step 5 — 约束守则 (Development Constraints)

### 5.1 C++ 子进程硬约束

| 约束                    | 原因                                   |
|-----------------------|--------------------------------------|
| ROI 坐标使用万分数 (0~10000) | 自适应不同分辨率/DPI                         |
| Socket IO 多路复用        | 单 Socket 连接处理多 ROI 帧数据               |
| 子进程随父进程自动销毁           | JobObjectManager 绑定，防止孤儿进程           |
| 子进程崩溃自动重连             | NativeProcess 监控 + CaptureService 重连 |

### 5.2 Java 侧硬约束

| 约束                                          | 原因                            |
|---------------------------------------------|-------------------------------|
| SIFT 匹配每帧包裹 `try (PointerScope scope)`      | nopointergc 下 Scope 是唯一批量回收机制 |
| `ArrowPredictService` 每 200 帧重置 NDManager   | DJL + ORT 内部 Arena 分配器累积内存    |
| `OcrService` 成员 Mat 在 `close()` 前禁止 release | 复用容器，后续访问已释放指针 → JVM 崩溃       |
| `ImageLoader.imageCache` 强引用，不可清除           | 渲染循环每帧读取，GC 回收导致图标闪烁          |
| `IconCache.iconCache` 强引用，不可清除              | 渲染循环每帧读取，GC 回收导致图标闪烁          |
| `MapRenderer.mapSnapshot` 只含地图+图标+路线        | viewportDirty 快照复用，动态元素每帧独立绘制 |

### 5.3 架构级约束

| 约束                               | 说明                                |
|----------------------------------|-----------------------------------|
| 所有 UI 操作通过 `Platform.runLater()` | HookMulticast 虚拟线程中直接操作 Node 会抛异常 |
| Hook 事件单向流：数据层 → UI 层            | 禁止在 Hook 回调中修改核心状态                |
| CaptureService 黑帧检测阈值: 30 帧      | 连续 30 帧全黑 → 强停 + 自动重连             |
| OCR 稳定性判定: 2 次连续相同               | 防止 OCR 误识别导致计数跳变                  |
| 地图匹配连续失败 5 次才标记 Lost             | 防止偶发失败导致玩家图标闪烁                    |
| SIFT 匹配通过 C++ 子进程异步执行，不阻塞主线程     | Socket 通信模式，匹配请求与箭头检测并行执行         |

### 5.4 模块间依赖约束

| 约束                                      | 说明                       |
|-----------------------------------------|--------------------------|
| roco-common 不依赖任何内部模块                   | 无 JavaFX、无模型类依赖          |
| roco-model 仅依赖 roco-common              | 推理逻辑不涉及地图/匹配             |
| roco-map 依赖 roco-common + JavaFX        | 资源点/图标渲染需要 JavaFX 类型     |
| roco-macher 依赖 roco-common + roco-model | 不依赖 roco-engine (避免循环依赖) |
| roco-engine 依赖所有核心模块                    | 截图/上下文/事件汇集各层            |
| roco-ui 仅直接依赖 roco-engine               | 其余模块通过传递依赖引入             |

### 5.5 资源路径系统

```
内嵌资源 (classpath):  /model/  /source/map/  /source/icon/  /source/point/  /dll/
  ├─ roco-model: /model/*.onnx, /model/ppocr_keys_v1.txt
  ├─ roco-map:   /source/**
  ├─ roco-ui:    /dll/*.dll, /logback.xml, /META-INF/**
  └─ roco-common: /extract-list.txt

外部资源 (磁盘):  ResourceUtils.getExternalFile()
  ├─ 首次运行: 从 classpath 释放到外部目录
  └─ SIFT 缓存: .feat / .pca64.ultra.feat (Zstd 压缩)
```

### 5.6 GraalVM Native Image 特殊约束

| 约束                                     | 说明                                          |
|----------------------------------------|---------------------------------------------|
| DLL 必须在运行时释放到临时目录                      | Native Image 不支持从 JAR 内直接加载 DLL             |
| `reachability-metadata.json` 必须完整      | 反射/JNI 访问类必须在元数据中声明                         |
| JNI 局部引用泄漏风险更高                         | Serial GC 不像 G1 那样频繁触发                      |
| `System.gc()` 在 Serial GC 下有效          | 同步全量回收                                      |
| roco-common 需依赖 `graal-sdk` (provided) | `--release 25` 限制 classpath，ImageInfo 需显式依赖 |

### 5.7 JavaCPP OpenCV 约束 (nopointergc=true)

项目使用 JavaCPP (`org.bytedeco.opencv.*`) 4.13.0-1.5.13。
`Main.java` 启动时设置 `System.setProperty("org.bytedeco.javacpp.nopointergc", "true")`，完全禁用 GC 自动回收 Native 指针。

#### 5.7.1 内存管理核心规则

| 规则                                                 | 说明                                                     |
|----------------------------------------------------|--------------------------------------------------------|
| **所有临时 Native 对象在 `try (PointerScope scope)` 内创建** | KeyPointVector, Mat, DMatchVectorVector 等，scope 自动批量回收 |
| **FlannBasedMatcher 在 scope 外创建**                  | 长期存活的匹配器，训练临时对象在嵌套 scope 中                             |
| **严禁散乱的 `.close()` 调用**                            | 仅在 `destroy()` 中关闭字段级长期 Mat                            |
| **asyncRebuildMatcher lambda 内打开自己的 scope**        | 调度线程有独立 PointerScope 上下文                               |
| **长期存活的 Mat 在 scope 外创建**                          | 字段级 Mat 在构造/init 中创建于所有 scope 之外                       |
| **字段级 Mat 重建必须在 PointerScope 外**                   | 生命周期跨越多个请求，scope 内重建会析构底层内存                            |

#### 5.7.2 FLANN 强制配置

```java
// ✅ 正确
new FlannBasedMatcher(
    new KDTreeIndexParams(1),       // 单树模式
    new SearchParams(24, 0, true)   // checks=24, 不排序, 启用 KD-tree
);
// ❌ 禁止: new FlannBasedMatcher();  // 默认多树 → 内存超限
```

#### 5.7.3 Mat 数据存取

```java
// 浮点数据 → FloatPointer 包装
new FloatPointer(mat.data()).put(floatArray);
new FloatPointer(mat.data()).get(floatArray);
// 字节数据 → BytePointer (data() 返回 BytePointer)
mat.data().put(byteArray);
// 指定行
new BytePointer(mat.ptr(row, 0)).get(byteArray, 0, width);
```

#### 5.7.4 SIFT.create() 签名 (6 参数)

```java
SIFT.create(nfeatures, nOctaveLayers, contrastThreshold, edgeThreshold, sigma, false);
```

#### 5.7.5 常量和类型对应

| OpenPNP                             | JavaCPP                               |
|-------------------------------------|---------------------------------------|
| `Core.CV_8UC1`                      | `opencv_core.CV_8UC1`                 |
| `Core.CV_32F`                       | `opencv_core.CV_32F`                  |
| `Calib3d.RANSAC`                    | `opencv_calib3d.RANSAC`               |
| `Imgproc.COLOR_BGR2GRAY`            | `opencv_imgproc.COLOR_BGR2GRAY`       |
| `MatOfKeyPoint`                     | `KeyPointVector`                      |
| `MatOfDMatch` / `List<MatOfDMatch>` | `DMatchVector` / `DMatchVectorVector` |
| `Vec3f` (HoughCircles)              | `Point3f`                             |

#### 5.7.6 正确模式示例

```java
// match() — 每帧热路径
public double[][] match(byte[] grayData, int width, int height) {
    prepareSceneMat(width, height);
    sceneRawPixelBuffer.put(grayData);
    try (PointerScope scope = new PointerScope()) {
        KeyPointVector sceneKeyPoints = new KeyPointVector();
        Mat sceneDescriptors = new Mat();
        // ... scope 结束时自动回收
    }
}

// initMatcher() — 匹配器生命周期分离
private void initMatcher() {
    FlannBasedMatcher newMatcher = new FlannBasedMatcher(...);  // scope 外
    try (PointerScope scope = new PointerScope()) {
        Mat tempFloat = new Mat();
        // ... newMatcher.add() 会深拷贝，临时 Mat 可释放
    }
    this.activeMatcher = newMatcher;
}

// destroy() — 唯一允许手动 close 的地方
public void destroy() {
    mapDescriptors.close();
    pcaEigenvectors.close();
    activeMatcher.clear();
    sift.close();
}
```

---

## 附录 A — 事件流全景

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
  ← ResourceGrayHook (置灰后通知)
  → MapRenderer.markDirty() → mapSnapshot = null

HookEventType.MATERIAL_COLLECTION_UPDATED
  ← MaterialCollectionContext.addMaterial/removeMaterial
  → ResourceCounterPanel.refreshData()

HookEventType.MAP_COORD_UPDATED / MAP_NAME_UPDATED / RESOURCE_FOUND
  (预留事件，当前无订阅者)
```

## 附录 B — ROI 布局

| ROI Index | 用途              | 万分数坐标                    | 实际覆盖 (以 1920×1080 为例) |
|-----------|-----------------|--------------------------|-----------------------|
| 0         | 小地图 (SIFT + 箭头) | (8900, 700, 1000, 1800)  | 右上角 192×194 像素区域      |
| 1         | 物品栏 (OCR)       | (8750, 2870, 1100, 1700) | 右侧中部 211×486 像素区域     |

> **万分数计算**: 实际像素 = 万分数 × 窗口尺寸 / 10000
> 例: ROI-0 x = 8900 × 1920 / 10000 = 1708px
